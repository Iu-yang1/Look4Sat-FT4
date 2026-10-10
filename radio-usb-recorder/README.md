# Radio USB Recorder — UAC1 raw USB audio (alpha)

Standalone Android/Kotlin radio recording project, currently staged under the Look4Sat-FT4 development branch without touching the original application or root Gradle build.

## Design

Android USB Host is used only for USB permission and device FD acquisition. A native JNI bridge opens that FD using libusb and nExtCamera/libuac, receives isochronous PCM data, and stores it using a bounded native queue/writer thread. It does NOT use Android AudioRecord, AAudio, phone microphone, AGC, NS, AEC, filtering, resampling, or AAC encoding. No microphone permission, Internet, or Root is required by the application.

Alpha supports **UAC1 mono 48 kHz 16-bit PCM input** only. Captures WAV using the system file picker and updates RIFF header on stop. WAV target must be seekable. App does not silently fall back to phone microphone if the USB input is lost.

## Building

Source dependency versions are pinned by commit and cloned via GitHub Actions:

- libusb v1.0.29 (15a7ebb4d426c5ce196684347d2b7cafad862626): LGPL-2.1-or-later
- nExtCamera/libuac (17a6ba0a09309a6890ec338826de43a256f89813): Apache-2.0

Workflow: .github/workflows/radio-usb-recorder.yml

JDK 17, Android SDK 35, Gradle 8.9, NDK 27.2.12479018, CMake 3.22.1. An Android arm64 debug APK is attached as a GitHub Actions artifact when the build passes.

For local source builds, clone both dependencies into app/src/main/cpp/vendor/libusb and app/src/main/cpp/vendor/libuac and run Gradle assembleDebug in this folder.

## Huawei HarmonyOS 4 / AB13X acceptance

1. Connect the same AB13X ADC, radio and 3.5 mm cable verified to be working on Windows.
2. Disable receiver squelch to generate continuous FM noise.
3. Grant USB access explicitly; save WAV. Verify PCM byte count increases, recent dBFS peak updates, and dropped packets stay zero.
4. Keep recording for at least 5 minutes, especially across the previously observed ~3:54 timepoint.
5. Stop; verify RIFF header, listen for gating and compare with a WAV from Windows and the original HarmonyOS voice recorder.

Do NOT claim hardware compatibility without on-device tests. libuac supports only part of UAC1; UAC2 or unusual AB13X USB descriptors may fail. Native USBFS isochronous transfer may also be blocked by OS interface ownership, without Root; the app reports errors rather than changing input sources.

Alpha is foreground-only: keep this screen open. File splitting, Android foreground service, app-level watchdog, sample-rate negotiation, multi-device selection, and richer diagnostics are future work.

Original code: Apache-2.0. Third-party sources have separate licenses; preserve notices and corresponding sources on redistribution.
