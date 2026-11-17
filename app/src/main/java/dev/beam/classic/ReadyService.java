package dev.beam.classic;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import java.io.*;
import java.util.*;

/** User-enabled background receiver. The notification owns consent and Stop. */
public final class ReadyService extends Service {
    private static final String CHANNEL = "touchbeam_ready", EVENTS = "touchbeam_incoming";
    private static final String STOP = "dev.beam.classic.STOP_READY", ACCEPT = "dev.beam.classic.ACCEPT", DECLINE = "dev.beam.classic.DECLINE";
    private static final int READY_ID = 41, RESULT_ID = 42;
    private static ReadyService live;
    private static boolean uiVisible, uiBusy;
    private static int closingSockets;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private NotificationManager notifications;
    private BeamTransport transport;
    private TapRadio radio;
    private volatile int generation;
    private volatile boolean receiving;
    private volatile ReceiveConsent offer;
    private String offerDetails, offerTitle;
    private boolean destroyed, closing, enabledForeground;
    private SoundPool sounds;
    private final Set<Integer> loaded = Collections.synchronizedSet(new HashSet<>());
    private int startSound, endSound, errorSound;
    private long lastProgress;
    private final Runnable retry = this::reconcile;

    static boolean running() { return live != null; }
    static boolean idle() { return closingSockets == 0 && (live == null || (!live.receiving && !live.closing && live.transport == null)); }
    static void closeTransport(BeamTransport old) {
        closingSockets++;
        old.cancelAsync(() -> new Handler(Looper.getMainLooper()).post(() -> {
            closingSockets--; if (live != null) { live.closing = false; live.reconcile(); }
        }));
    }
    static boolean receiving() { return live != null && live.receiving; }
    static void uiVisible(boolean value) { uiVisible = value; if (live != null) live.reconcile(); }
    static void uiBusy(boolean value) { uiBusy = value; if (live != null) live.reconcile(); }
    static boolean allowed(Context context) {
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = nm.getNotificationChannel(CHANNEL), events = nm.getNotificationChannel(EVENTS);
        return nm.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE)
            && (events == null || events.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }
    static void enable(Context context) { context.startForegroundService(new Intent(context, ReadyService.class)); }
    static void disable(Context context) {
        context.getSharedPreferences("beam", MODE_PRIVATE).edit().putBoolean("background_ready", false).apply();
        context.stopService(new Intent(context, ReadyService.class));
    }
    static AlertDialog showOffer(Activity activity) {
        ReadyService service = live;
        if (service == null || service.offer == null || service.offerDetails == null) return null;
        ReceiveConsent current = service.offer;
        return new AlertDialog.Builder(activity).setTitle(service.offerTitle).setMessage(service.offerDetails)
            .setPositiveButton("Accept", (d, w) -> { if (!service.locked()) current.decide(current.id, true); })
            .setNegativeButton("Decline", (d, w) -> current.decide(current.id, false)).show();
    }
    @Override public void onCreate() {
        super.onCreate(); live = this;
        prefs = getSharedPreferences("beam", MODE_PRIVATE); notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Background readiness", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows whether Touchbeam is listening and provides Stop."); notifications.createNotificationChannel(channel);
        NotificationChannel events = new NotificationChannel(EVENTS, "Incoming beams", NotificationManager.IMPORTANCE_HIGH);
        events.setDescription("File acceptance and completed beams."); notifications.createNotificationChannel(events);
        // Snapshot before a new listener starts; only abandoned names in this
        // snapshot can be removed, never files from a newly accepted transfer.
        if (!uiBusy) {
            File parent = new File(getFilesDir(), "received"); File[] abandoned = parent.listFiles();
            String retained = prefs.getString("file_path", ""), batch = prefs.getString("batch_path", "");
            if (abandoned != null) {
                Thread cleanup = new Thread(() -> {
                    for (File file : abandoned) {
                        String name = file.getName();
                        if (name.startsWith("beam-") && !name.equals(retained) && (name.endsWith(".part") || name.endsWith(".bin"))) file.delete();
                        if (name.startsWith("batch-") && !name.equals(batch) && file.isDirectory()) StagedBatch.delete(file);
                    }
                }, "Touchbeam abandoned files"); cleanup.setDaemon(true); cleanup.start();
            }
        }
        sounds = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        sounds.setOnLoadCompleteListener((pool, sample, result) -> { if (result == 0) loaded.add(sample); });
        startSound = sounds.load(this, R.raw.beam_start, 1); endSound = sounds.load(this, R.raw.beam_end, 1); errorSound = sounds.load(this, R.raw.beam_error, 1);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            prefs.edit().putBoolean("background_ready", false).apply(); stopSelf(); return START_NOT_STICKY;
        }
        if (!prefs.getBoolean("background_ready", false) || !allowed(this)) { stopSelf(); return START_NOT_STICKY; }
        if (!enabledForeground) {
            try {
                Notification notification = readyNotification(getString(R.string.ready_starting));
                if (Build.VERSION.SDK_INT >= 29) startForeground(READY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                else startForeground(READY_ID, notification);
                enabledForeground = true;
            } catch (SecurityException | IllegalStateException ex) { stopSelf(); return START_NOT_STICKY; }
        }
        if (intent != null && (ACCEPT.equals(intent.getAction()) || DECLINE.equals(intent.getAction()))) {
            ReceiveConsent current = offer;
            if (current != null && (!ACCEPT.equals(intent.getAction()) || !locked())) current.decide(intent.getStringExtra("offer"), ACCEPT.equals(intent.getAction()));
        }
        reconcile(); return START_STICKY;
    }
    private boolean active(int id) { return !destroyed && id == generation; }
    private boolean locked() { KeyguardManager keyguard = getSystemService(KeyguardManager.class); return keyguard != null && keyguard.isDeviceLocked(); }
    private void reconcile() {
        if (destroyed || !enabledForeground) return;
        if (!allowed(this)) { stopSelf(); return; }
        if (uiVisible || uiBusy) {
            if (!receiving) pause();
            return;
        }
        if (transport != null || closing || closingSockets > 0) return;
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        try {
            if (adapter == null || !adapter.isEnabled()) { updateReady(getString(R.string.ready_bluetooth_off)); main.removeCallbacks(retry); main.postDelayed(retry, 5000); return; }
        } catch (SecurityException ex) { stopSelf(); return; }
        final int id = ++generation;
        final String token = UUID.randomUUID().toString();
        transport = new BeamTransport(new BeamTransport.Listener() {
            private int fileCount;
            private boolean tapAuthorized;
            public void onTapAuthorized() { if (active(id)) tapAuthorized = true; }
            public void onListening() { main.post(() -> { if (active(id)) { NfcSession.enableBackground(token, SystemClock.elapsedRealtime()); if (radio != null) radio.publish(); updateReady(getString(radio != null && radio.available() ? R.string.pairless_background_ready : R.string.ready_listening)); } }); }
            public void onConnecting() {
                if (!active(id) || locked()) throw new IllegalStateException("Unlock the receiving phone.");
                receiving = true;
                main.post(() -> { if (active(id)) { NfcSession.clearBackground(); if (radio != null) radio.close(); updateReady(getString(R.string.pairless_authenticating)); } });
            }
            public void onReady(String name) {
                if (!active(id) || locked()) throw new IllegalStateException("Unlock the receiving phone.");
                receiving = true;
                main.post(() -> { if (active(id)) { NfcSession.clearBackground(); if (radio != null) radio.close(); play(startSound); updateReady(getString(tapAuthorized ? R.string.tap_auto_connected : R.string.ready_connected)); } });
            }
            public void onReceived(String text) throws IOException {
                if (!active(id) || locked() || !prefs.edit().putString("received", text).commit()) throw new IOException("Receiver closed.");
            }
            public FileProtocol.Sink onFileOffer(FileProtocol.Meta meta) throws IOException {
                if (!active(id) || locked() || (!tapAuthorized && !consent(id, Collections.singletonList(meta)))) return null;
                return new ReceiveIndex(getFilesDir(), prefs).fileSink(meta, () -> active(id), () -> fileCount = 1);
            }
            public BatchProtocol.Sink onBatchOffer(BatchProtocol.Plan plan) throws IOException {
                if (!active(id) || locked() || (!tapAuthorized && !consent(id, plan.files))) return null;
                return new ReceiveIndex(getFilesDir(), prefs).batchSink(plan, () -> active(id), () -> fileCount = plan.files.size());
            }
            public void onProgress(long done, long total) { progress(id, done, total); }
            public void onBatchProgress(int index, int count, String name, long done, long total) { progress(id, done, total); }
            public void onSuccess() { main.post(() -> {
                if (!active(id)) return;
                play(endSound); result(getString(R.string.ready_complete), fileCount == 0 ? getString(R.string.ready_text_saved) : getResources().getQuantityString(R.plurals.ready_files_saved, fileCount, fileCount)); ended(false);
            }); }
            public void onError(String error) { main.post(() -> {
                if (!active(id)) return;
                // Idle receiver timeout is a normal session-identity rotation.
                if (receiving) { play(errorSound); result(getString(R.string.ready_failed), error); }
                ended(true);
            }); }
        });
        final BeamTransport incoming = transport;
        radio = new TapRadio(adapter, token);
        radio.start((socket, offer) -> { if (active(id) && transport == incoming) incoming.receive(adapter, token, socket, offer); });
    }
    private boolean consent(int id, List<FileProtocol.Meta> files) throws IOException {
        if (!active(id) || locked()) return false;
        ReceiveConsent current = new ReceiveConsent(); offer = current;
        String title = getResources().getQuantityString(R.plurals.ready_offer_title, files.size(), files.size());
        StringBuilder details = new StringBuilder(getString(R.string.ready_offer_instructions));
        for (FileProtocol.Meta meta : files) details.append('\n').append(meta.name).append(" · ").append(meta.size < 0 ? getString(R.string.ready_size_unknown) : getString(R.string.ready_bytes, meta.size));
        main.post(() -> {
            if (active(id) && offer == current) {
                offerTitle = title; offerDetails = details.toString();
                Notification.Builder builder = base(EVENTS, title, offerDetails).setOngoing(true)
                    .setStyle(new Notification.BigTextStyle().bigText(offerDetails))
                    .addAction(action(getString(R.string.ready_accept), ACCEPT, current.id, 3))
                    .addAction(action(getString(R.string.ready_decline), DECLINE, current.id, 4));
                notifySafe(RESULT_ID, builder.build());
            }
        });
        try { return current.await(60000) && active(id); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("Receive cancelled.", ex); }
        finally {
            if (offer == current) offer = null;
            main.post(() -> { if (active(id)) { notifications.cancel(RESULT_ID); offerTitle = null; offerDetails = null; updateReady(getString(R.string.ready_receiving)); } });
        }
    }
    private void progress(int id, long done, long total) {
        long now = SystemClock.elapsedRealtime(); if (now - lastProgress < 1000 && done != total) return; lastProgress = now;
        main.post(() -> { if (active(id)) updateReady(getString(R.string.ready_progress, done / 1024)); });
    }
    private void ended(boolean delay) {
        generation++; if (radio != null) { radio.close(); radio = null; } transport = null; receiving = false; NfcSession.clearBackground();
        ReceiveConsent current = offer; offer = null; if (current != null) current.cancel();
        updateReady(getString(R.string.ready_starting)); main.removeCallbacks(retry); main.postDelayed(retry, delay ? 3000 : 500);
    }
    private void pause() {
        if (transport != null) {
            generation++; NfcSession.clearBackground(); if (radio != null) { radio.close(); radio = null; }
            BeamTransport old = transport; transport = null; closing = true;
            closeTransport(old);
        }
        updateReady(getString(R.string.ready_paused));
    }
    private PendingIntent open() { return PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE); }
    private Notification.Builder base(String channel, String title, String detail) {
        return new Notification.Builder(this, channel).setSmallIcon(R.drawable.notification_beam).setContentTitle(title).setContentText(detail)
            .setContentIntent(open()).setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true);
    }
    private Notification.Action action(String title, String action, String id, int request) {
        Intent intent = new Intent(this, ReadyService.class).setAction(action).setData(android.net.Uri.parse("touchbeam://offer/" + id + "/" + action)).putExtra("offer", id);
        PendingIntent pending = PendingIntent.getService(this, request, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action.Builder builder = new Notification.Action.Builder(null, title, pending);
        if (Build.VERSION.SDK_INT >= 31 && ACCEPT.equals(action)) builder.setAuthenticationRequired(true);
        return builder.build();
    }
    private Notification readyNotification(String detail) { return base(CHANNEL, getString(R.string.ready_title), detail).setOngoing(true).addAction(action(getString(R.string.ready_stop), STOP, "", 2)).build(); }
    private void notifySafe(int id, Notification notification) { try { notifications.notify(id, notification); } catch (SecurityException ex) { stopSelf(); } }
    private void updateReady(String detail) { if (!destroyed && enabledForeground) notifySafe(READY_ID, readyNotification(detail)); }
    private void result(String title, String detail) { notifySafe(RESULT_ID, base(EVENTS, title, detail).setStyle(new Notification.BigTextStyle().bigText(detail)).setAutoCancel(true).build()); }
    private void play(int sound) { if (prefs.getBoolean("sound", true) && loaded.contains(sound)) sounds.play(sound, 1, 1, 1, 0, 1); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        destroyed = true; generation++; if (radio != null) { radio.close(); radio = null; } NfcSession.clearBackground(); main.removeCallbacksAndMessages(null);
        ReceiveConsent current = offer; offer = null; if (current != null) { current.cancel(); notifications.cancel(RESULT_ID); }
        if (transport != null) { closeTransport(transport); transport = null; }
        receiving = false; if (live == this) live = null; sounds.release(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
