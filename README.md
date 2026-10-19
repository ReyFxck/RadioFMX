# RadioFMX

RadioFMX is an offline-first Android radio experiment built with Kotlin, Jetpack Compose and Material 3 Expressive.

## First milestone

- Android 17 / API 37 target
- Material 3 Expressive UI
- No INTERNET permission
- Offline local-audio playback
- FM-style tuning UI and saved presets
- Best-effort bridge to an OEM FM Radio app when the device exposes one
- GitHub Actions debug APK builds

> Android does not expose a universal public FM tuner API to ordinary third-party phone apps. Real over-the-air FM tuning therefore depends on the device vendor, its FM hardware, and any vendor app/API exposed on that device.

Online radio is intentionally out of scope for the first milestone and can be added later as an optional module.
