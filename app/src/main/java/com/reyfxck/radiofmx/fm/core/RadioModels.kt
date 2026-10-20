package com.reyfxck.radiofmx.fm.core

enum class BackendKind {
    QCOM_AIDL_HAL,
    QCOM_SHARED_LIBRARY,
    NATIVE_V4L2,
    NONE,
}

enum class BackendHealth {
    AVAILABLE,
    FALLBACK_ONLY,
    BLOCKED,
    MISSING,
    ERROR,
}

data class RadioCapabilities(
    val power: Boolean = false,
    val tune: Boolean = false,
    val seek: Boolean = false,
    val scan: Boolean = false,
    val signalStrength: Boolean = false,
    val rds: Boolean = false,
    val speakerRoute: Boolean = false,
    val requiresOemBootstrap: Boolean = false,
)

data class BackendProbe(
    val id: String,
    val name: String,
    val kind: BackendKind,
    val priority: Int,
    val health: BackendHealth,
    val summary: String,
    val details: List<String> = emptyList(),
)

data class RadioState(
    val backendId: String = "",
    val backendName: String = "Nenhum backend ativo",
    val backendKind: BackendKind = BackendKind.NONE,
    val capabilities: RadioCapabilities = RadioCapabilities(),
    val available: Boolean = false,
    val connected: Boolean = false,
    val powered: Boolean = false,
    val speakerEnabled: Boolean = false,
    val antennaAvailable: Boolean = false,
    val wiredHeadsetAvailable: Boolean = false,
    val scanning: Boolean = false,
    val frequencyMhz: Float? = null,
    val rssi: Int? = null,
    val programService: String = "",
    val radioText: String = "",
    val stations: List<Float> = emptyList(),
    val lastError: String? = null,
)
