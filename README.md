# RadioFMX

RadioFMX is an offline-first FM radio app for Android. The goal is a single app that owns its UI and radio behavior while selecting the best hardware access path available on each device.

## Architecture

RadioFMX no longer treats a ROM-specific radio app as the product. The app has a RadioEngine with pluggable FM backends:

- **Native V4L2** — first choice. Probes common /dev/radio* nodes and talks to a Linux V4L2 tuner through RadioFMX's own JNI bridge.
- **Qualcomm CAF Binder** — compatibility fallback for ROMs/devices exposing com.caf.fmradio.FMRadioService.
- Future backends can be added for MediaTek, Samsung/vendor HALs, privileged/system builds, or other hardware without changing the Compose UI.

The engine probes all supported backends, ranks them, selects the best available one, and can automatically fall back when direct V4L2 access opens but cannot initialize the vendor driver.

## v0.8.1

- Adds a Qualcomm HIDL availability/security probe when AIDL is unavailable.
- Keeps HIDL diagnostic-only until the device proves an untrusted APK may access that HAL.
- Avoids unsafe global media-key tricks and still never launches another radio app from Power.

## v0.8.0

- Direct Qualcomm stable FM HAL (AIDL) probe/client
- Raw Qualcomm FM HCI power/tune/seek/scan commands when the AIDL HAL is exposed
- VINTF-stable callback Binder created by RadioFMX
- qcom.fmradio Java backend is now marked BLOCKED when libqcomfm_jni cannot actually load
- No automatic launch of OEM/Lineage FM applications
- Native V4L2 and CAF compatibility backends remain available

## v0.6.0

- Android 17 / API 37 target
- Kotlin + Jetpack Compose + Material 3 Expressive
- Orange-first UI
- Unified RadioEngine
- Backend registry and capability probing
- Native V4L2 JNI bridge
- Direct tune, seek, signal-strength query, and scan primitives for accessible V4L2 tuners
- Qualcomm CAF Binder fallback retained for compatibility
- Automatic backend selection and direct-to-CAF fallback
- In-app diagnostic terminal
- Backend detection report in Maintenance
- Frequency presets, volume controls, audio-route detection, speaker control when supported
- Local Media3 audio player
- No INTERNET permission

## Compatibility reality

Android has no universal public FM radio API. A normal APK can only control hardware that the device exposes to it. Some phones expose a V4L2 tuner, some expose vendor services, some require privileged permissions, and some contain no usable FM receiver at all.

RadioFMX therefore uses a multi-backend architecture. The same APK can support many ROMs/devices, but hardware or SELinux restrictions can still make FM unavailable on a specific phone.

The app never pretends a scan succeeded when there is no hardware path.

## Licensing note

The native V4L2 backend in RadioFMX is an independent implementation using Linux V4L2 interfaces. External FM projects are useful engineering references, but RadioFMX does not copy GPL code into this implementation.
