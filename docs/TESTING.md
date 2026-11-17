# Touchbeam — About screen test build 0.9.1

A native **Android Beam recreation**, with the original NFC connection, completion and error sounds, original particle texture, and a Canvas adaptation of AOSP's particles. The sender sees the classic black screen, cyan “Touch to beam” prompt, and a 60% app preview. This is an independent app, not Samsung S Beam or a restoration of Android's removed system-wide Beam feature.

Version 0.4 sends **an entire selection in one beam**: one connection, one tap to send, one receiving confirmation, and one final completion after every file is verified and saved. Files stream sequentially over the same Bluetooth connection. They are not all loaded into memory at once. The Beam screen, sound and particles remain in place, with progress for the current file and the whole batch.

## About screen in 0.9.1

On the main screen, tap **About Touchbeam**. It shows the original animated Beam particles, “Touch. Tap. Beam.”, the installed version, **Created by NursePandaz**, and links to GitHub, Report an issue, and How to beam. Credits and open-source licenses remain available in a separate scrollable view. Close or Back returns to the app. The animation pauses when hidden and respects the system animation setting. The About button is disabled during a foreground beam or export.

This patch preserves the 0.9 transfer behavior. Install 0.9.1 on both phones for the latest app, or update an existing 0.9 install directly.

## Touch, tap, receive automatically in 0.9

A successfully authenticated pair-free NFC tap now authorizes **one complete beam**, including a file or the entire selected batch. The receiving phone no longer shows an Accept dialog or Accept notification for that connection. The sender still taps the original **Touch to beam** preview to start sending. Completion still waits for every checksum, durable private save and the final receipt. Nothing is sent merely by opening the Android share target or touching the phones together.

Install **0.9 on both phones**. Enable Ready in background once on the receiver and leave the app, or keep Receive open. Wait for pair-free readiness, keep both phones unlocked, then choose content on the sender, tap Send, touch the backs together and tap the Beam preview. The files arrive automatically. Use Stop in the receiver notification or Cancel beam in the foreground to interrupt; save a permanent copy from Touchbeam afterward.

This applies to the authenticated NFC / LE path on compatible Android 10+ phones. The authorization is set only after proving possession of the current private NFC handover key, is local to that receiver session, and is not saved as an always-accept preference. Selecting a phone manually, an ordinary Classic connection, a matching public UUID or a failed NFC-key proof does not grant it. Those fallback file offers still ask for Accept. A new receive session starts without an authorization grant.

## Pair-free taps from 0.8 (expanded in 0.9)

On supported **Android 10+** phones, NFC can now authorize a new connection without pairing the phones in Settings or creating a Bluetooth bond. This also works with **Ready in background**; the receiver does not need a fresh Receive tap for every new sender. The receiver must support BLE advertising and LE L2CAP connection-oriented channels, plus NFC host card emulation. The sender needs NFC and BLE scanning / LE L2CAP support. Check the receiver’s status: **pair-free NFC taps available** means the new listener and advertisement started. This path does not need Classic Bluetooth visibility.

The original Beam preview, sounds, particles, per-file verification and batch receipt remain. The sender still taps the preview to send. In 0.9, a verified pair-free NFC connection receives automatically; Classic fallback still asks for file acceptance. It is an independent recreation, not the removed Android system implementation. Keep both phones awake and unlocked for the first test. There is no account or internet connection.

### First pair-free test

1. Update both phones to **0.9**. Turn on Bluetooth and NFC and allow Nearby devices. Android 10–11 also needs Location permission / Location on for scanning. On the receiver, enable **Ready in background**, allow notifications, then leave Touchbeam. Wait for the notification to say **pair-free NFC taps available**. Alternatively, open **Receive** and wait for its pair-free-ready status.
2. On the sender, select one small photo through Gallery → Share → Touchbeam, or Choose photos. Tap **Send**. Do not choose from the phone list.
3. Hold the backs together until **Tap recognized**, then separate them. Touchbeam scans only for the temporary receiver identity from that NFC tap and opens the new connection.
4. Tap the original Beam preview to send. The receiver saves it automatically after the verified tap; no Accept action is needed on 0.9. Save the verified file from Touchbeam after completion. Repeat with a small batch.
5. To verify this is actually unbonded, use phones that have never been paired, or optionally Forget their existing pairing in Android settings before the test. Touchbeam itself does not remove or alter stored bonds.

Pair-free discovery has a 25-second scan timeout, followed by bounded connection / authentication timers. A failed authenticated connection is reported; it is not silently retried as an unauthenticated plaintext transfer. Android may throttle rapid scan restarts, so give a failed search time to stop before tapping again. Large files may be slow; real throughput is not yet measured.

### Compatibility and fallback

Android 8–9, missing BLE advertising support, firmware failures or denied advertising permission leave the Classic path available. The receiver uses **Receive with Bluetooth visibility** for an unpaired Classic sender, then approves Android’s visibility prompt. The sender can choose a phone manually. Classic connections can ask for Android pairing; that fallback is not pair-free. Existing 0.6–0.7 phones can use the legacy NFC identifier / Classic path. Pair-free handover is offered only by 0.8 when its LE listener and advertising are active.

### How the new connection is authorized

The BLE advertisement contains only a fresh public service UUID. A second NFC command hands over the matching session identity, dynamic LE port and a separate random 256-bit secret. No local Bluetooth hardware address is read, and the secret is not advertised. Both peers prove knowledge of the NFC secret using HMAC-SHA256 bound to the session identity, endpoint port and fresh nonces. Separate directional keys encrypt bounded 32 KiB records with AES-256-GCM. Sequence-based nonces and authenticated records reject altered / replayed data before releasing plaintext. The existing protocol still checks file hashes, private storage commit and the final receipt. Pending handover data is removed when the connection starts, the listener closes or its identity expires.

The underlying Android LE socket is deliberately unbonded; application authentication and encryption happen before the Beam preview or file metadata is accepted. This is a new experimental application protocol, not independently security-audited, and it trusts the physical NFC handover. Host tests cover wrong keys / identities / ports, old proofs, record tampering / replay, truncation, bounds, cancellation, exact file and batch bytes, declines and receipts. Actual BLE discovery, advertising, LE sockets, NFC handover, background lifecycle and unbonded behavior still require real-phone validation. No attached phone or emulator was available.

References: [Android LE L2CAP receiver API](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#listenUsingInsecureL2capChannel()), [sender API](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#createInsecureL2capChannel(int)), [BLE advertising](https://developer.android.com/reference/android/bluetooth/le/BluetoothLeAdvertiser).

## Ready in background in 0.7 (retained, expanded in 0.8)

Enable **Ready in background** once on the receiving phone. Allow Nearby devices and notifications, leave Bluetooth and NFC on, then leave Touchbeam using Home or Back. Wait for its notification to say **Ready while unlocked**. From then on, supported 0.8 phones can use the pair-free path, and paired Classic phones can also tap to beam without reopening Touchbeam or pressing Receive. Keep the receiving phone awake and unlocked. The sender still opens its share selection / Send screen and taps the Beam preview.

For the Classic fallback background test, pair the phones once or complete an ordinary foreground beam first. Classic does not make the phone permanently discoverable; a new Classic sender needs temporary visibility using Receive with Bluetooth visibility or system settings. The new pair-free LE route needs neither Classic visibility nor a stored bond. Classic tap lookup checks up to 32 eligible paired entries.

Verified pair-free NFC connections in 0.9 receive files and batches automatically. **Classic fallback** files and batches require **Accept** in the incoming notification within 60 seconds. Expand it to review filenames, or tap it to open Touchbeam and use **Review incoming background beam** for the full list. Decline / timeout discards the provisional transfer. Text is saved automatically while the receiver is unlocked; it is not opened or executed. The completed-beam notification opens Touchbeam, where you can export the received files. As before, only the latest completed file/batch is retained privately; save a permanent copy before the next successful beam replaces it.

The background-readiness notification stays present and has **Stop**, which disables this mode and cancels any incoming transfer. The switch does the same. While the Touchbeam activity is visible, an idle background listener pauses so the regular Send / Receive screen can use Bluetooth. An already connected background transfer continues, and starting another beam is blocked until it ends or you turn readiness off. Opening the save picker / exporting also pauses new background receives. Leaving the app resumes listening automatically when enabled.

This uses Android’s connected-device foreground service, not a hidden always-on system feature. It adds the normal foreground-service permissions and notification permission; no internet, broad storage, overlay, location-in-background or boot permission is added. Notifications must be enabled, including both Touchbeam channels. System / manufacturer battery controls may stop it. Android may restart the service, but readiness is not guaranteed after force-stop, reboot, or system app-stop; open Touchbeam again and check the notification. No automatic boot receiver is installed. A local NFC identity is published only while a real listener is active and expires with that session.

This is the closest supported flow with the current Bluetooth design, not a restoration of the original system Beam privileges. Android’s HCE behavior can also depend on Secure NFC, wallet Observe Mode and device support. Background NFC, notifications, pairing, power management and stop / restart behavior still need real-phone verification.

References: [Android foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [Bluetooth visibility](https://developer.android.com/develop/connectivity/bluetooth/find-bluetooth-devices), [HCE screen and unlock behavior](https://developer.android.com/develop/connectivity/nfc/hce).

## Classic tap-first selection from 0.6 (fallback)

With NFC enabled, **Send** now arms the tap without opening a phone list. The receiver taps **Receive**. Pair-free status needs no visibility; Classic fallback can use **Receive with Bluetooth visibility**. Hold the backs together until the sender says **Tap recognized**. You can then separate them: the sender scans for the exact temporary Bluetooth service supplied by the NFC tap, connects to that phone, and checks the same identity again in the Bluetooth handshake. It does not try pairing with every nearby Touchbeam phone or choose by signal strength or name. Discovery can take several seconds, up to 75 seconds. Keep the receiver visible and both apps open.

Android may ask you to approve first-time pairing on both phones. Return to Touchbeam afterward. The original Beam preview then appears; tap it to send and accept the file/batch on the receiver. A tap chooses the receiver; it does not send the content automatically.

Both phones need 0.6 or newer for legacy tap-first lookup; pair-free taps require 0.8 on both, NFC must be enabled on both, and the receiving phone must support HCE. If a tap cannot be read or its receiver cannot be found, choose **Choose phone / connect without NFC**. Phones with NFC off or unavailable open the nearby list directly. Previously paired phones can be used when scan permission is declined. No local hardware address is read or required.

The receiver advertises two services: the normal Touchbeam service for manual discovery, plus a fresh random service identity for this receive session. Both listeners close after either connection wins or the session is cancelled. The NFC response remains a temporary identifier, not a permanent phone identity. Android may return cached service lists; the Bluetooth handshake rejects a stale/mismatched identifier before any payload. Changing Receive sessions invalidates the prior tap.

## Nearby discovery in 0.5

Receive advertises the live Touchbeam Bluetooth service and requests temporary phone visibility through Android. The manual phone picker performs Classic inquiry followed by service checks, so a newly discovered phone is listed only when its Touchbeam service responds. Search is bounded to 75 seconds and stops when the picker closes or the app leaves the screen. Tap **Search again** if the receiver just became ready. Inquiry and service checks may take several seconds or longer in a crowded area.

The receiver must use Receive or enabled background readiness; installation alone does not make a phone available. Denying visibility or scan permission leaves paired-phone fallback available. Cancel and tap Receive again to renew visibility. Cancelling stops the Touchbeam listener; Android’s general Bluetooth visibility can remain until its two-minute timeout. There is no background beacon or automatic sending. Android’s pairing consent, the Beam tap, and file acceptance remain required. A “Nearby Touchbeam” label reports a responding service, not a verified personal identity; check the phone name and matching pairing code.

Android pairing dialogs can temporarily cover the app. A current pairing operation gets a bounded 90-second exception to foreground cancellation; regular backgrounding still closes foreground sessions. Enabled background readiness starts a separate listener after the foreground sockets close. Keep both phones unlocked and return to Touchbeam after pairing. This behavior needs verification across real phone brands.

## Touchbeam rename in 0.4.2

The app label, home screen, share-menu entry, NFC description, Bluetooth service label, project name and downloadable artifacts now use Touchbeam. The existing installation identity and signing key are retained, so this APK updates the previous app and keeps its saved data.

## Finalization fixes carried forward

- Replaced session timers and terminal events are coordinated, so an old timeout or a cancellation cannot also report transfer success.
- Cancelling closes slow resources in the background rather than holding the UI thread.
- Revoked Bluetooth permissions and document-provider failures produce a clear error instead of a worker crash or delayed silent failure.
- The NFC receiver capability is held in process memory and expires; it is not restored from stale preferences after a process restart or reboot.
- Export names are bounded in UTF-8 bytes, retain extensions where possible, and handle Unicode-normalized / case-only collisions. Transfer metadata and payload bytes are unchanged.
- Staged files check the actual saved length before publishing. Failed commits retain the previous completed receive index.
- Beam preview memory is bounded; rapid taps and disposed animations are guarded. Displayed bitmaps are not recycled while the renderer may still reference them.
- The original particle texture uses density-independent resources; explicit Android 12+ backup exclusions and resource-based status formatting are included.

The original UI, sounds, particles, share target, file limits and batch workflow are retained. This remains a locally signed test APK, not a Play Store or hardware-certified release.

## Install / update

Install **touchbeam-0.9.1-test.apk** on **both phones**. Android 8.0 or newer and Bluetooth Classic are required. The signing certificate matches the earlier APKs from this chat, so install it over the existing app to keep its saved data. Do not uninstall first unless you intend to remove app data.

Version 0.9 lets a verified NFC handover authorize automatic receipt of one beam, while preserving the transfer protocol. Install 0.9 on both phones for the complete no-Accept flow. Versions 0.1–0.3 cannot connect to it; the app gives a version-mismatch message on an incompatible Bluetooth connection.

Use Android's normal downloaded-APK installation flow, or, if USB debugging is already authorized:

```sh
adb install -r touchbeam-0.9.1-test.apk
```

No Android phone or emulator was connected during development. The APK builds, validates and passes automated checks; radio interoperability, NFC positioning, Android picker behavior and native UI still need your two-phone test.

## Limits

- **1–32 photos/files in one beam.** Mixed file types are supported.
- **100 MiB per file** (104,857,600 bytes).
- **500 MiB per batch** (524,288,000 bytes).
- Text / links remain supported, up to **16,384 UTF-8 bytes**, separately from files.
- Keep the sender open. The receiver can stay in Receive, or use Ready in background. Both phones must be unlocked when initiating a beam.
- Unknown file sizes are accepted, but the same limits are enforced while streaming. Combined percentage is shown when all sizes are known; otherwise progress shows transferred bytes and the file count.

## Classic fallback two-phone test

1. Install the new APK on both phones and enable Bluetooth. Pairing in Settings is optional.
2. Open Touchbeam on both phones. On Android 12 and later, allow **Nearby devices** when asked. Android 8–11 needs Location permission and Location enabled for discovery. Touchbeam does not derive location or request broad photo/storage access.
3. On the receiver, tap **Receive with Bluetooth visibility** and approve Android’s temporary visibility prompt. Wait for “Ready to receive.” The phone is discoverable for about two minutes.
4. On the sender, tap **Choose photos** or **Choose files**. In Android's picker, select several items (often by long-pressing the first item). The app shows the complete selection and combined size. **View selected files** lists every item. A preview of the first selected photo is shown when supported.
5. Tap **Send**, then **Choose phone / connect without NFC** if the tap-ready screen appears. Wait for **Nearby Touchbeam**, select the receiver, and choose **Choose phone / connect without NFC** again to connect for this Bluetooth-only test. Approve Android’s matching pairing code on both phones if prompted, then return to Touchbeam. Existing paired phones are also listed immediately as “Paired · not checked.”
6. When the classic Beam preview appears, tap it once. On the receiving phone, review the full filename list and total, then tap **Accept all** within 60 seconds.
7. Keep both apps on screen. The files transfer one after another automatically; no repeated taps or reconnections are needed. Completion plays only after all file checksums, batch integrity and private storage commit succeed.
8. On the receiver, tap **Save all received files…** and select a folder. Touchbeam creates a new uniquely named `Touchbeam-…` subfolder containing the whole batch. A single received file uses Android's normal Save As dialog instead.

A failed or declined batch does not publish a partial selection. Temporary files are discarded; the previous complete received batch stays available. Cancel with Back on the sender or **Cancel beam** on the receiver. A process killed mid-transfer can leave provisional data, which is cleaned on the next app launch.

Bluetooth can be slow for large photos, videos and batches. Transfers have a rolling 45-second inactivity timeout, not a fixed 45-second total duration. A receiver waits up to 5 minutes for a connection, and a connected sender has 90 seconds to tap. Text waits up to 20 seconds for a receipt.

## Share directly from Gallery or other apps

Select multiple photos or files in Gallery, Photos, Files, or another app → **Share → Touchbeam**. Some Android share menus require **More** or **All apps** to reveal it.

The complete shared selection opens ready for review. Tap Send and follow the connection / touch / Beam-preview flow. Verified pair-free taps on 0.9 skip receiver acceptance; the Classic fallback uses Accept all. Nothing is sent automatically just because you opened Touchbeam from the share menu. Duplicate links are deduplicated; different files with identical names are all retained. If any item is inaccessible or exceeds a limit, the selection is rejected visibly; the app does not silently skip it.

The originating app must supply Android `content://` links with temporary read access. Both `EXTRA_STREAM` and `ClipData` shares are supported. If access is missing or revoked, share again or use Choose files. Private filesystem paths, remote URLs masquerading as file links, or malformed shares are rejected. A URL shared as plain text is supported.

If a share includes files and a caption, the files are selected for the beam and the caption remains in the editable text field. Captions are not embedded into photos or sent alongside the batch. **Remove file · send text instead** returns to text sharing. Oversized text/captions are rejected rather than silently truncated. A new share asks before replacing an active beam; finish an ongoing Save operation before sharing again.

## NFC back-to-back test

After the Bluetooth-only test works, restart Receive (or use background readiness); for the Classic fallback allow visibility if needed, then tap Send on the sender. Do not open or select from the list. Hold the backs together until “Tap recognized,” then separate them and wait for the Bluetooth connection. Both need NFC enabled; the receiver also needs host card emulation (HCE) support. Try moving the phones slightly to align their NFC antennas and remove thick cases if necessary.

NFC reads a temporary receiver identifier. The sender finds the matching advertisement and checks the identity during connection setup. The pair-free path also exchanges and proves a private NFC key; the Classic fallback uses its session service identity. Bluetooth carries the actual bytes. This uses reader mode plus HCE, not the removed Android Beam peer-to-peer APIs. It does not emulate a payment card. The receiving NFC service requires an unlocked phone and only responds while a foreground or background receive listener is active. Phones without working NFC/HCE can use the fallback button.

## Saving and retaining received files

The app retains **only the latest successfully received file or batch** in private storage. Save a permanent copy before receiving a new successful batch. A new batch replaces the old private copy only after it completely succeeds. Declined, interrupted or invalid batches leave the old complete copy intact. The last received text is retained independently.

Save all writes into a fresh subfolder; it does not overwrite existing files. Duplicate filenames, including case-only collisions, get suffixes such as `photo (2).jpg` when exported. Touchbeam transfers the shared bytes without re-encoding photos. Received files are never automatically opened or executed.

Saved copies outside the app survive uninstalling it. Private copies, drafts and saved text are removed by clearing app storage or uninstalling. Android cloud-backup and device-transfer exclusions are configured alongside the legacy backup opt-out. Saved copies outside the app are unaffected.

If exporting fails, the private verified batch remains available for another attempt. The app tries to remove the new incomplete export folder; some providers may leave it behind. A cloud folder you choose in Android's picker may use that provider's internet connection. The phone-to-phone transfer itself requires no internet or account.

## Troubleshooting

- **Version mismatch:** update both phones to 0.9, then restart Receive / Send.
- **No nearby phone:** keep the receiver unlocked in Receive, allow visibility, then Search again. On Android 8–11 enable Location and grant its permission. Renew Receive after two minutes. Pairing in Bluetooth settings remains a fallback; BLE-only accessories are excluded.
- **Cannot select multiple items:** the current provider may only support one. Try Android Files or select several in Gallery and share them to Touchbeam.
- **Selection fails:** check every item's read access and limits. Inaccessible items are not silently omitted. Re-share or choose the items again.
- **Folder picker will not allow a root folder:** Android restricts some locations. Choose or create an allowed subfolder instead.
- **Sender reports failure but files are present:** the final receipt may have been lost after the receiver saved the complete batch. Check the receiving phone before resending. There is no automatic retry.
- **App leaves the screen:** a foreground Send / Receive session closes; enabled background readiness starts a new receive session. Check its notification. Active background receives can continue. Selections may need to be chosen again after the app process exits.
- **Audio is silent:** enable Original Beam sounds and check the phone's system sound mode, volume and Do Not Disturb settings.
- **Particles do not move:** a system animation duration scale of zero is respected. Restore system animations if you want motion.

## Build / validation

Open this project in Android Studio, or use the included dependency-free SDK build script:

```sh
bash build-apk.sh
bash test.sh
bash verify.sh
```

The full verification workflow is `bash verify.sh` and additionally requires Python 3 and the SDK command-line tools. It saves the test output, packaged manifest, signature/alignment checks and Android scan under `build/verification/`.

Manual build requires JDK 17, Android SDK platform 35, build-tools 35.0.0, Python 3, and `zip`. SDK location is `ANDROID_SDK_ROOT`, then `ANDROID_HOME`, then `$HOME/Android/Sdk`. The APK is written to `build/manual/touchbeam-test.apk`. The local signing key is kept under `build/manual/` and excluded from the source ZIP; keep the original key for future updates. A new key requires uninstalling the installed app first, which removes its private data. Android Studio may download normal build dependencies at first sync.

**566 automated checks passed.** Coverage includes text, Unicode, bounded file streaming, malformed data, checksums, unknown sizes, 32-file batches, a single aggregate receipt, batch declines, source failures after an earlier file completed, failed storage commits, mid-batch cancellation and cleanup, remaining total limits, filename collisions, NFC identity matching, and share parsing. Additional regression checks cover automatic authenticated file / batch receipt, denial of automatic receipt on Classic and wrong-key connections, pair-free text / files / batches, wrong NFC keys / identities / ports, stale handshake proofs, record tampering / replay / truncation and bounds, declines, listener cleanup, background NFC identity ownership and expiry, stale notification capabilities, consent deadlines, cancellation/acceptance races, socket-cleanup completion, exact tap service routing, malformed tap identities, mismatched receiver rejection, cancellation of both receive listeners, bounded discovery queues, stale probe callbacks, nearby prioritization, pairing grace expiry, terminal-event races, stale timers, permission revocation, provider exceptions, slow resource closure, NFC capability expiry, actual on-disk lengths and exportable long filenames. Transport tests use in-memory duplex streams; share tests use small Android type substitutes. The APK's v2/v3 signatures and update certificate are verified. Both the offline Gradle build and its lint task passed. The Gradle scan reports 0 errors / 4 compatibility warnings; the newer standalone default Android lint scan reports 0 errors and 5 documented compatibility warnings: SDK 35 is not the newest target; the scan permission flag is ignored below Android 12; the back-dispatch flag is ignored on Android below 13; and the portrait-oriented interface has two large-screen/orientation warnings. Those warnings are preserved in the verification report. The two explicit lint exceptions in source cover a programmatic-only view constructor and a deliberately synchronous durability check, not transfer or permission errors. Hardware discovery, actual OS pairing, Android share routing, picker/folder grants, NFC and UI are not verified by those automated checks.

## Attribution

Original audio, star texture and particle behavior: Android Open Source Project, Apache License 2.0.

- [AOSP NFC app](https://android.googlesource.com/platform/packages/apps/Nfc/)
- [Historical source mirror used for original assets and particle behavior](https://github.com/LineageOS/android_packages_apps_Nfc/tree/cm-10.1)

Original assets are `res/raw/start.ogg`, `end.ogg`, `error.ogg`, and `assets/star.png`; particle reference is `FireflyRenderer.java`. The native Canvas adaptation is identified in its source header. Full notices and license text are included under `licenses/`, in `NOTICE.txt`, and in the APK's About screen.
