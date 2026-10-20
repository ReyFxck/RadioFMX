package com.reyfxck.radiofmx.fm.backend

import com.reyfxck.radiofmx.debug.DebugLog
import com.reyfxck.radiofmx.fm.core.BackendHealth
import com.reyfxck.radiofmx.fm.core.BackendKind
import com.reyfxck.radiofmx.fm.core.BackendProbe
import com.reyfxck.radiofmx.fm.core.FmBackend
import com.reyfxck.radiofmx.fm.core.RadioCapabilities
import com.reyfxck.radiofmx.fm.core.RadioState
import com.reyfxck.radiofmx.fm.nativebridge.NativeFmBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.round

class NativeV4l2Backend : FmBackend {
    override val id = "native-v4l2"
    override val name = "V4L2 nativo"
    override val kind = BackendKind.NATIVE_V4L2
    override val priority = 100
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

    @Volatile private var devicePath: String? = null
    @Volatile private var handle: Long = 0
    private var scanJob: Job? = null

    override fun probe(): BackendProbe {
        val result = NativeFmBridge.probe()
        devicePath = result.path
        val health = when {
            result.available -> BackendHealth.AVAILABLE
            result.blocked -> BackendHealth.BLOCKED
            result.status == "MISSING" -> BackendHealth.MISSING
            else -> BackendHealth.ERROR
        }
        return BackendProbe(
            id = id,
            name = name,
            kind = kind,
            priority = priority,
            health = health,
            summary = when (health) {
                BackendHealth.AVAILABLE -> "Receptor V4L2 acessível diretamente pelo APK"
                BackendHealth.BLOCKED -> "Dispositivo FM detectado, mas acesso bloqueado"
                BackendHealth.MISSING -> "Nenhum nó V4L2 FM acessível"
                else -> "Falha no bridge nativo"
            },
            details = listOfNotNull(
                result.path?.let { "device=$it" },
                result.errno?.takeIf { it != 0 }?.let { "errno=$it" },
                result.detail.takeIf { it.isNotBlank() },
                NativeFmBridge.loadError()?.let { "jni=$it" },
            ),
        )
    }

    override fun connect() {
        val probe = probe()
        val ok = probe.health == BackendHealth.AVAILABLE && devicePath != null
        mutableState.value = baseState().copy(
            available = ok,
            connected = ok,
            lastError = if (ok) null else probe.summary,
        )
        DebugLog.i("ENGINE", "$name probe=${probe.health}: ${probe.summary}")
    }

    override fun disconnect() {
        cancelScan()
        closeHandle()
        mutableState.value = baseState()
    }

    override fun refresh() {
        val currentHandle = handle
        if (currentHandle == 0L) return
        scope.launch {
            val freq = NativeFmBridge.nativeGetFrequency(currentHandle).takeIf { it > 0f }
            val signal = NativeFmBridge.nativeGetSignal(currentHandle).takeIf { it >= 0 }
            mutableState.value = mutableState.value.copy(
                powered = true,
                frequencyMhz = freq,
                rssi = signal,
                lastError = null,
            )
        }
    }

    override fun setPower(on: Boolean, frequencyMhz: Float) {
        scope.launch {
            if (!on) {
                closeHandle()
                mutableState.value = mutableState.value.copy(
                    powered = false,
                    scanning = false,
                    frequencyMhz = null,
                    rssi = null,
                    lastError = null,
                )
                DebugLog.i("NATIVE", "Tuner V4L2 fechado")
                return@launch
            }

            val path = devicePath ?: probe().let {
                if (it.health == BackendHealth.AVAILABLE) devicePath else null
            }
            if (path == null) {
                return@launch fail("Nenhum dispositivo V4L2 FM acessível.")
            }

            if (handle == 0L) {
                handle = NativeFmBridge.nativeOpen(path)
            }
            if (handle == 0L) {
                return@launch fail("O kernel recusou abrir $path.")
            }

            val tuned = NativeFmBridge.nativeTune(handle, frequencyMhz)
            if (!tuned) {
                closeHandle()
                return@launch fail(
                    "O dispositivo abriu, mas VIDIOC_S_FREQUENCY foi recusado. " +
                        "O driver pode exigir inicialização/vendor controls privilegiados."
                )
            }

            DebugLog.i("NATIVE", "Power+tune direto em ${"%.1f".format(frequencyMhz)} MHz")
            mutableState.value = mutableState.value.copy(
                available = true,
                connected = true,
                powered = true,
                frequencyMhz = frequencyMhz,
                lastError = null,
            )
            refresh()
        }
    }

    override fun tune(frequencyMhz: Float) {
        scope.launch {
            val currentHandle = handle
            if (currentHandle == 0L) {
                return@launch fail("Ligue o tuner V4L2 antes de sintonizar.")
            }
            val ok = NativeFmBridge.nativeTune(currentHandle, frequencyMhz)
            if (!ok) return@launch fail("Sintonia V4L2 recusada em ${"%.1f".format(frequencyMhz)} MHz.")
            mutableState.value = mutableState.value.copy(
                frequencyMhz = frequencyMhz,
                lastError = null,
            )
            DebugLog.i("NATIVE", "Tune direto: ${"%.1f".format(frequencyMhz)} MHz")
            refresh()
        }
    }

    override fun seek(up: Boolean) {
        scope.launch {
            val currentHandle = handle
            if (currentHandle == 0L) return@launch fail("Tuner V4L2 desligado.")
            val result = NativeFmBridge.nativeSeek(currentHandle, up)
            if (result <= 0f) return@launch fail("Seek V4L2 não encontrou/retornou frequência.")
            mutableState.value = mutableState.value.copy(
                frequencyMhz = result,
                lastError = null,
            )
            DebugLog.i("NATIVE", "Seek ${if (up) "↑" else "↓"} -> ${"%.1f".format(result)} MHz")
            refresh()
        }
    }

    override fun scan() {
        if (scanJob?.isActive == true) return
        val currentHandle = handle
        if (currentHandle == 0L) {
            fail("Ligue o tuner V4L2 antes do scan.")
            return
        }

        scanJob = scope.launch {
            mutableState.value = mutableState.value.copy(scanning = true, lastError = null)
            val start = NativeFmBridge.nativeGetFrequency(currentHandle).takeIf { it > 0f } ?: 87.5f
            val found = mutableListOf<Float>()
            try {
                var wrapped = false
                repeat(40) {
                    if (wrapped) return@repeat
                    val next = NativeFmBridge.nativeSeek(currentHandle, true)
                    if (next <= 0f) return@repeat
                    val rounded = round(next * 10f) / 10f
                    if (found.none { abs(it - rounded) < 0.05f }) {
                        found += rounded
                    } else if (found.size > 1 && abs(rounded - start) < 0.08f) {
                        wrapped = true
                    }
                    delay(60)
                }
                val stations = found.distinct().sorted()
                DebugLog.i("SCAN", "V4L2 scan terminou com ${stations.size} estação(ões)")
                mutableState.value = mutableState.value.copy(
                    scanning = false,
                    stations = stations,
                    lastError = if (stations.isEmpty()) "Scan V4L2 terminou sem estações." else null,
                )
                refresh()
            } catch (_: CancellationException) {
                DebugLog.i("SCAN", "Scan V4L2 cancelado")
            } catch (t: Throwable) {
                fail("Falha no scan V4L2: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                mutableState.value = mutableState.value.copy(scanning = false)
            }
        }
    }

    override fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        mutableState.value = mutableState.value.copy(scanning = false)
    }

    override fun setSpeaker(enabled: Boolean) {
        fail("Roteamento de alto-falante não é exposto pelo backend V4L2 genérico.")
    }

    private fun closeHandle() {
        val current = handle
        handle = 0
        if (current != 0L) {
            runCatching { NativeFmBridge.nativeClose(current) }
        }
    }

    private fun fail(message: String) {
        DebugLog.e("NATIVE", message)
        mutableState.value = mutableState.value.copy(
            lastError = message,
            scanning = false,
        )
    }

    private fun baseState() = RadioState(
        backendId = id,
        backendName = name,
        backendKind = kind,
        capabilities = capabilities,
    )
}
