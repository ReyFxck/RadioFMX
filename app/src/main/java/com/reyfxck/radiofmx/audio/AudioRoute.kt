package com.reyfxck.radiofmx.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

data class AudioRoute(
    val label: String,
    val headphones: Boolean,
)

fun detectAudioRoute(audioManager: AudioManager): AudioRoute {
    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

    val wired = devices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
    }
    if (wired != null) return AudioRoute("Fone com fio", true)

    val usb = devices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
    }
    if (usb != null) return AudioRoute("Áudio USB", true)

    val bluetooth = devices.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
    }
    if (bluetooth != null) return AudioRoute("Bluetooth", true)

    return AudioRoute("Alto-falante", false)
}
