package com.reyfxck.radiofmx.fm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.caf.fmradio.IFMRadioService
import com.caf.fmradio.IFMRadioServiceCallbacks
import com.reyfxck.radiofmx.debug.DebugLog
import com.reyfxck.radiofmx.fm.core.BackendHealth
import com.reyfxck.radiofmx.fm.core.BackendKind
import com.reyfxck.radiofmx.fm.core.BackendProbe
import com.reyfxck.radiofmx.fm.core.FmBackend
import com.reyfxck.radiofmx.fm.core.RadioCapabilities
import com.reyfxck.radiofmx.fm.core.RadioState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

class CafBinderBackend(context: Context) : FmBackend {
    companion object {
        const val PACKAGE = "com.caf.fmradio"
        const val SERVICE = "com.caf.fmradio.FMRadioService"
    }

    override val id = "caf-binder"
    override val name = "Qualcomm CAF Binder"
    override val kind = BackendKind.CAF_BINDER
    override val priority = 70
    override val capabilities = RadioCapabilities(
        power = true,
        tune = true,
        seek = true,
        scan = true,
        signalStrength = true,
        rds = true,
        speakerRoute = true,
        requiresOemBootstrap = true,
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(baseState())
    override val state: StateFlow<RadioState> = _state.asStateFlow()

    @Volatile
    private var radio: IFMRadioService? = null
    private var bound = false
    private var scanJob: Job? = null

    @Volatile
    private var scanCompletion: CompletableDeferred<Unit>? = null

    private val callbacks = object : IFMRadioServiceCallbacks.Stub() {
        override fun onEnabled() = callbackEvent("onEnabled", refreshState = true)
        override fun onDisabled() = callbackEvent("onDisabled", refreshState = true)
        override fun onRadioReset() = callbackEvent("onRadioReset", refreshState = true)
        override fun onTuneStatusChanged() = callbackEvent("onTuneStatusChanged", refreshState = true)
        override fun onProgramServiceChanged() = callbackEvent("onProgramServiceChanged", refreshState = true)
        override fun onRadioTextChanged() = callbackEvent("onRadioTextChanged", refreshState = true)
        override fun onAlternateFrequencyChanged() = callbackEvent("onAlternateFrequencyChanged")
        override fun onSignalStrengthChanged() = callbackEvent("onSignalStrengthChanged", refreshState = true)
        override fun onSearchComplete() = callbackEvent("onSearchComplete", refreshState = true)
        override fun onSearchListComplete() {
            DebugLog.i("SCAN", "Callback onSearchListComplete recebido")
            scanCompletion?.complete(Unit)
        }
        override fun onMute(bMuted: Boolean) = callbackEvent("onMute=$bMuted")
        override fun onAudioUpdate(bStereo: Boolean) = callbackEvent("onAudioUpdate=$bStereo")
        override fun onStationRDSSupported(bRDSSupported: Boolean) = callbackEvent("onStationRDSSupported=$bRDSSupported")
        override fun onRecordingStopped() = callbackEvent("onRecordingStopped")
        override fun onExtenRadioTextChanged() = callbackEvent("onExtenRadioTextChanged", refreshState = true)
        override fun onRecordingStarted() = callbackEvent("onRecordingStarted")
        override fun onSeekNextStation() = callbackEvent("onSeekNextStation")
        override fun onA2DPConnectionstateChanged(state: Boolean) = callbackEvent("onA2DPConnectionstateChanged=$state")
        override fun onFmAudioPathStarted() = callbackEvent("onFmAudioPathStarted", refreshState = true)
        override fun onFmAudioPathStopped() = callbackEvent("onFmAudioPathStopped", refreshState = true)
        override fun onExtenCountryCodeChanged() = callbackEvent("onExtenCountryCodeChanged")
        override fun getSigThCb(value: Int, status: Int) = callbackEvent("getSigThCb value=$value status=$status")
        override fun getChDetThCb(value: Int, status: Int) = callbackEvent("getChDetThCb value=$value status=$status")
        override fun DefDataRdCb(value: Int, status: Int) = callbackEvent("DefDataRdCb value=$value status=$status")
        override fun getBlendCb(value: Int, status: Int) = callbackEvent("getBlendCb value=$value status=$status")
        override fun setChDetThCb(status: Int) = callbackEvent("setChDetThCb status=$status")
        override fun DefDataWrtCb(status: Int) = callbackEvent("DefDataWrtCb status=$status")
        override fun setBlendCb(status: Int) = callbackEvent("setBlendCb status=$status")
        override fun getStationParamCb(value: Int, status: Int) = callbackEvent("getStationParamCb value=$value status=$status")
        override fun getStationDbgParamCb(value: Int, status: Int) = callbackEvent("getStationDbgParamCb value=$value status=$status")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            DebugLog.i("FM", "Binder conectado ao serviço: ${name.flattenToShortString()}")
            val service = IFMRadioService.Stub.asInterface(binder)
            radio = service
            try {
                service.registerCallbacks(callbacks)
                DebugLog.i("FM", "Callbacks FM registrados")
            } catch (t: Throwable) {
                DebugLog.e("FM-CB", "Não foi possível registrar callbacks FM", t)
            }
            _state.value = _state.value.copy(
                available = true,
                connected = true,
                lastError = null,
            )
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            DebugLog.w("FM", "Binder desconectado: ${name.flattenToShortString()}")
            radio = null
            bound = false
            scanCompletion?.cancel()
            scanCompletion = null
            _state.value = _state.value.copy(
                connected = false,
                powered = false,
                scanning = false,
                lastError = "Serviço Qualcomm/CAF desconectado.",
            )
        }

        override fun onBindingDied(name: ComponentName) {
            onServiceDisconnected(name)
        }

        override fun onNullBinding(name: ComponentName) {
            DebugLog.e("FM", "O serviço recusou o binding: ${name.flattenToShortString()}")
            radio = null
            bound = false
            _state.value = _state.value.copy(
                available = true,
                connected = false,
                lastError = "O pacote FM existe, mas não aceitou conexão Binder.",
            )
        }
    }

    override fun probe(): BackendProbe {
        val installed = isInstalled()
        return BackendProbe(
            id = id,
            name = name,
            kind = kind,
            priority = priority,
            health = if (installed) BackendHealth.AVAILABLE else BackendHealth.MISSING,
            summary = if (installed) {
                "Serviço Qualcomm/CAF encontrado; funciona como fallback de compatibilidade"
            } else {
                "Pacote com.caf.fmradio não encontrado"
            },
            details = listOf(
                "package=$PACKAGE",
                "service=$SERVICE",
                "bootstrap OEM necessário para ligar em algumas ROMs",
            ),
        )
    }

    override fun connect() {
        val installed = isInstalled()
        _state.value = _state.value.copy(available = installed)
        DebugLog.i("FM", "Pacote $PACKAGE instalado=$installed; binding ativo=$bound")
        if (!installed) {
            DebugLog.w("FM", "Fallback CAF não encontrado nesta ROM")
            return
        }
        if (bound) return

        try {
            val intent = Intent().setClassName(PACKAGE, SERVICE)
            bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            DebugLog.i("FM", "bindService($SERVICE) retornou $bound")
            if (!bound) {
                _state.value = _state.value.copy(
                    lastError = "Não foi possível vincular ao serviço $SERVICE."
                )
            }
        } catch (t: Throwable) {
            bound = false
            DebugLog.e("FM", "Falha ao conectar ao serviço FM", t)
            _state.value = _state.value.copy(
                lastError = "${t.javaClass.simpleName}: ${t.message ?: "falha ao conectar"}"
            )
        }
    }

    override fun disconnect() {
        scanJob?.cancel()
        scanCompletion?.cancel()
        scanCompletion = null
        runCatching { radio?.unregisterCallbacks() }
        if (bound) {
            runCatching { appContext.unbindService(connection) }
        }
        bound = false
        radio = null
    }

    override fun refresh() {
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
                    scanning = if (on) _state.value.scanning else false,
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

    override fun setPower(on: Boolean, frequencyMhz: Float) {
        DebugLog.i("FM", "Pedido Power=$on, frequência=${"%.1f".format(frequencyMhz)} MHz")
        scope.launch {
            val svc = radio ?: return@launch setError("Serviço FM da ROM ainda não conectado.")
            try {
                if (on) {
                    if (!ensurePowered(svc, frequencyMhz)) return@launch
                    tuneOnService(svc, frequencyMhz)
                } else {
                    if (!svc.isFmOn) {
                        refresh()
                        return@launch
                    }
                    val ok = try {
                        svc.fmOff()
                    } catch (t: Throwable) {
                        DebugLog.e("FM", "fmOff remoto falhou; abrindo a interface original da ROM", t)
                        val launched = runCatching { FmRadioBridge.launch(appContext, frequencyMhz) }.getOrDefault(false)
                        return@launch setError(
                            if (launched) {
                                "A ROM recusou desligar o tuner por Binder. Desligue-o no app FM original e volte ao RadioFMX."
                            } else {
                                "Falha ao desligar o tuner: ${t.javaClass.simpleName}: ${t.message ?: "erro FM"}"
                            }
                        )
                    }
                    DebugLog.i("FM", "Comando fmOff retornou $ok")
                    if (!ok) {
                        setError("O serviço FM recusou desligar o tuner.")
                        return@launch
                    }
                    if (!waitForPowerState(svc, desired = false, timeoutMs = 5_000)) {
                        setError("fmOff foi aceito, mas o serviço ainda informa que o tuner está ligado.")
                        return@launch
                    }
                    refresh()
                }
            } catch (t: Throwable) {
                setError(t)
            }
        }
    }

    override fun tune(frequencyMhz: Float) {
        DebugLog.i("FM", "Pedido de sintonia: ${"%.1f".format(frequencyMhz)} MHz")
        scope.launch {
            val svc = radio ?: return@launch setError("Serviço FM da ROM ainda não conectado.")
            try {
                if (!svc.isFmOn) {
                    return@launch setError("O tuner está desligado. Toque em Power para inicializá-lo pelo app FM da ROM.")
                }
                tuneOnService(svc, frequencyMhz)
            } catch (t: Throwable) {
                setError(t)
            }
        }
    }

    override fun seek(up: Boolean) {
        scope.launch {
            val svc = radio ?: return@launch setError("Serviço FM da ROM ainda não conectado.")
            try {
                if (!svc.isFmOn) {
                    return@launch setError("O tuner está desligado. Toque em Power para inicializá-lo pelo app FM da ROM.")
                }
                DebugLog.i("FM", "Pedido seek up=$up")
                val ok = svc.seek(up)
                DebugLog.i("FM", "seek($up) retornou $ok")
                if (!ok) setError("Seek FM não pôde ser iniciado.")
                delay(1400)
                refresh()
            } catch (t: Throwable) {
                setError(t)
            }
        }
    }

    override fun setSpeaker(enabled: Boolean) {
        DebugLog.i("AUDIO", "Pedido de alto-falante FM=$enabled")
        scope.launch {
            val svc = radio ?: return@launch setError("Controle de alto-falante exige o backend CAF conectado.")
            try {
                svc.enableSpeaker(enabled)
                delay(150)
                refresh()
            } catch (t: Throwable) {
                setError(t)
            }
        }
    }

    override fun scan() {
        if (scanJob?.isActive == true) {
            DebugLog.w("SCAN", "Scan ignorado: já existe uma busca em andamento")
            return
        }
        DebugLog.i("SCAN", "Solicitada busca física de até 12 estações")
        scanJob = scope.launch {
            val svc = radio ?: return@launch setError("Scan físico exige o serviço FM da ROM.")
            try {
                if (!svc.isFmOn) {
                    return@launch setError("O tuner está desligado. Toque em Power antes de iniciar o scan.")
                }

                runCatching { svc.cancelSearch() }
                val completion = CompletableDeferred<Unit>()
                scanCompletion = completion

                val started = svc.searchStrongStationList(12)
                DebugLog.i("SCAN", "searchStrongStationList(12) retornou $started")
                if (!started) {
                    scanCompletion = null
                    return@launch setError("O tuner não iniciou a busca de estações.")
                }

                _state.value = _state.value.copy(scanning = true, lastError = null)
                val callbackCompleted = withTimeoutOrNull(45_000) {
                    completion.await()
                    true
                } ?: false

                // Some vendor FM services populate the search list but fail to deliver
                // onSearchListComplete. Preserve usable results if that callback is lost.
                val raw = svc.searchList ?: intArrayOf()
                val stations = raw.filter { it in 87500..108000 }
                    .map { it / 1000f }
                    .distinct()
                    .sorted()

                if (!callbackCompleted) {
                    if (stations.isEmpty()) {
                        return@launch setError(
                            "A busca foi aceita, mas após 45 s não chegou o callback nem uma lista de estações."
                        )
                    }
                    DebugLog.w(
                        "SCAN",
                        "Callback não chegou em 45 s; aproveitando ${stations.size} estação(ões) retornadas pela lista do serviço."
                    )
                }

                DebugLog.i("SCAN", "Busca concluída; callback=$callbackCompleted; estações válidas=${stations.size}: ${stations.joinToString { "%.1f".format(it) }} MHz")
                _state.value = _state.value.copy(
                    scanning = false,
                    stations = stations,
                    lastError = if (stations.isEmpty()) {
                        if (callbackCompleted) {
                            "O callback chegou, mas o serviço não retornou estações."
                        } else {
                            "O serviço não concluiu a busca nem retornou estações."
                        }
                    } else null,
                )
                refresh()
            } catch (cancelled: CancellationException) {
                DebugLog.i("SCAN", "Busca cancelada; cancelamento tratado normalmente.")
                _state.value = _state.value.copy(scanning = false)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(scanning = false)
                setError(t)
            } finally {
                scanCompletion = null
                _state.value = _state.value.copy(scanning = false)
            }
        }
    }

    override fun cancelScan() {
        DebugLog.w("SCAN", "Cancelamento de scan solicitado")
        scanCompletion?.cancel()
        scanCompletion = null
        scanJob?.cancel()
        scanJob = null
        scope.launch {
            try {
                val cancelled = radio?.cancelSearch()
                DebugLog.i("SCAN", "cancelSearch retornou $cancelled")
            } catch (t: Throwable) {
                DebugLog.e("SCAN", "Falha ao cancelar busca", t)
            }
            _state.value = _state.value.copy(scanning = false)
            refresh()
        }
    }

    private suspend fun ensurePowered(svc: IFMRadioService, frequencyMhz: Float): Boolean {
        if (svc.isFmOn) return true

        DebugLog.w(
            "FM",
            "Não chamarei fmOn() por Binder remoto: esta implementação da ROM exige inicialização pela própria interface FM."
        )
        val launched = try {
            FmRadioBridge.launch(appContext, frequencyMhz)
        } catch (t: Throwable) {
            DebugLog.e("FM", "Falha ao abrir o app FM original", t)
            false
        }
        if (!launched) {
            setError("O tuner está desligado e não foi possível abrir o app FM original da ROM.")
            return false
        }

        DebugLog.i(
            "FM",
            "App FM original aberto. Aguardando até 30 s para o usuário/ROM ligar o tuner pela interface nativa."
        )
        if (!waitForPowerState(svc, desired = true, timeoutMs = 30_000)) {
            setError(
                "O app FM da ROM foi aberto, mas o tuner continua desligado. Ligue o rádio no app original e volte ao RadioFMX."
            )
            return false
        }
        DebugLog.i("FM", "Tuner físico confirmado ligado; retomando o comando no RadioFMX")
        return true
    }

    private suspend fun waitForPowerState(
        svc: IFMRadioService,
        desired: Boolean,
        timeoutMs: Long,
    ): Boolean {
        repeat((timeoutMs / 250L).toInt()) {
            delay(250)
            val current = runCatching { svc.isFmOn }.getOrNull()
            if (current == desired) return true
        }
        return false
    }

    private suspend fun tuneOnService(svc: IFMRadioService, frequencyMhz: Float) {
        val khz = (frequencyMhz * 1000f).roundToInt()
        DebugLog.i("FM", "Enviando tune($khz) após confirmar tuner ligado")
        val ok = svc.tune(khz)
        DebugLog.i("FM", "tune($khz) retornou $ok")
        delay(150)
        refresh()
        if (!ok) setError("O tuner rejeitou ${"%.1f".format(frequencyMhz)} MHz.")
    }

    private fun callbackEvent(name: String, refreshState: Boolean = false) {
        DebugLog.d("FM-CB", name)
        if (refreshState) refresh()
    }

    private fun isInstalled(): Boolean = try {
        appContext.packageManager.getApplicationInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun setError(message: String) {
        DebugLog.e("FM", message)
        _state.value = _state.value.copy(lastError = message, scanning = false)
    }

    private fun setError(t: Throwable) {
        DebugLog.e("FM", "Exceção no backend FM", t)
        _state.value = _state.value.copy(
            lastError = "${t.javaClass.simpleName}: ${t.message ?: "erro FM"}",
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
