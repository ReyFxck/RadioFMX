package com.reyfxck.radiofmx.fm.core

import kotlinx.coroutines.flow.StateFlow

interface FmBackend {
    val id: String
    val name: String
    val kind: BackendKind
    val priority: Int
    val capabilities: RadioCapabilities
    val state: StateFlow<RadioState>

    fun probe(): BackendProbe
    fun connect()
    fun disconnect()
    fun refresh()
    fun setPower(on: Boolean, frequencyMhz: Float)
    fun tune(frequencyMhz: Float)
    fun seek(up: Boolean)
    fun scan()
    fun cancelScan()
    fun setSpeaker(enabled: Boolean)
}
