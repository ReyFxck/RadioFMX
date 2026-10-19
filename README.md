# RadioFMX

RadioFMX is an offline-first Android radio experiment built with Kotlin, Jetpack Compose, Media3 and Material 3 Expressive.

## v0.2.0

- Android 17 / API 37 target
- Orange-first Material 3 Expressive visual identity
- Receiver power button and powered/off visual states
- Animated ON AIR / scanning signal bars
- 87.5–108.0 MHz tuning in 0.1 MHz steps
- Band scan animation and saved channel presets
- System media volume slider plus volume up/down controls
- Live audio-route indicator for wired headphones, USB, Bluetooth and speaker
- Media3 local audio playback with media audio attributes, audio focus handling and noisy-route handling
- Best-effort handoff of the selected frequency to an OEM FM Radio app when available
- No INTERNET permission

## FM hardware limitation

Android does not expose one universal public FM tuner API to ordinary third-party phone apps. RadioFMX can implement the tuner UI, band logic, presets and OEM handoff, but automatic signal detection and direct chip control require a vendor-specific tuner backend. If the device exposes no compatible FM app/API, the physical tuner cannot be controlled from a normal app.

Online radio remains intentionally optional and is not enabled in v0.2.0.
