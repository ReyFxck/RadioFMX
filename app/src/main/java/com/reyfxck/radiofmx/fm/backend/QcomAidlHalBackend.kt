package com.reyfxck.radiofmx.fm.backend

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import com.reyfxck.radiofmx.debug.DebugLog
import com.reyfxck.radiofmx.fm.core.BackendHealth
import com.reyfxck.radiofmx.fm.core.BackendKind
import com.reyfxck.radiofmx.fm.core.BackendProbe
import com.reyfxck.radiofmx.fm.core.FmBackend
import com.reyfxck.radiofmx.fm.core.RadioCapabilities
import com.reyfxck.radiofmx.fm.core.RadioState
import com.reyfxck.radiofmx.fm.nativebridge.NativeFmBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.round

/**
 * Direct client for Qualcomm's stable FM HAL.
 *
 * This bypasses both the FM2 Activity and qcom.fmradio/libqcomfm_jni. The wire
 * protocol below is the small stable AIDL IFmHci transport used by current
 * Qualcomm FM stacks: initialize(callback), sendHciCommand(byte[]), close().
 */
class QcomAidlHalBackend : FmBackend {
    override val id = "qcom-aidl-hal"
    override val name = "Qualcomm FM HAL (AIDL)"
    override val kind = BackendKind.QCOM_AIDL_HAL
    override val priority = 160
    override val capabilities = RadioCapabilities(
        power = true,
        tune = true,
        seek = true,
        scan = true,
        signalStrength = true,
        rds = false,
        speakerRoute = false,
        requiresOemBootstrap = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(baseState())
    override val state: StateFlow<RadioState> = mutableState.asStateFlow()

    private var client: QcomFmAidlClient? = null
    private var callback: FmHalCallbackBinder? = null
    private var initialized = false
    private var initDeferred: CompletableDeferred<Int>? = null
    private val commandWaiters = ConcurrentHashMap<Int, CompletableDeferred<Int>>()
    private var tuneDeferred: CompletableDeferred<Float>? = null
    private var searchDeferred: CompletableDeferred<Float?>? = null
    private var scanJob: Job? = null

    @Volatile private var lastFrequencyKhz: Int? = null
    @Volatile private var lastRssi: Int? = null

    override fun probe(): BackendProbe {
        if (!NativeFmBridge.loaded) {
            return BackendProbe(
                id = id,
                name = name,
                kind = kind,
                priority = priority,
                health = BackendHealth.ERROR,
                summary = "Bridge nativo do RadioFMX não carregou",
                details = listOf(NativeFmBridge.loadError() ?: "erro JNI"),
            )
        }

        val raw = runCatching { NativeFmBridge.nativeQcomAidlProbe() }
            .getOrElse { "ERROR|${it.javaClass.simpleName}: ${it.message}" }
        val parts = raw.split('|')
        val status = parts.firstOrNull().orEmpty()
        val details = parts.drop(1).filter { it.isNotBlank() }

        val health = when (status) {
            "OK" -> BackendHealth.AVAILABLE
            "BLOCKED" -> BackendHealth.BLOCKED
            "MISSING" -> BackendHealth.MISSING
            else -> BackendHealth.ERROR
        }

        val hidlProbe = if (status != "OK") {
            runCatching { NativeFmBridge.nativeQcomHidlProbe() }
                .getOrElse { "ERROR|${it.javaClass.simpleName}: ${it.message}" }
        } else {
            null
        }

        return BackendProbe(
            id = id,
            name = name,
            kind = kind,
            priority = priority,
            health = health,
            summary = when (health) {
                BackendHealth.AVAILABLE ->
                    "Serviço FM Qualcomm AIDL acessível diretamente ao RadioFMX"
                BackendHealth.BLOCKED ->
                    "HAL FM AIDL existe, mas o Android/SELinux não liberou o Binder ao APK"
                BackendHealth.MISSING ->
                    "HAL FM Qualcomm AIDL não encontrado; o dispositivo pode usar HIDL"
                else ->
                    "Não foi possível consultar o HAL FM Qualcomm AIDL"
            },
            details = buildList {
                addAll(details)
                hidlProbe?.let { add("HIDL probe: $it") }
            },
        )
    }

    override fun connect() {
        val p = probe()
        val ok = p.health == BackendHealth.AVAILABLE
        mutableState.value = baseState().copy(
            available = ok,
            connected = ok,
            lastError = if (ok) null else p.summary,
        )
        DebugLog.i("HAL", "Qualcomm AIDL probe=${p.health}: ${p.summary} ${p.details}")
    }

    override fun disconnect() {
        scanJob?.cancel()
        scanJob = null
        commandWaiters.values.forEach { it.cancel() }
        commandWaiters.clear()
        tuneDeferred?.cancel()
        searchDeferred?.cancel()
        initDeferred?.cancel()

        runCatching { client?.close() }
        client = null
        callback = null
        initialized = false
        mutableState.value = baseState()
    }

    override fun refresh() {
        mutableState.value = mutableState.value.copy(
            frequencyMhz = lastFrequencyKhz?.div(1000f) ?: mutableState.value.frequencyMhz,
            rssi = lastRssi ?: mutableState.value.rssi,
        )
    }

    override fun setPower(on: Boolean, frequencyMhz: Float) {
        scope.launch {
            try {
                val c = ensureInitialized()
                if (!on) {
                    val status = sendAndAwait(c, OGF_RECV, OCF_DISABLE_RECV)
                    if (status != 0) {
                        return@launch fail("HAL Qualcomm recusou desligar o receptor: status=$status")
                    }
                    initialized = false
                    runCatching { c.close() }
                    client = null
                    callback = null
                    mutableState.value = mutableState.value.copy(
                        powered = false,
                        scanning = false,
                        frequencyMhz = null,
                        rssi = null,
                        lastError = null,
                    )
                    DebugLog.i("HAL", "FM Qualcomm desligado diretamente pelo HAL")
                    return@launch
                }

                DebugLog.i("HAL", "Enviando HCI FM_ENABLE_RECV diretamente ao HAL")
                val status = sendAndAwait(c, OGF_RECV, OCF_ENABLE_RECV)
                if (status != 0) {
                    return@launch fail("FM_ENABLE_RECV falhou: status=$status")
                }

                mutableState.value = mutableState.value.copy(
                    powered = true,
                    connected = true,
                    available = true,
                    lastError = null,
                )
                tuneInternal(c, frequencyMhz)
            } catch (t: Throwable) {
                fail(
                    "Falha ao ligar pelo HAL Qualcomm AIDL",
                    t,
                )
            }
        }
    }

    override fun tune(frequencyMhz: Float) {
        scope.launch {
            try {
                if (!mutableState.value.powered) {
                    return@launch fail("Ligue o tuner Qualcomm antes de sintonizar.")
                }
                val c = client ?: return@launch fail("HAL Qualcomm não inicializado.")
                tuneInternal(c, frequencyMhz)
            } catch (t: Throwable) {
                fail("Falha de sintonia no HAL Qualcomm", t)
            }
        }
    }

    override fun seek(up: Boolean) {
        scope.launch {
            try {
                if (!mutableState.value.powered) {
                    return@launch fail("Ligue o tuner Qualcomm antes do seek.")
                }
                val c = client ?: return@launch fail("HAL Qualcomm não inicializado.")
                val found = seekInternal(c, up)
                if (found == null) {
                    fail("Seek Qualcomm terminou sem frequência válida.")
                } else {
                    mutableState.value = mutableState.value.copy(
                        frequencyMhz = found,
                        lastError = null,
                    )
                    DebugLog.i("HAL", "Seek ${if (up) "↑" else "↓"} -> ${"%.1f".format(found)} MHz")
                }
            } catch (t: Throwable) {
                fail("Falha no seek Qualcomm", t)
            }
        }
    }

    override fun scan() {
        if (scanJob?.isActive == true) return
        if (!mutableState.value.powered) {
            fail("Ligue o tuner Qualcomm antes do scan.")
            return
        }

        scanJob = scope.launch {
            val c = client ?: return@launch fail("HAL Qualcomm não inicializado.")
            val found = mutableListOf<Float>()
            val start = mutableState.value.frequencyMhz ?: 87.5f

            mutableState.value = mutableState.value.copy(scanning = true, lastError = null)
            DebugLog.i("SCAN", "Scan direto via Qualcomm AIDL iniciado")

            try {
                var wrapped = false
                repeat(32) {
                    if (wrapped) return@repeat
                    val next = seekInternal(c, up = true) ?: return@repeat
                    val rounded = round(next * 10f) / 10f
                    if (found.none { abs(it - rounded) < 0.05f }) {
                        found += rounded
                        DebugLog.i("SCAN", "HAL encontrou ${"%.1f".format(rounded)} MHz")
                    } else if (found.size > 1 && abs(rounded - start) < 0.08f) {
                        wrapped = true
                    }
                    delay(60)
                }

                val stations = found.distinct().sorted()
                mutableState.value = mutableState.value.copy(
                    scanning = false,
                    stations = stations,
                    lastError = if (stations.isEmpty()) {
                        "Scan Qualcomm terminou sem estações."
                    } else null,
                )
                DebugLog.i("SCAN", "Scan AIDL terminou com ${stations.size} estação(ões)")
            } catch (t: Throwable) {
                fail("Falha no scan Qualcomm AIDL", t)
            } finally {
                mutableState.value = mutableState.value.copy(scanning = false)
            }
        }
    }

    override fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        searchDeferred?.cancel()
        searchDeferred = null
        scope.launch {
            runCatching {
                client?.sendHciCommand(command(OGF_RECV, OCF_CANCEL_SEARCH))
            }.onFailure {
                DebugLog.w("SCAN", "Cancel HCI falhou: ${it.javaClass.simpleName}: ${it.message}")
            }
            mutableState.value = mutableState.value.copy(scanning = false)
        }
    }

    override fun setSpeaker(enabled: Boolean) {
        fail(
            "O HAL FM controla o receptor, mas o roteamento de áudio é uma camada separada do Audio HAL."
        )
    }

    private suspend fun ensureInitialized(): QcomFmAidlClient {
        client?.takeIf { initialized }?.let { return it }

        val binder = NativeFmBridge.nativeQcomAidlBinder()
            ?: throw IllegalStateException(
                "Serviço vendor.qti.hardware.fm.IFmHci/default não ficou acessível."
            )

        val c = QcomFmAidlClient(binder)
        val cb = FmHalCallbackBinder(
            onInit = { status ->
                DebugLog.i("HAL", "initializationComplete(status=$status)")
                initDeferred?.complete(status)
            },
            onEvent = ::handleEvent,
        )

        val marked = NativeFmBridge.nativeMarkVintfStability(cb)
        DebugLog.i("HAL", "Callback Binder marcado VINTF=$marked")
        if (!marked) {
            throw IllegalStateException("Não foi possível marcar o callback Binder como VINTF-stable.")
        }

        client = c
        callback = cb
        val deferred = CompletableDeferred<Int>()
        initDeferred = deferred

        DebugLog.i("HAL", "Chamando IFmHci.initialize() diretamente")
        c.initialize(cb)

        val status = try {
            withTimeout(5_000) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            throw IllegalStateException("HAL FM não respondeu initialize() em 5 s.")
        } finally {
            initDeferred = null
        }

        if (status != STATUS_SUCCESS) {
            throw IllegalStateException("HAL FM initialize retornou status=$status")
        }

        initialized = true
        mutableState.value = mutableState.value.copy(
            available = true,
            connected = true,
            lastError = null,
        )
        return c
    }

    private suspend fun tuneInternal(c: QcomFmAidlClient, frequencyMhz: Float) {
        val khz = (frequencyMhz * 1000f).toInt()
        val deferred = CompletableDeferred<Float>()
        tuneDeferred = deferred
        lastFrequencyKhz = khz

        DebugLog.i("HAL", "HCI tune -> $khz kHz")
        c.sendHciCommand(
            command(
                OGF_COMMON,
                OCF_TUNE_STATION,
                ByteBuffer.allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(khz)
                    .array(),
            )
        )

        val tuned = try {
            withTimeout(4_000) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            DebugLog.w("HAL", "Sem TUNE_STATUS em 4 s; mantendo frequência solicitada")
            frequencyMhz
        } finally {
            tuneDeferred = null
        }

        mutableState.value = mutableState.value.copy(
            powered = true,
            frequencyMhz = tuned,
            rssi = lastRssi,
            lastError = null,
        )
    }

    private suspend fun seekInternal(c: QcomFmAidlClient, up: Boolean): Float? {
        val deferred = CompletableDeferred<Float?>()
        searchDeferred = deferred

        val direction = if (up) SRCH_DIR_UP else SRCH_DIR_DOWN
        val payload = byteArrayOf(
            SEEK_MODE.toByte(),
            0,
            direction.toByte(),
        )

        DebugLog.i("HAL", "HCI seek ${if (up) "up" else "down"}")
        c.sendHciCommand(command(OGF_RECV, OCF_SEARCH_STATIONS, payload))

        return try {
            withTimeout(10_000) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            runCatching { c.sendHciCommand(command(OGF_RECV, OCF_CANCEL_SEARCH)) }
            null
        } finally {
            searchDeferred = null
        }
    }

    private suspend fun sendAndAwait(
        c: QcomFmAidlClient,
        ogf: Int,
        ocf: Int,
        payload: ByteArray = byteArrayOf(),
    ): Int {
        val opcode = opcode(ogf, ocf)
        val deferred = CompletableDeferred<Int>()
        commandWaiters[opcode] = deferred

        c.sendHciCommand(command(ogf, ocf, payload))

        return try {
            withTimeout(5_000) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            throw IllegalStateException("Timeout aguardando resposta HCI opcode=0x${opcode.toString(16)}")
        } finally {
            commandWaiters.remove(opcode)
        }
    }

    private fun handleEvent(event: ByteArray) {
        if (event.size < 2) {
            DebugLog.w("HAL", "Evento HCI curto: ${event.toHex()}")
            return
        }

        val eventCode = event[0].toInt() and 0xff
        DebugLog.i("HAL-EVT", "0x${eventCode.toString(16)} len=${event.size}: ${event.toHex()}")

        when (eventCode) {
            HCI_EV_CMD_COMPLETE -> {
                // [evt,len, numPackets, opcodeLSB, opcodeMSB, status, ...]
                if (event.size >= 6) {
                    val op = (event[3].toInt() and 0xff) or
                        ((event[4].toInt() and 0xff) shl 8)
                    val status = event[5].toInt() and 0xff
                    commandWaiters.remove(op)?.complete(status)
                }
            }

            HCI_EV_CMD_STATUS -> {
                // [evt,len,status,numPackets,opcodeLSB,opcodeMSB]
                if (event.size >= 6) {
                    val status = event[2].toInt() and 0xff
                    val op = (event[4].toInt() and 0xff) or
                        ((event[5].toInt() and 0xff) shl 8)
                    if (status != 0) commandWaiters.remove(op)?.complete(status)
                }
            }

            HCI_EV_TUNE_STATUS -> {
                // params: sub_event + packed little-endian station_freq + ...
                if (event.size >= 7) {
                    val khz = ByteBuffer.wrap(event, 3, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .int
                    if (khz in 76_000..108_000) {
                        lastFrequencyKhz = khz
                        if (event.size > 8) {
                            lastRssi = event[8].toInt()
                        }
                        val mhz = khz / 1000f
                        mutableState.value = mutableState.value.copy(
                            powered = true,
                            frequencyMhz = mhz,
                            rssi = lastRssi,
                            lastError = null,
                        )
                        tuneDeferred?.complete(mhz)
                    }
                }
            }

            HCI_EV_SEARCH_COMPLETE,
            HCI_EV_SEARCH_RDS_COMPLETE -> {
                val mhz = lastFrequencyKhz?.div(1000f)
                searchDeferred?.complete(mhz)
            }

            HCI_EV_HW_ERROR -> {
                fail("HAL FM reportou erro de hardware.")
            }
        }
    }

    private fun fail(message: String, error: Throwable? = null) {
        val suffix = error?.let {
            ": ${it.javaClass.simpleName}: ${it.message ?: "sem mensagem"}"
        }.orEmpty()
        if (error == null) DebugLog.e("HAL", message) else DebugLog.e("HAL", message, error)
        mutableState.value = mutableState.value.copy(
            lastError = message + suffix,
            scanning = false,
        )
    }

    private fun baseState() = RadioState(
        backendId = id,
        backendName = name,
        backendKind = kind,
        capabilities = capabilities,
    )

    private fun command(ogf: Int, ocf: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val op = opcode(ogf, ocf)
        return ByteArray(3 + payload.size).also { out ->
            out[0] = (op and 0xff).toByte()
            out[1] = ((op ushr 8) and 0xff).toByte()
            out[2] = payload.size.toByte()
            payload.copyInto(out, destinationOffset = 3)
        }
    }

    private fun opcode(ogf: Int, ocf: Int): Int =
        (ocf and 0x03ff) or (ogf shl 10)

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val STATUS_SUCCESS = 0

        private const val OGF_RECV = 0x13
        private const val OGF_COMMON = 0x15

        private const val OCF_ENABLE_RECV = 0x01
        private const val OCF_DISABLE_RECV = 0x02
        private const val OCF_SEARCH_STATIONS = 0x0E
        private const val OCF_CANCEL_SEARCH = 0x11
        private const val OCF_TUNE_STATION = 0x01

        private const val SEEK_MODE = 0
        private const val SRCH_DIR_UP = 0
        private const val SRCH_DIR_DOWN = 1

        private const val HCI_EV_TUNE_STATUS = 0x01
        private const val HCI_EV_CMD_COMPLETE = 0x0F
        private const val HCI_EV_CMD_STATUS = 0x10
        private const val HCI_EV_SEARCH_COMPLETE = 0x12
        private const val HCI_EV_SEARCH_RDS_COMPLETE = 0x13
        private const val HCI_EV_HW_ERROR = 0x1A
    }
}

private class QcomFmAidlClient(
    private val remote: IBinder,
) {
    fun initialize(callback: IBinder) {
        transact(TRANSACTION_INITIALIZE) { data ->
            data.writeStrongBinder(callback)
        }
    }

    fun sendHciCommand(command: ByteArray) {
        transact(TRANSACTION_SEND_HCI_COMMAND) { data ->
            data.writeByteArray(command)
        }
    }

    fun close() {
        transact(TRANSACTION_CLOSE) { }
    }

    private inline fun transact(code: Int, write: (Parcel) -> Unit) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            if (!remote.transact(code, data, reply, 0)) {
                throw RemoteException("IFmHci transact($code) retornou false")
            }
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    companion object {
        private const val DESCRIPTOR = "vendor.qti.hardware.fm.IFmHci"
        private const val TRANSACTION_CLOSE = IBinder.FIRST_CALL_TRANSACTION + 0
        private const val TRANSACTION_INITIALIZE = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TRANSACTION_SEND_HCI_COMMAND = IBinder.FIRST_CALL_TRANSACTION + 2
    }
}

private class FmHalCallbackBinder(
    private val onInit: (Int) -> Unit,
    private val onEvent: (ByteArray) -> Unit,
) : Binder(), IInterface {
    init {
        attachInterface(this, DESCRIPTOR)
    }

    override fun asBinder(): IBinder = this

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            INTERFACE_TRANSACTION -> {
                reply?.writeString(DESCRIPTOR)
                true
            }

            TRANSACTION_HCI_EVENT -> {
                data.enforceInterface(DESCRIPTOR)
                val event = data.createByteArray() ?: byteArrayOf()
                onEvent(event)
                reply?.writeNoException()
                true
            }

            TRANSACTION_INITIALIZATION_COMPLETE -> {
                data.enforceInterface(DESCRIPTOR)
                val status = data.readInt()
                onInit(status)
                reply?.writeNoException()
                true
            }

            TRANSACTION_GET_INTERFACE_HASH -> {
                data.enforceInterface(DESCRIPTOR)
                reply?.writeNoException()
                reply?.writeString(HASH)
                true
            }

            TRANSACTION_GET_INTERFACE_VERSION -> {
                data.enforceInterface(DESCRIPTOR)
                reply?.writeNoException()
                reply?.writeInt(VERSION)
                true
            }

            else -> super.onTransact(code, data, reply, flags)
        }
    }

    companion object {
        private const val DESCRIPTOR = "vendor.qti.hardware.fm.IFmHciCallbacks"
        private const val VERSION = 1
        private const val HASH = "af88707cae8bd863668def1a0df8df2e298c549f"

        private const val TRANSACTION_HCI_EVENT = IBinder.FIRST_CALL_TRANSACTION + 0
        private const val TRANSACTION_INITIALIZATION_COMPLETE = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TRANSACTION_GET_INTERFACE_HASH =
            IBinder.FIRST_CALL_TRANSACTION + 16_777_213
        private const val TRANSACTION_GET_INTERFACE_VERSION =
            IBinder.FIRST_CALL_TRANSACTION + 16_777_214
    }
}
