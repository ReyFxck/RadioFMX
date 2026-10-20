package com.reyfxck.radiofmx.fm.backend

import android.content.Context
import com.reyfxck.radiofmx.debug.DebugLog
import com.reyfxck.radiofmx.fm.core.BackendHealth
import com.reyfxck.radiofmx.fm.core.BackendKind
import com.reyfxck.radiofmx.fm.core.BackendProbe
import com.reyfxck.radiofmx.fm.core.FmBackend
import com.reyfxck.radiofmx.fm.core.RadioCapabilities
import com.reyfxck.radiofmx.fm.core.RadioState
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
import java.lang.reflect.InvocationTargetException
import kotlin.math.abs
import kotlin.math.round

class QcomSharedLibraryBackend(context: Context) : FmBackend {
    override val id = "qcom-shared"
    override val name = "Qualcomm qcom.fmradio"
    override val kind = BackendKind.QCOM_SHARED_LIBRARY
    override val priority = 120
    override val capabilities = RadioCapabilities(
        power = true,
        tune = true,
        seek = true,
        scan = true,
        signalStrength = true,
        rds = true,
        speakerRoute = false,
        requiresOemBootstrap = false,
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(baseState())
    override val state: StateFlow<RadioState> = mutableState.asStateFlow()

    private var receiverClass: Class<*>? = null
    private var transceiverClass: Class<*>? = null
    private var callbackClass: Class<*>? = null
    private var configClass: Class<*>? = null
    private var receiver: Any? = null
    private var scanJob: Job? = null

    override fun probe(): BackendProbe {
        return try {
            receiverClass = Class.forName("qcom.fmradio.FmReceiver")
            transceiverClass = Class.forName("qcom.fmradio.FmTransceiver")
            callbackClass = Class.forName("qcom.fmradio.FmRxEvCallbacksAdaptor")
            configClass = Class.forName("qcom.fmradio.FmConfig")

            BackendProbe(
                id = id,
                name = name,
                kind = kind,
                priority = priority,
                health = BackendHealth.AVAILABLE,
                summary = "Shared library qcom.fmradio visível ao RadioFMX",
                details = listOf(
                    "execução no processo do RadioFMX",
                    "usa libqcomfm_jni/Helium quando o sistema permite",
                    "sem Binder do app FM",
                ),
            )
        } catch (t: Throwable) {
            BackendProbe(
                id = id,
                name = name,
                kind = kind,
                priority = priority,
                health = BackendHealth.MISSING,
                summary = "Shared library qcom.fmradio não está disponível para este APK",
                details = listOf(errorText(t)),
            )
        }
    }

    override fun connect() {
        val probe = probe()
        val ok = probe.health == BackendHealth.AVAILABLE
        mutableState.value = baseState().copy(
            available = ok,
            connected = ok,
            lastError = if (ok) null else probe.summary,
        )
        DebugLog.i("QCOM", "Probe shared library: ${probe.health} - ${probe.summary}")
    }

    override fun disconnect() {
        scanJob?.cancel()
        scanJob = null
        scope.launch {
            val r = receiver
            if (r != null) {
                runCatching {
                    transceiverClass?.getMethod("disable")?.invoke(r)
                }
            }
            receiver = null
            mutableState.value = baseState()
        }
    }

    override fun refresh() {
        scope.launch {
            val r = receiver ?: return@launch
            try {
                val fmState = invokeInt(r, "getFMState")
                val powered = fmState == 1 || fmState == 3
                val frequency = if (powered) {
                    invokeInt(r, "getTunedFrequency").takeIf { it in 76000..108000 }?.div(1000f)
                } else null
                val rssi = if (powered) runCatching { invokeInt(r, "getRssi") }.getOrNull() else null
                val ps = if (powered) readRdsText(r, "getPSInfo", "getPrgmServices") else ""
                val rt = if (powered) readRdsText(r, "getRTInfo", "getRadioText") else ""

                mutableState.value = mutableState.value.copy(
                    available = true,
                    connected = true,
                    powered = powered,
                    scanning = fmState == 3 || mutableState.value.scanning,
                    frequencyMhz = frequency ?: mutableState.value.frequencyMhz,
                    rssi = rssi,
                    programService = ps,
                    radioText = rt,
                    lastError = null,
                )
            } catch (t: Throwable) {
                fail("Falha ao atualizar qcom.fmradio", t)
            }
        }
    }

    override fun setPower(on: Boolean, frequencyMhz: Float) {
        scope.launch {
            try {
                val r = ensureReceiver()
                if (!on) {
                    val ok = (transceiverClass
                        ?.getMethod("disable")
                        ?.invoke(r) as? Boolean) ?: false
                    DebugLog.i("QCOM", "disable() retornou $ok")
                    delay(350)
                    mutableState.value = mutableState.value.copy(
                        powered = false,
                        scanning = false,
                        frequencyMhz = null,
                        rssi = null,
                        programService = "",
                        radioText = "",
                        lastError = if (ok) null else "A pilha Qualcomm não confirmou o desligamento.",
                    )
                    return@launch
                }

                val config = createConfig()
                val ok = (transceiverClass
                    ?.getMethod("enable", configClass, Int::class.javaPrimitiveType)
                    ?.invoke(r, config, 1) as? Boolean) ?: false

                DebugLog.i("QCOM", "FmTransceiver.enable(FM_RX) retornou $ok")
                if (!ok) {
                    return@launch fail(
                        "qcom.fmradio foi carregado, mas a pilha Qualcomm/Helium recusou ligar o receptor."
                    )
                }

                val ready = waitForState(r, target = 1, timeoutMs = 8_000)
                if (!ready) {
                    return@launch fail(
                        "A pilha Qualcomm aceitou o power-on, mas não chegou ao estado FM_RX em 8 s."
                    )
                }

                mutableState.value = mutableState.value.copy(
                    available = true,
                    connected = true,
                    powered = true,
                    lastError = null,
                )
                tuneInternal(r, frequencyMhz)
                refresh()
            } catch (t: Throwable) {
                fail("Falha ao ligar pelo shared library Qualcomm", unwrap(t))
            }
        }
    }

    override fun tune(frequencyMhz: Float) {
        scope.launch {
            try {
                val r = receiver ?: return@launch fail("Tuner Qualcomm ainda não foi inicializado.")
                if (invokeInt(r, "getFMState") != 1) {
                    return@launch fail("Tuner Qualcomm não está no estado FM_RX.")
                }
                tuneInternal(r, frequencyMhz)
                refresh()
            } catch (t: Throwable) {
                fail("Falha de sintonia Qualcomm", unwrap(t))
            }
        }
    }

    override fun seek(up: Boolean) {
        scope.launch {
            try {
                val r = receiver ?: return@launch fail("Tuner Qualcomm ainda não foi inicializado.")
                val result = seekInternal(r, up, 12_000)
                if (result == null) {
                    fail("Seek Qualcomm não concluiu em tempo.")
                } else {
                    mutableState.value = mutableState.value.copy(
                        frequencyMhz = result,
                        lastError = null,
                    )
                    DebugLog.i("QCOM", "Seek ${if (up) "↑" else "↓"} -> ${"%.1f".format(result)} MHz")
                    refresh()
                }
            } catch (t: Throwable) {
                fail("Falha no seek Qualcomm", unwrap(t))
            }
        }
    }

    override fun scan() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            val r = receiver ?: return@launch fail("Ligue o tuner Qualcomm antes do scan.")
            mutableState.value = mutableState.value.copy(scanning = true, lastError = null)
            val found = mutableListOf<Float>()
            val start = runCatching {
                invokeInt(r, "getTunedFrequency").takeIf { it > 0 }?.div(1000f)
            }.getOrNull() ?: 87.5f

            try {
                repeat(24) {
                    val next = seekInternal(r, up = true, timeoutMs = 8_000) ?: return@repeat
                    val rounded = round(next * 10f) / 10f
                    if (found.none { abs(it - rounded) < 0.05f }) {
                        found += rounded
                        DebugLog.i("SCAN", "Qualcomm encontrou ${"%.1f".format(rounded)} MHz")
                    }
                    if (found.size > 1 && abs(rounded - start) < 0.08f) {
                        return@repeat
                    }
                    delay(80)
                }

                val stations = found.distinct().sorted()
                mutableState.value = mutableState.value.copy(
                    scanning = false,
                    stations = stations,
                    lastError = if (stations.isEmpty()) {
                        "Scan Qualcomm terminou sem estações."
                    } else null,
                )
                DebugLog.i("SCAN", "Scan Qualcomm terminou com ${stations.size} estação(ões)")
                refresh()
            } catch (_: CancellationException) {
                DebugLog.i("SCAN", "Scan Qualcomm cancelado")
            } catch (t: Throwable) {
                fail("Falha no scan Qualcomm", unwrap(t))
            } finally {
                mutableState.value = mutableState.value.copy(scanning = false)
            }
        }
    }

    override fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        scope.launch {
            val r = receiver
            if (r != null) {
                runCatching {
                    receiverClass?.getMethod("cancelSearch")?.invoke(r)
                }.onFailure {
                    DebugLog.w("SCAN", "cancelSearch Qualcomm falhou: ${errorText(it)}")
                }
            }
            mutableState.value = mutableState.value.copy(scanning = false)
        }
    }

    override fun setSpeaker(enabled: Boolean) {
        fail(
            "O shared library Qualcomm controla o tuner, mas o roteamento de áudio FM " +
                "continua sendo uma camada separada do Audio HAL."
        )
    }

    private fun ensureReceiver(): Any {
        receiver?.let { return it }

        if (receiverClass == null || callbackClass == null || transceiverClass == null || configClass == null) {
            val probe = probe()
            if (probe.health != BackendHealth.AVAILABLE) {
                throw IllegalStateException(probe.summary)
            }
        }

        runCatching {
            System.loadLibrary("qcomfm_jni")
            DebugLog.i("QCOM", "libqcomfm_jni carregada pelo namespace do app")
        }.onFailure {
            DebugLog.w(
                "QCOM",
                "System.loadLibrary(qcomfm_jni) não carregou explicitamente; tentando shared library mesmo assim: ${errorText(it)}"
            )
        }

        val cbClass = requireNotNull(callbackClass)
        val recvClass = requireNotNull(receiverClass)
        val callback = cbClass.getConstructor().newInstance()
        val instance = recvClass
            .getConstructor(String::class.java, cbClass)
            .newInstance("/dev/radio0", callback)

        receiver = instance
        DebugLog.i("QCOM", "FmReceiver criado dentro do processo do RadioFMX")
        return instance
    }

    private fun createConfig(): Any {
        val cls = requireNotNull(configClass)
        val cfg = cls.getConstructor().newInstance()

        cls.getMethod("setRadioBand", Int::class.javaPrimitiveType).invoke(cfg, 4)
        cls.getMethod("setEmphasis", Int::class.javaPrimitiveType).invoke(cfg, 1)
        cls.getMethod("setChSpacing", Int::class.javaPrimitiveType).invoke(cfg, 1)
        cls.getMethod("setRdsStd", Int::class.javaPrimitiveType).invoke(cfg, 1)
        cls.getMethod("setLowerLimit", Int::class.javaPrimitiveType).invoke(cfg, 87_500)
        cls.getMethod("setUpperLimit", Int::class.javaPrimitiveType).invoke(cfg, 108_000)

        return cfg
    }

    private suspend fun tuneInternal(r: Any, frequencyMhz: Float) {
        val khz = (frequencyMhz * 1000f).toInt()
        val ok = (transceiverClass
            ?.getMethod("setStation", Int::class.javaPrimitiveType)
            ?.invoke(r, khz) as? Boolean) ?: false
        DebugLog.i("QCOM", "setStation($khz) retornou $ok")
        if (!ok) throw IllegalStateException("setStation rejeitou $khz kHz")
        delay(250)
        mutableState.value = mutableState.value.copy(
            powered = true,
            frequencyMhz = frequencyMhz,
            lastError = null,
        )
    }

    private suspend fun seekInternal(r: Any, up: Boolean, timeoutMs: Long): Float? {
        val direction = if (up) 1 else 0
        val started = (receiverClass
            ?.getMethod(
                "searchStations",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            ?.invoke(r, 0, 0, direction) as? Boolean) ?: false

        if (!started) return null

        var waited = 0L
        while (waited < timeoutMs) {
            delay(120)
            waited += 120
            val fmState = invokeInt(r, "getFMState")
            if (fmState != 3) {
                val khz = invokeInt(r, "getTunedFrequency")
                return khz.takeIf { it in 76_000..108_000 }?.div(1000f)
            }
        }
        return null
    }

    private suspend fun waitForState(r: Any, target: Int, timeoutMs: Long): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (invokeInt(r, "getFMState") == target) return true
            delay(100)
            waited += 100
        }
        return false
    }

    private fun invokeInt(target: Any, method: String): Int =
        (receiverClass?.getMethod(method)?.invoke(target) as Number).toInt()

    private fun readRdsText(receiver: Any, dataMethod: String, textMethod: String): String {
        return runCatching {
            val data = receiverClass?.getMethod(dataMethod)?.invoke(receiver) ?: return@runCatching ""
            data.javaClass.getMethod(textMethod).invoke(data)?.toString().orEmpty()
        }.getOrDefault("")
    }

    private fun fail(message: String, error: Throwable? = null) {
        if (error == null) DebugLog.e("QCOM", message) else DebugLog.e("QCOM", message, error)
        mutableState.value = mutableState.value.copy(
            lastError = if (error == null) message else "$message: ${errorText(error)}",
            scanning = false,
        )
    }

    private fun unwrap(t: Throwable): Throwable =
        if (t is InvocationTargetException && t.targetException != null) t.targetException else t

    private fun errorText(t: Throwable): String {
        val e = unwrap(t)
        return "${e.javaClass.simpleName}: ${e.message ?: "sem mensagem"}"
    }

    private fun baseState() = RadioState(
        backendId = id,
        backendName = name,
        backendKind = kind,
        capabilities = capabilities,
    )
}
