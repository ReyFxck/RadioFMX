package com.reyfxck.radiofmx.fm

import android.content.Context
import android.content.Intent
import kotlin.math.roundToInt

object FmRadioBridge {
    private val knownPackages = listOf(
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

    fun findAvailablePackage(context: Context): String? =
        knownPackages.firstOrNull { packageName ->
            context.packageManager.getLaunchIntentForPackage(packageName) != null
        }

    fun launch(context: Context, frequencyMhz: Float? = null): Boolean {
        val packageName = findAvailablePackage(context) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false

        if (frequencyMhz != null) {
            intent.putExtra("frequency", frequencyMhz)
            intent.putExtra("frequency_mhz", frequencyMhz)
            intent.putExtra("freq", (frequencyMhz * 1000f).roundToInt())
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}
