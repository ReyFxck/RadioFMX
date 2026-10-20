# RadioFMX

RadioFMX is an offline-first FM radio app for Android. It is intentionally **standalone**: the RadioFMX UI and engine must control the tuner through hardware/vendor interfaces available to this process. Another FM application is never used as the radio engine.

## Architecture

The engine currently contains only direct backends:

- **Qualcomm FM HAL (AIDL)** — RadioFMX talks directly to `vendor.qti.hardware.fm.IFmHci/default` and sends the Qualcomm FM HCI commands itself when Android exposes the service.
- **Qualcomm vendor framework** — direct in-process access through `qcom.fmradio` only when its JNI library is actually loadable by the RadioFMX process.
- **Native V4L2** — RadioFMX's own JNI implementation for accessible Linux `/dev/radio*` tuner nodes.

There is deliberately **no CAF/LineageOS Binder backend, no OEM-radio launcher, no package fallback, and no dependency on another FM app being open**.

## v0.9.0

- Strict standalone mode.
- Removed `com.caf.fmradio.FMRadioService` from backend selection.
- Removed the CAF/LineageOS AIDL client and callbacks from the project.
- Removed all FM-app package queries and the manual OEM-radio launcher.
- Removed automatic or manual fallback to another radio application.
- Diagnostics now report only hardware/vendor paths owned by the RadioFMX process.

## Motorola moto g34 5G / fogos test result

The current test ROM exposes no usable direct path to a normal third-party APK:

- Qualcomm FM AIDL service: not exposed.
- Qualcomm HIDL: not accessible to the app.
- `qcom.fmradio` Java classes: visible, but `libqcomfm_jni.so` is outside the app linker namespace.
- V4L2 radio node: not accessible.

That means an ordinary standalone APK cannot currently power this phone's FM receiver without additional system privileges. RadioFMX will report that limitation instead of silently borrowing the LineageOS radio service.

A future privileged/system build can add a RadioFMX-owned hardware service for ROMs that restrict FM to privileged processes.

## Compatibility reality

Android has no universal public FM-radio API. Direct operation depends on the hardware/vendor interface and the access policy exposed by the ROM. RadioFMX does not pretend that another app's service is its own hardware backend.

## Licensing note

The native V4L2 backend in RadioFMX is an independent implementation using Linux V4L2 interfaces.
