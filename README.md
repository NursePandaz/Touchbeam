# Touchbeam

An experimental Android app that recreates the classic **Touch to beam** screen, original Beam sounds and particles, with photo, file and batch sharing.

**[Download Touchbeam 0.9.2-test APK](https://github.com/NursePandaz/Touchbeam/releases/download/v0.9.2-test/touchbeam-0.9.2-test.apk)** · [Releases](https://github.com/NursePandaz/Touchbeam/releases) · [Full test guide](docs/TESTING.md)

## Try it on two phones

1. Install the APK on **both phones**, updating any earlier Touchbeam installation.
2. Turn on Bluetooth and NFC and allow Nearby devices. Android 10–11 also needs Location permission and Location enabled for discovery.
3. On the receiver, enable **Ready in background**, allow notifications, then leave Touchbeam. Wait for **pair-free NFC taps receive automatically**. Keep the phone awake and unlocked. You can also keep **Receive** open.
4. On the sender, share a small photo from Gallery to Touchbeam, then tap **Send**. Do not select a phone from the list.
5. Touch the backs together until the tap is recognized. Separate them, then tap the original **Touch to beam** preview. The receiver saves the file automatically.
6. After completion, open Touchbeam on the receiver to save a permanent copy. Try a small batch next.

Pair-free, no-Accept sharing requires compatible **Android 10+**, NFC/HCE, BLE advertising and LE L2CAP support. Android 8–9 and unsupported phones retain the Classic Bluetooth fallback, which may require visibility, pairing and receiver acceptance. The app does not silently switch a failed authenticated transfer to plaintext.

## Test status

**566 host checks pass.** SDK and offline Gradle builds pass; Android scans report no errors and documented compatibility warnings. Original audio and particle pixels are preserved.

**No physical phones or emulator were connected during development.** Real NFC, Bluetooth interoperability, background behavior and the complete two-phone flow still need device testing. Start with one small photo. The new NFC-authorized encryption protocol is experimental and has not been independently security-audited. See [verification details](docs/VERIFICATION.txt).

## About Touchbeam

Open **About Touchbeam** from the app’s main screen for the original drifting Beam particles, “Touch. Tap. Beam.”, installed version and experimental-build badge, and **Created by NursePandaz** with the creator’s GitHub profile picture. GitHub, Report an issue and How to beam open in your browser. The credits view includes the original AOSP notices. Animation pauses when hidden and respects disabled system animations.

## Features

- Original-style Beam preview, sound effects and particles.
- Text and links, photos and arbitrary files; Android Share menu support.
- Up to 32 files per beam, 100 MiB per file and 500 MiB per batch.
- NFC-authorized automatic receipt for one authenticated beam; manual fallback retains Accept.
- Optional background receiver with a status notification and Stop control.
- Offline phone-to-phone transfer, bounded streaming, file checksums and a final receipt.
- Only the latest completed file/batch is kept privately; export a permanent copy before another successful beam replaces it.

## Build

Open in Android Studio, or use JDK 17, Android SDK platform 35 and build-tools 35.0.0:

```sh
bash test.sh
bash build-apk.sh
bash verify.sh
```

The manual build writes `build/manual/touchbeam-test.apk`. `verify.sh` also needs Python 3 and SDK command-line tools. Set `ANDROID_SDK_ROOT` or `ANDROID_HOME` if your SDK is not under `$HOME/Android/Sdk`.

The signing key, machine-specific configuration and build caches are excluded. A fresh source build creates its own local test signing key and cannot update the published APK unless signed with the same original key. The internal application ID remains `dev.beam.classic` to preserve updates from earlier builds.

## Attribution

This is an independent Android Beam recreation, not an official Google or Samsung product. Original AOSP Beam audio, particle texture and reference behavior are covered by Apache License 2.0; see [NOTICE.txt](NOTICE.txt) and [licenses](licenses/).
