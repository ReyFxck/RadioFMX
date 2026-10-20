package com.reyfxck.radiofmx.fm.core

import android.content.Context
import android.os.Build
import java.io.File

data class RadioEnvironment(
    val manufacturer: String,
    val model: String,
    val hardware: String,
    val board: String,
    val socManufacturer: String,
    val socModel: String,
    val radioNodes: List<String>,
    val knownFmPackages: List<String>,
)

object RadioEnvironmentDetector {
    private val fmNodes = listOf(
        "/dev/radio0",
        "/dev/radio1",
        "/dev/fm",
        "/dev/fmradio",
        "/dev/radio",
    )

    private val fmPackages = listOf(
        "com.caf.fmradio",
        "com.android.fmradio",
        "com.mediatek.fmradio",
        "com.sec.android.app.fm",
        "com.samsung.android.app.fmradio",
        "com.miui.fm",
        "com.huawei.android.FMRadio",
        "com.motorola.fmplayer",
        "com.oneplus.fmradio",
    )

    fun detect(context: Context): RadioEnvironment {
        val pm = context.packageManager
        return RadioEnvironment(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            hardware = Build.HARDWARE,
            board = Build.BOARD,
            socManufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else "",
            socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "",
            radioNodes = fmNodes.filter { File(it).exists() },
            knownFmPackages = fmPackages.filter { packageName ->
                runCatching { pm.getApplicationInfo(packageName, 0) }.isSuccess
            },
        )
    }
}
