package com.reyfxck.radiofmx.fm.core

import android.content.Context
import com.reyfxck.radiofmx.debug.DebugLog
import com.reyfxck.radiofmx.fm.CafBinderBackend
import com.reyfxck.radiofmx.fm.backend.NativeV4l2Backend
import com.reyfxck.radiofmx.fm.backend.QcomSharedLibraryBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RadioEngine(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val environment: RadioEnvironment = RadioEnvironmentDetector.detect(appContext)

    private val backends: List<FmBackend> = listOf(
        QcomSharedLibraryBackend(appContext),
        NativeV4l2Backend(),
        CafBinderBackend(appContext),
    ).sortedByDescending { it.priority }

    private val mutableState = MutableStateFlow(RadioState())
    val state: StateFlow<RadioState> = mutableState.asStateFlow()

    private val mutableProbes = MutableStateFlow<List<BackendProbe>>(emptyList())
    val probes: StateFlow<List<BackendProbe>> = mutableProbes.asStateFlow()

    @Volatile private var activeBackend: FmBackend? = null
    private var stateCollector: Job? = null

    fun connect() {
        scope.launch {
            logEnvironment()
            selectBestBackend()
        }
    }

    fun disconnect() {
        stateCollector?.cancel()
        stateCollector = null
        activeBackend?.disconnect()
        activeBackend = null
        mutableState.value = RadioState()
    }

    fun reprobeAndConnect() {
        scope.launch {
            selectBestBackend(force = true)
        }
    }

    fun selectBackend(id: String) {
        scope.launch {
            val backend = backends.firstOrNull { it.id == id } ?: return@launch
            val probe = backend.probe()
            refreshProbe(probe)
            if (probe.health != BackendHealth.AVAILABLE) {
                mutableState.value = mutableState.value.copy(
                    lastError = "Backend ${probe.name} não está disponível: ${probe.summary}"
                )
                return@launch
            }
            activate(backend)
        }
    }

    fun refresh() = activeBackend?.refresh()

    fun setPower(on: Boolean, frequencyMhz: Float) {
        val selected = activeBackend
        if (selected == null) {
            mutableState.value = mutableState.value.copy(
                lastError = "Nenhum backend FM funcional foi selecionado."
            )
            return
        }

        selected.setPower(on, frequencyMhz)

        if (on && selected.kind != BackendKind.CAF_BINDER) {
            scope.launch {
                repeat(24) {
                    delay(500)
                    if (activeBackend?.id != selected.id) return@launch
                    val current = selected.state.value
                    if (current.powered) return@launch
                    if (current.lastError != null) {
                        DebugLog.w(
                            "ENGINE",
                            "${selected.name} falhou ao ligar; tentando próximo backend automaticamente."
                        )
                        val fallback = chooseAvailableBackend(
                            excluding = setOf(selected.id),
                            allowExternalBootstrap = false,
                        )
                        if (fallback != null) {
                            activate(fallback)
                            delay(350)
                            fallback.setPower(true, frequencyMhz)
                        } else {
                            DebugLog.w(
                                "ENGINE",
                                "Nenhum backend interno restante consegue ligar o tuner sem bootstrap externo."
                            )
                        }
                        return@launch
                    }
                }
            }
        }
    }

    fun tune(frequencyMhz: Float) = activeBackend?.tune(frequencyMhz)
    fun seek(up: Boolean) = activeBackend?.seek(up)
    fun scan() = activeBackend?.scan()
    fun cancelScan() = activeBackend?.cancelScan()
    fun setSpeaker(enabled: Boolean) = activeBackend?.setSpeaker(enabled)

    private suspend fun selectBestBackend(force: Boolean = false) {
        if (!force && activeBackend != null) return

        val fresh = backends.map { backend ->
            runCatching { backend.probe() }.getOrElse { error ->
                BackendProbe(
                    id = backend.id,
                    name = backend.name,
                    kind = backend.kind,
                    priority = backend.priority,
                    health = BackendHealth.ERROR,
                    summary = "Probe falhou: ${error.javaClass.simpleName}",
                    details = listOf(error.message ?: "sem mensagem"),
                )
            }
        }.sortedByDescending { it.priority }

        mutableProbes.value = fresh
        fresh.forEach {
            DebugLog.i(
                "DETECT",
                "${it.name}: ${it.health} - ${it.summary} ${it.details.joinToString(prefix = "[", postfix = "]")}"
            )
        }

        val selected = chooseAvailableBackend()
        if (selected == null) {
            activeBackend?.disconnect()
            activeBackend = null
            stateCollector?.cancel()
            mutableState.value = RadioState(
                lastError = buildString {
                    append("Nenhum caminho de acesso FM disponível neste momento.")
                    if (fresh.any { it.health == BackendHealth.BLOCKED }) {
                        append(" O hardware foi detectado, mas o Android/SELinux bloqueou acesso direto.")
                    }
                }
            )
            return
        }

        activate(selected)
    }

    private fun chooseAvailableBackend(
        excluding: Set<String> = emptySet(),
        allowExternalBootstrap: Boolean = true,
    ): FmBackend? {
        val probeMap = mutableProbes.value.associateBy { it.id }
        return backends
            .filterNot { it.id in excluding }
            .filter { allowExternalBootstrap || !it.capabilities.requiresOemBootstrap }
            .firstOrNull { probeMap[it.id]?.health == BackendHealth.AVAILABLE }
    }

    private fun activate(backend: FmBackend) {
        if (activeBackend?.id == backend.id) {
            backend.connect()
            return
        }

        stateCollector?.cancel()
        activeBackend?.disconnect()
        activeBackend = backend

        DebugLog.i(
            "ENGINE",
            "Backend selecionado: ${backend.name} (${backend.kind}), prioridade=${backend.priority}"
        )

        mutableState.value = backend.state.value
        stateCollector = scope.launch {
            backend.state.collect { latest ->
                if (activeBackend?.id == backend.id) {
                    mutableState.value = latest
                }
            }
        }
        backend.connect()
    }

    private fun refreshProbe(newProbe: BackendProbe) {
        val current = mutableProbes.value.toMutableList()
        val index = current.indexOfFirst { it.id == newProbe.id }
        if (index >= 0) current[index] = newProbe else current += newProbe
        mutableProbes.value = current.sortedByDescending { it.priority }
    }

    private fun logEnvironment() {
        DebugLog.i(
            "DETECT",
            "Device=${environment.manufacturer} ${environment.model}; " +
                "hardware=${environment.hardware}; board=${environment.board}; " +
                "SoC=${environment.socManufacturer} ${environment.socModel}"
        )
        DebugLog.i(
            "DETECT",
            "Nós FM visíveis=${environment.radioNodes.ifEmpty { listOf("nenhum") }}; " +
                "apps FM=${environment.knownFmPackages.ifEmpty { listOf("nenhum") }}"
        )
    }
}
