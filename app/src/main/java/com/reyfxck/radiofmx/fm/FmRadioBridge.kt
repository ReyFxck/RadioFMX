package com.reyfxck.radiofmx.fm

import android.content.Context
import android.content.Intent

/**
 * Best-effort bridge to an OEM FM application.
 *
 * Android phones do not expose one public, universal API that ordinary third-party apps can use
 * to control an integrated FM tuner. Vendors that ship FM support normally keep it behind their
 * own application/system integration. RadioFMX therefore detects a few common vendor package
 * names and launches the vendor app when one is available.
 */
object FmRadioBridge {
    private val knownPackages = listOf(
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

    fun launch(context: Context): Boolean {
        val packageName = findAvailablePackage(context) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}
