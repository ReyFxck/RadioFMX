package com.reyfxck.radiofmx.fm.nativebridge

import android.os.IBinder
import com.reyfxck.radiofmx.debug.DebugLog

data class NativeProbeResult(
    val status: String,
    val path: String? = null,
    val errno: Int? = null,
    val detail: String = "",
) {
    val available: Boolean get() = status == "OK"
    val blocked: Boolean get() = status == "DENIED"
}

object NativeFmBridge {
    private var loadFailure: Throwable? = null

    val loaded: Boolean = try {
        System.loadLibrary("radiofmx_native")
        true
    } catch (t: Throwable) {
        loadFailure = t
        DebugLog.e("NATIVE", "Falha ao carregar libradiofmx_native", t)
        false
    }

    fun loadError(): String? = loadFailure?.let {
        "${it.javaClass.simpleName}: ${it.message ?: "falha ao carregar JNI"}"
    }

    fun probe(): NativeProbeResult {
        if (!loaded) {
            return NativeProbeResult(
                status = "ERROR",
                detail = loadError() ?: "JNI nativo indisponível",
            )
        }
        return parseProbe(nativeProbe())
    }

    private fun parseProbe(raw: String): NativeProbeResult {
        val parts = raw.split('|', limit = 4)
        val status = parts.getOrNull(0).orEmpty().ifBlank { "ERROR" }
        val path = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        val errno = parts.getOrNull(2)?.toIntOrNull()
        val detail = parts.getOrNull(3).orEmpty()
        return NativeProbeResult(status, path, errno, detail)
    }

    external fun nativeProbe(): String
    external fun nativeQcomAidlProbe(): String
    external fun nativeQcomHidlProbe(): String
    external fun nativeQcomAidlBinder(): IBinder?
    external fun nativeMarkVintfStability(binder: IBinder): Boolean
    external fun nativeOpen(path: String): Long
    external fun nativeClose(handle: Long)
    external fun nativeTune(handle: Long, frequencyMhz: Float): Boolean
    external fun nativeGetFrequency(handle: Long): Float
    external fun nativeSeek(handle: Long, up: Boolean): Float
    external fun nativeGetSignal(handle: Long): Int
}
