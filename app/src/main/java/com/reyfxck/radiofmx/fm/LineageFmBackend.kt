package com.reyfxck.radiofmx.fm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.caf.fmradio.IFMRadioService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class LineageFmState(
    val available: Boolean = false,
    val connected: Boolean = false,
    val powered: Boolean = false,
    val speakerEnabled: Boolean = false,
    val antennaAvailable: Boolean = false,
    val wiredHeadsetAvailable: Boolean = false,
    val scanning: Boolean = false,
    val rssi: Int? = null,
    val programService: String = "",
    val radioText: String = "",
    val stations: List<Float> = emptyList(),
    val lastError: String? = null,
)

class LineageFmBackend(context: Context) {
    companion object {
        const val PACKAGE = "com.caf.fmradio"
        const val SERVICE = "com.caf.fmradio.FMRadioService"
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(LineageFmState())
    val state: StateFlow<LineageFmState> = _state.asStateFlow()

    @Volatile
    private var radio: IFMRadioService? = null
    private var bound = false
    private var scanJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            radio = IFMRadioService.Stub.asInterface(binder)
            _state.value = _state.value.copy(
                available = true,
                connected = true,
                lastError = null,
            )
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            radio = null
            bound = false
            _state.value = _state.value.copy(
                connected = false,
                powered = false,
                scanning = false,
                lastError = "Serviço FM da Lineage/CAF desconectado.",
            )
        }

        override fun onBindingDied(name: ComponentName) {
            onServiceDisconnected(name)
        }

        override fun onNullBinding(name: ComponentName) {
            radio = null
            bound = false
            _state.value = _state.value.copy(
                available = true,
                connected = false,
                lastError = "O pacote FM existe, mas não aceitou conexão Binder.",
            )
        }
    }

    fun connect() {
        val installed = isInstalled()
        _state.value = _state.value.copy(available = installed)
        if (!installed || bound) return

        try {
            val intent = Intent().setClassName(PACKAGE, SERVICE)
            bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                _state.value = _state.value.copy(
                    lastError = "Não foi possível vincular ao serviço $SERVICE."
                )
            }
        } catch (t: Throwable) {
            bound = false
            _state.value = _state.value.copy(
                lastError = "${t.javaClass.simpleName}: ${t.message ?: "falha ao conectar"}"
            )
        }
    }

    fun disconnect() {
        scanJob?.cancel()
        if (bound) {
            runCatching { appContext.unbindService(connection) }
        }
        bound = false
        radio = null
    }

    fun refresh() {
        scope.launch {
            val svc = radio ?: return@launch
            try {
                val on = svc.isFmOn
                _state.value = _state.value.copy(
                    connected = true,
                    powered = on,
                    speakerEnabled = runCatching { svc.isSpeakerEnabled }.getOrDefault(false),
                    antennaAvailable = runCatching { svc.isAntennaAvailable }.getOrDefault(false),
                    wiredHeadsetAvailable = runCatching { svc.isWiredHeadsetAvailable }.getOrDefault(false),
                    scanning = runCatching { svc.isSearchInProgress }.getOrDefault(false),
                    rssi = if (on) runCatching { svc.rssi }.getOrNull() else null,
                    programService = if (on) runCatching { svc.programService.orEmpty() }.getOrDefault("") else "",
                    radioText = if (on) runCatching { svc.radioText.orEmpty() }.getOrDefault("") else "",
                    lastError = null,
                )
            } catch (t: Throwable) {
                setError(t)
            }
        }
    }

    fun setPower(on: Boolean, frequencyMhz: Float) {
        scope.launch {
            val svc = radio ?: return@launch setError("Backend Lineage/CAF ainda não conectado.")
            try {
                val ok = if (on) svc.fmOn() else svc.fmOff()
                if (ok && on) {
                    delay(120)
                    svc.tune((frequencyMhz * 1000f).roundToInt())
                }
                delay(100)
                refresh()
                if (!ok) setError("O serviço FM recusou ${if (on) "ligar" else "desligar"} o tuner.")
            } catch (t: Throwable) {
                setError(t)
            }
        }
    }

    fun tune(frequencyMhz: Float) {
        scope.launch {
            val svc = radio ?: return@launch setError("Backend Lineage/CAF ainda não conectado.")
            try {
                if (!svc.isFmOn) {
                    if (!svc.fmOn()) {
                        return@launch setError("Não foi possível ligar o tuner FM.")
                    }
                    delay(120)
                }
                val ok = svc.tune((frequencyMhz * 1000f).roundToInt())
                delay(100)
                refresh()
                if (!ok) setError("O tuner rejeitou ${"%.1f".format(frequencyMhz)} MHz.")
            } catch (t: Throwable) {
                setError(t)
            }
        }
    }

    fun seek(up: Boolean) {
        scope.launch {
            val svc = radio ?: return@launch setError("Backend Lineage/CAF ainda não conectado.")
            try {
                if (!svc.isFmOn && !svc.fmOn()) {
                    return@launch setError("Não foi possível ligar o tuner FM.")
                }
                val ok = svc.seek(up)
                if (!ok) setError("Seek FM não pôde ser iniciado.")
                delay(1400)
                refresh()
            } catch (t: Throwable) {
                setError(t)
            }
        }
    }

    fun setSpeaker(enabled: Boolean) {
        scope.launch {
            val svc = radio ?: return@launch setError("Controle de alto-falante exige o backend Lineage/CAF.")
            try {
                svc.enableSpeaker(enabled)
                delay(150)
                refresh()
            } catch (t: Throwable) {
                setError(t)
            }
        }
    }

    fun scan() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            val svc = radio ?: return@launch setError("Scan físico exige o backend Lineage/CAF.")
            try {
                if (!svc.isFmOn && !svc.fmOn()) {
                    return@launch setError("Não foi possível ligar o tuner para fazer o scan.")
                }

                runCatching {
                    if (svc.isSearchInProgress) svc.cancelSearch()
                }

                val started = svc.searchStrongStationList(12)
                if (!started) {
                    return@launch setError("O tuner não iniciou a busca de estações.")
                }

                _state.value = _state.value.copy(scanning = true, lastError = null)

                var checks = 0
                do {
                    delay(400)
                    checks++
                } while (runCatching { svc.isSearchInProgress }.getOrDefault(false) && checks < 100)

                val raw = runCatching { svc.scannedFrequencies }.getOrNull().orEmpty()
                val stations = raw.mapNotNull { value ->
                    (value as? Number)?.toInt()?.takeIf { it in 87500..108000 }
                }.map { it / 1000f }
                    .distinct()
                    .sorted()

                _state.value = _state.value.copy(
                    scanning = false,
                    stations = stations,
                    lastError = if (stations.isEmpty()) {
                        "Scan terminou, mas o serviço não retornou estações."
                    } else null,
                )
                refresh()
            } catch (t: Throwable) {
                _state.value = _state.value.copy(scanning = false)
                setError(t)
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        scope.launch {
            runCatching { radio?.cancelSearch() }
            _state.value = _state.value.copy(scanning = false)
            refresh()
        }
    }

    private fun isInstalled(): Boolean = try {
        appContext.packageManager.getApplicationInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun setError(message: String) {
        _state.value = _state.value.copy(lastError = message, scanning = false)
    }

    private fun setError(t: Throwable) {
        setError("${t.javaClass.simpleName}: ${t.message ?: "erro FM"}")
    }
}
