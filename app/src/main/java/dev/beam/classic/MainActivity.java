package dev.beam.classic;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.media.*;
import android.nfc.*;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.tech.IsoDep;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import org.json.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int NONE = 0, RECEIVE = 1, ARMED = 2, CONNECTING = 3, READY = 4, SENDING = 5;
    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout column;
    private TextView status, received;
    private EditText message;
    private Button sendButton, receiveButton, fallback, cancel, copy;
    private Button photoButton, fileButton, clearFile, saveFile, sharedChooser, aboutButton;
    private TextView attachment, receivedFile;
    private ImageView photoPreview;
    private Bitmap previewBitmap;
    private final ArrayList<Uri> selectedUris = new ArrayList<>();
    private final ArrayList<FileProtocol.Meta> selectedMetas = new ArrayList<>();
    private final ArrayList<FileProtocol.Source> pendingSources = new ArrayList<>();
    private boolean loadingAttachment, exporting, choosingSave;
    private int pickGeneration;
    private AlertDialog offerDialog;
    private AlertDialog sharedDialog;
    private boolean sharedSelectionPending;
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private Switch sound, backgroundReady;
    private boolean updatingReady;
    private AlertDialog backgroundOffer;
    private AboutDialog aboutDialog;
    private final SharedPreferences.OnSharedPreferenceChangeListener receiveChanges = (preferences, key) -> {
        if (backgroundReady == null) return;
        if ("background_ready".equals(key)) setReadyChecked(preferences.getBoolean("background_ready", false));
        if ("received".equals(key) && received != null) { received.setText(preferences.getString("received", "")); copy.setVisibility(received.length() == 0 ? View.GONE : View.VISIBLE); }
        if (key != null && (key.startsWith("file_") || key.startsWith("batch_")) && saveFile != null) refreshReceivedFile();
    };
    private BluetoothAdapter bluetooth;
    private NfcAdapter nfc;
    private BeamTransport transport;
    private BeamOverlay overlay;
    private SharedPreferences prefs;
    private SoundPool sounds;
    private final Set<Integer> loadedSounds = Collections.synchronizedSet(new HashSet<>());
    private int startSound, endSound, errorSound;
    private int mode = NONE;
    private volatile int generation;
    private boolean resumed;
    private boolean receiverListening;
    private String pendingAction, pendingMessage, receiverToken;
    private BluetoothDevice peer;
    private final AtomicBoolean readingNfc = new AtomicBoolean();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PairingGuard pairing = new PairingGuard();
    private boolean bondReceiverRegistered, waitingVisibility, resolvingTap;
    private long visibleUntil;
    private NearbyDiscovery discovery;
    private TapRadio receiverRadio;
    private TapRadio.Search pairlessSearch;
    private AlertDialog nearbyDialog;
    private ArrayAdapter<String> nearbyAdapter;
    private TextView nearbyStatus;
    private final ArrayList<BluetoothDevice> nearbyDevices = new ArrayList<>();
    private final ArrayList<Boolean> nearbyReady = new ArrayList<>();
    private int discoveryGeneration;
    private final BroadcastReceiver bondReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) pairing.update(device.getAddress(), intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE) == BluetoothDevice.BOND_BONDING);
            } catch (RuntimeException ignored) {}
        }
    };
    private final Runnable pairingExpired = () -> {
        if (!resumed && pairing.expired(SystemClock.elapsedRealtime()) && mode != NONE) { stopSession(); status.setText(R.string.discovery_pairing_expired); }
    };
    private final Runnable visibilityExpired = () -> {
        if (mode == RECEIVE && receiverListening && visibleUntil > 0 && SystemClock.elapsedRealtime() >= visibleUntil) status.setText(receiverRadio != null && receiverRadio.available() ? R.string.pairless_visibility_expired : R.string.discovery_visibility_expired);
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ReadyService.uiVisible(true);
        if (getActionBar() != null) getActionBar().hide();
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        prefs = getSharedPreferences("beam", MODE_PRIVATE);
        prefs.edit().remove("receiving").remove("receiving_until").remove("token").apply();
        NfcSession.clear();
        // Capture only old files before any session starts; remove abandoned staging
        // files and unreferenced completed files left by a killed process.
        File[] oldFiles = new File(getFilesDir(), "received").listFiles();
        String retained = prefs.getString("file_path", "");
        String retainedBatch = prefs.getString("batch_path", "");
        if (!ReadyService.running() && oldFiles != null) disk.execute(() -> {
            for (File file : oldFiles) {
                if (file.getName().startsWith("beam-") && !file.getName().equals(retained)
                        && (file.getName().endsWith(".part") || file.getName().endsWith(".bin"))) file.delete();
                if (file.getName().startsWith("batch-") && !file.getName().equals(retainedBatch) && file.isDirectory()) StagedBatch.delete(file);
            }
        });
        BluetoothManager manager = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        bluetooth = manager == null ? null : manager.getAdapter();
        nfc = NfcAdapter.getDefaultAdapter(this);
        sounds = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        sounds.setOnLoadCompleteListener((pool, sample, result) -> { if (result == 0) loadedSounds.add(sample); });
        startSound = sounds.load(this, R.raw.beam_start, 1);
        endSound = sounds.load(this, R.raw.beam_end, 1);
        errorSound = sounds.load(this, R.raw.beam_error, 1);
        buildUi();
        IntentFilter bonds = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bondReceiver, bonds, Context.RECEIVER_EXPORTED);
        else registerReceiver(bondReceiver, bonds);
        bondReceiverRegistered = true;
        prefs.registerOnSharedPreferenceChangeListener(receiveChanges);
        handleShare(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); handleShare(intent);
    }
    private void handleShare(Intent intent) {
        if (!SharedContent.isShare(intent)) return;
        final SharedContent content;
        try { content = SharedContent.read(intent); }
        catch (IOException ex) {
            new AlertDialog.Builder(this).setTitle("Could not open shared item").setMessage(ex.getMessage()).setPositiveButton("Close", null).show();
            return;
        }
        if (exporting) {
            new AlertDialog.Builder(this).setTitle("Finishing a save")
                .setMessage("Let the current save finish, then share this item to Touchbeam again.")
                .setPositiveButton("Close", null).show(); return;
        }
        Runnable apply = () -> {
            if (sharedDialog != null) { sharedDialog.dismiss(); sharedDialog = null; }
            stopSession(); loadingAttachment = false; clearAttachment();
            message.setText(content.text == null ? "" : content.text);
            prefs.edit().putString("draft", message.getText().toString()).apply();
            if (content.files.isEmpty()) {
                busy(NONE); status.setText(R.string.ui_shared_text_is_ready_review_it_then);
            } else {
                clearFile.setVisibility(View.VISIBLE); loadAttachments(content.files, content.mime);
            }
            scroll.post(() -> scroll.smoothScrollTo(0, 0));
        };
        if (mode != NONE) {
            new AlertDialog.Builder(this).setTitle("Open this share?")
                .setMessage("Cancel the current beam and load the shared content?")
                .setPositiveButton("Cancel beam and load", (d, w) -> apply.run()).setNegativeButton("Keep current beam", null).show();
        } else apply.run();
    }
    private void reviewSelection() {
        if (mode != NONE || selectedMetas.isEmpty()) return;
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < selectedMetas.size(); i++) list.append(i + 1).append(". ").append(selectedMetas.get(i).name).append(" · ").append(sizeLabel(selectedMetas.get(i).size)).append('\n');
        sharedDialog = new AlertDialog.Builder(this).setTitle("Files in this beam").setMessage(list.toString()).setPositiveButton("Close", null).create();
        sharedDialog.show();
    }

    private int dp(float value) { return (int)(getResources().getDisplayMetrics().density * value + .5f); }
    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(12); view.setLayoutParams(p);
        return view;
    }
    private Button button(String label, Runnable task) {
        Button view = new Button(this); view.setText(label); view.setAllCaps(false);
        view.setOnClickListener(v -> task.run()); view.setMinHeight(dp(48));
        column.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private void buildUi() {
        root = new FrameLayout(this); root.setBackgroundColor(0xff111111);
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL); column.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(column); root.addView(scroll, new FrameLayout.LayoutParams(-1, -1)); setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else scroll.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        column.addView(text("Touchbeam", 28, Color.WHITE));
        column.addView(text("Open a moment. Touch to beam.", 16, 0xff33b5e5));
        column.addView(text("Make one phone ready to receive. Tap Send on the other and hold the backs together. Supported phones connect without pairing.", 14, 0xffbbbbbb));
        message = new EditText(this); message.setText(prefs.getString("draft", "Beam me up, Android!"));
        message.setHint("Text to beam"); message.setTextColor(Color.WHITE); message.setTextSize(20);
        message.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        message.setMinLines(3); message.setMaxLines(8); message.setGravity(Gravity.TOP);
        message.setContentDescription("Text to beam");
        column.addView(message, new LinearLayout.LayoutParams(-1, -2));
        photoButton = button("Choose photos", () -> pickFile("image/*"));
        fileButton = button("Choose files", () -> pickFile("*/*"));
        attachment = text("Sending text", 14, 0xffbbbbbb); column.addView(attachment);
        photoPreview = new ImageView(this); photoPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photoPreview.setContentDescription("Selected photo preview"); photoPreview.setVisibility(View.GONE);
        column.addView(photoPreview, new LinearLayout.LayoutParams(-1, dp(180)));
        clearFile = button("Remove file · send text instead", () -> clearAttachment()); clearFile.setVisibility(View.GONE);
        sharedChooser = button("View selected files", () -> reviewSelection()); sharedChooser.setVisibility(View.GONE);
        sendButton = button("Send", () -> prepare("send"));
        receiveButton = button("Receive", () -> prepare("receive"));
        button("Receive with Bluetooth visibility", () -> { if (mode == NONE) prepare("receive_visible"); });
        fallback = button("Choose phone / connect without NFC", () -> { if (peer == null) { resolvingTap = false; disableNfc(); choosePeer(); } else connectPeer(null); }); fallback.setVisibility(View.GONE);
        cancel = button("Cancel beam", () -> { stopSession(); status.setText(R.string.ui_ready_for_another_beam); }); cancel.setEnabled(false);
        button("Bluetooth pairing settings", () -> { stopSession(); startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); });
        sound = new Switch(this); sound.setText(R.string.ui_original_beam_sounds); sound.setTextColor(0xffdddddd);
        sound.setChecked(prefs.getBoolean("sound", true)); sound.setPadding(0, dp(12), 0, dp(12));
        sound.setOnCheckedChangeListener((v, value) -> prefs.edit().putBoolean("sound", value).apply()); column.addView(sound);
        backgroundReady = new Switch(this); backgroundReady.setText(R.string.ready_toggle); backgroundReady.setTextColor(0xffdddddd);
        backgroundReady.setChecked(prefs.getBoolean("background_ready", false));
        backgroundReady.setOnCheckedChangeListener((view, checked) -> {
            if (updatingReady) return;
            if (checked) prepare("background"); else ReadyService.disable(this);
        }); column.addView(backgroundReady);
        column.addView(text(getString(R.string.ready_explanation), 12, 0xff999999));
        button("Review incoming background beam", () -> {
            if (backgroundOffer != null) backgroundOffer.dismiss();
            backgroundOffer = ReadyService.showOffer(this);
            if (backgroundOffer == null) status.setText(R.string.ready_no_offer);
        });
        status = text("Choose Send or Receive. Transfers stay between your phones.", 15, 0xff33b5e5); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); column.addView(status);
        received = text(prefs.getString("received", ""), 20, Color.WHITE); received.setTextIsSelectable(true); column.addView(received);
        copy = button("Copy received text", () -> {
            ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("Beam received text", received.getText()));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        }); copy.setVisibility(received.length() == 0 ? View.GONE : View.VISIBLE);
        receivedFile = text("", 15, Color.WHITE); column.addView(receivedFile);
        saveFile = button("Save received file…", () -> { if (ReadyService.receiving()) status.setText(R.string.ready_incoming_busy); else chooseSaveLocation(); }); refreshReceivedFile();
        aboutButton = button("About Touchbeam", () -> {
            if (mode != NONE || exporting) return;
            if (aboutDialog == null) aboutDialog = new AboutDialog(this);
            aboutDialog.show();
        });
        column.addView(text("Test build 0.9.1 · Android Beam recreation\nPair-free NFC taps · up to 32 files per beam\n100 MB each · 500 MB total · no internet needed", 12, 0xff999999));
    }

    private void prepare(String action) {
        if (action == null) return;
        pendingAction = action;
        boolean background = action.startsWith("background");
        if (!background && ReadyService.receiving()) { status.setText(R.string.ready_incoming_busy); return; }
        if (!background && !ReadyService.idle()) {
            mainHandler.postDelayed(() -> { if (resumed && action.equals(pendingAction) && !isDestroyed()) prepare(action); }, 50); return;
        }
        boolean receiving = action.startsWith("receive"), pairedOnly = action.endsWith("_paired") || background;
        if (bluetooth == null) { if (background) setReadyChecked(false); status.setText(R.string.ui_this_phone_has_no_bluetooth_classic_adapter); return; }
        ArrayList<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            String extra = receiving ? Manifest.permission.BLUETOOTH_ADVERTISE : Manifest.permission.BLUETOOTH_SCAN;
            if (!pairedOnly && !granted(extra)) permissions.add(extra);
        } else if (!pairedOnly && !receiving && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) { permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION); permissions.add(Manifest.permission.ACCESS_FINE_LOCATION); }
        if ("background".equals(action) && Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_ADVERTISE)) permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        if (background && Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) { requestPermissions(permissions.toArray(new String[0]), 100); return; }
        try {
            if (!bluetooth.isEnabled()) { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 101); return; }
            if (background) {
                if (!ReadyService.allowed(this)) { setReadyChecked(false); status.setText(R.string.ready_needs_notifications); return; }
                prefs.edit().putBoolean("background_ready", true).apply(); ensureBackgroundReady(); status.setText(R.string.ready_enabled); return;
            }
            if (receiving) { if (pairedOnly || (!action.endsWith("visible") && TapRadio.possible(bluetooth))) startReceiver(0); else requestVisibility(); }
            else if (pairedOnly) choosePairedPeer();
            else if (nfc != null && nfc.isEnabled()) armSender(null); else choosePeer();
        } catch (SecurityException ex) { status.setText(R.string.ui_allow_nearby_devices_permission_to_connect_the); }
    }
    private void setReadyChecked(boolean checked) { updatingReady = true; backgroundReady.setChecked(checked); updatingReady = false; }
    private void ensureBackgroundReady() {
        if (!prefs.getBoolean("background_ready", false)) return;
        try {
            if (!ReadyService.allowed(this)) { ReadyService.disable(this); setReadyChecked(false); status.setText(R.string.ready_needs_notifications); return; }
            ReadyService.enable(this);
        } catch (SecurityException | IllegalStateException ex) { ReadyService.disable(this); setReadyChecked(false); status.setText(R.string.ready_cannot_start); }
    }
    private boolean granted(String permission) { return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private boolean canConnect() { return Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT); }
    private boolean canScan() { return Build.VERSION.SDK_INT >= 31 ? granted(Manifest.permission.BLUETOOTH_SCAN) : granted(Manifest.permission.ACCESS_FINE_LOCATION); }
    private void requestVisibility() {
        stopSession(); pendingAction = "receive"; waitingVisibility = true;
        Intent visible = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
        try { startActivityForResult(visible, 102); }
        catch (SecurityException | ActivityNotFoundException ex) { waitingVisibility = false; startReceiver(0); }
    }
    private static String sizeLabel(long bytes) {
        if (bytes < 0) return "Size unknown · maximum 100 MB";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024d);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024d * 1024));
    }
    private void pickFile(String mime) {
        if (mode != NONE) return;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime);
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(picker, 200); }
        catch (ActivityNotFoundException ex) { status.setText(R.string.ui_no_file_picker_is_available_on_this); }
    }
    private void clearAttachment() {
        pickGeneration++;
        selectedUris.clear(); selectedMetas.clear();
        sharedSelectionPending = false; sharedChooser.setVisibility(View.GONE);
        attachment.setText(R.string.ui_sending_text); clearFile.setVisibility(View.GONE); photoPreview.setVisibility(View.GONE);
        photoPreview.setImageDrawable(null);
        previewBitmap = null;
        status.setText(R.string.ui_text_will_be_sent_choose_a_photo);
        if (sendButton != null) busy(mode);
    }
    private Bitmap thumbnail(Uri uri) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, options); }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            int sample = 1;
            while (options.outWidth / sample > 512 || options.outHeight / sample > 512) sample *= 2;
            options.inJustDecodeBounds = false; options.inSampleSize = sample; options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = getContentResolver().openInputStream(uri)) { return BitmapFactory.decodeStream(in, null, options); }
        } catch (IOException | RuntimeException | OutOfMemoryError ex) { return null; }
    }
    private void loadAttachments(List<Uri> selected, String fallbackMime) {
        final ArrayList<Uri> items = new ArrayList<>(new LinkedHashSet<>(selected));
        if (items.isEmpty() || items.size() > BatchProtocol.MAX_ITEMS) { status.setText(R.string.ui_choose_1_32_files); return; }
        final int pick = ++pickGeneration; loadingAttachment = true; sharedSelectionPending = true; busy(NONE); status.setText(R.string.ui_reading_file_details);
        disk.execute(() -> {
            try {
                ArrayList<FileProtocol.Meta> metadata = new ArrayList<>();
                for (Uri uri : items) {
                    if (!"content".equals(uri.getScheme())) throw new IOException("Invalid file link.");
                    String name = "beam-file"; long bytes = -1;
                    try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) {
                            int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME), sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                            if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn);
                            if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) bytes = cursor.getLong(sizeColumn);
                        }
                    }
                    String mime = getContentResolver().getType(uri);
                    if (mime == null || mime.contains("*")) mime = fallbackMime;
                    if (mime == null || mime.contains("*")) {
                        int dot = name.lastIndexOf('.');
                        mime = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
                    }
                    metadata.add(new FileProtocol.Meta(name, mime, bytes));
                    try (InputStream probe = getContentResolver().openInputStream(uri)) { if (probe == null) throw new IOException("No read access."); }
                }
                BatchProtocol.Plan plan = new BatchProtocol.Plan(metadata);
                FileProtocol.Meta first = metadata.get(0);
                Bitmap preview = first.mime.startsWith("image/") ? thumbnail(items.get(0)) : null;
                runOnUiThread(() -> {
                    if (isDestroyed() || pick != pickGeneration) { if (preview != null) preview.recycle(); return; }
                    loadingAttachment = false; sharedSelectionPending = false;
                    selectedUris.clear(); selectedUris.addAll(items); selectedMetas.clear(); selectedMetas.addAll(metadata);
                    photoPreview.setImageDrawable(null); previewBitmap = preview;
                    photoPreview.setImageBitmap(preview); photoPreview.setVisibility(preview == null ? View.GONE : View.VISIBLE);
                    attachment.setText(items.size() == 1 ? getString(R.string.selection_single, first.name, sizeLabel(first.size)) : getResources().getQuantityString(R.plurals.selection_batch, items.size(), items.size(), plan.total < 0 ? getString(R.string.size_unknown) : sizeLabel(plan.total)));
                    clearFile.setVisibility(View.VISIBLE); sharedChooser.setVisibility(items.size() > 1 ? View.VISIBLE : View.GONE);
                    busy(NONE); status.setText(getResources().getQuantityString(message.length() > 0 ? R.plurals.ready_files_with_text : R.plurals.ready_files, items.size(), items.size()));
                });
            } catch (IOException | RuntimeException ex) {
                runOnUiThread(() -> { if (!isDestroyed() && pick == pickGeneration) { loadingAttachment = false; clearFile.setVisibility(View.VISIBLE); busy(NONE); status.setText(R.string.ui_selection_could_not_be_loaded_check_file); } });
            }
        });
    }
    private ReceiveIndex receiveIndex() { return new ReceiveIndex(getFilesDir(), prefs); }
    private File storedFile() { return receiveIndex().file(); }
    private File storedBatchDirectory() { return receiveIndex().batch(); }
    private static final class StoredItem {
        final File file; final FileProtocol.Meta meta;
        StoredItem(File file, FileProtocol.Meta meta) { this.file = file; this.meta = meta; }
    }
    private List<StoredItem> storedItems() {
        ArrayList<StoredItem> items = new ArrayList<>();
        File directory = storedBatchDirectory();
        try {
            if (directory != null) {
                JSONArray manifest = new JSONArray(prefs.getString("batch_items", "[]"));
                if (manifest.length() == 0 || manifest.length() > BatchProtocol.MAX_ITEMS) return items;
                for (int i = 0; i < manifest.length(); i++) {
                    JSONObject entry = manifest.getJSONObject(i);
                    File file = new File(directory, StagedBatch.fileName(i));
                    FileProtocol.Meta meta = new FileProtocol.Meta(entry.getString("name"), entry.getString("mime"), entry.getLong("size"));
                    if (!file.isFile() || file.length() != meta.size) return Collections.emptyList();
                    items.add(new StoredItem(file, meta));
                }
            } else {
                File file = storedFile();
                if (file != null) items.add(new StoredItem(file, new FileProtocol.Meta(prefs.getString("file_name", "beam-file"), prefs.getString("file_mime", "application/octet-stream"), file.length())));
            }
        } catch (IOException | JSONException ex) { return Collections.emptyList(); }
        return items;
    }
    private void refreshReceivedFile() {
        List<StoredItem> items = storedItems(); boolean exists = !items.isEmpty();
        StringBuilder detail = new StringBuilder();
        if (exists) { detail.append("Received ").append(items.size()).append(" file(s):\n"); for (StoredItem item : items) detail.append(item.meta.name).append(" · ").append(sizeLabel(item.meta.size)).append('\n'); }
        receivedFile.setText(detail.toString());
        receivedFile.setVisibility(exists ? View.VISIBLE : View.GONE); saveFile.setVisibility(exists ? View.VISIBLE : View.GONE);
        saveFile.setText(items.size() > 1 ? R.string.save_all_received : R.string.save_one_received);
    }
    private void chooseSaveLocation() {
        List<StoredItem> items = storedItems();
        if (mode != NONE || items.isEmpty() || exporting) return;
        choosingSave = true; ReadyService.uiBusy(true);
        if (items.size() > 1) {
            Intent folder = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { startActivityForResult(folder, 202); } catch (ActivityNotFoundException ex) { choosingSave = false; busy(NONE); status.setText(R.string.ui_no_folder_picker_is_available_on_this); }
            return;
        }
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(items.get(0).meta.mime).putExtra(Intent.EXTRA_TITLE, BatchProtocol.exportName(items.get(0).meta.name));
        try { startActivityForResult(save, 201); }
        catch (ActivityNotFoundException ex) { choosingSave = false; busy(NONE); status.setText(R.string.ui_no_file_saving_app_is_available_on); }
    }
    private void exportFile(Uri uri) {
        List<StoredItem> items = storedItems(); if (items.isEmpty()) { status.setText(R.string.ui_no_received_file_is_available); return; }
        final File file = items.get(0).file;
        exporting = true; busy(NONE); status.setText(R.string.ui_saving_received_file);
        disk.execute(() -> {
            String result = "File saved to your chosen location.";
            try (InputStream in = new FileInputStream(file); OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("Cannot open destination.");
                byte[] buffer = new byte[32768]; int count;
                while ((count = in.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Saving interrupted.");
                    out.write(buffer, 0, count);
                }
                out.flush();
            } catch (IOException | RuntimeException ex) {
                try { android.provider.DocumentsContract.deleteDocument(getContentResolver(), uri); } catch (Exception ignored) {}
                result = "Could not save a copy. The verified received file is still available in the app; try another location.";
            }
            final String detail = result;
            runOnUiThread(() -> { if (!isDestroyed()) { exporting = false; busy(NONE); status.setText(detail); } });
        });
    }
    private void exportAll(Uri tree) {
        final List<StoredItem> items = storedItems(); if (items.isEmpty()) return;
        exporting = true; busy(NONE); status.setText(getResources().getQuantityString(R.plurals.saving_count, items.size(), items.size()));
        disk.execute(() -> {
            Uri created = null; String result;
            try {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                String folder = "Touchbeam-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + "-" + UUID.randomUUID().toString().substring(0, 8);
                created = DocumentsContract.createDocument(getContentResolver(), parent, DocumentsContract.Document.MIME_TYPE_DIR, folder);
                if (created == null) throw new IOException("Cannot create export folder.");
                ArrayList<FileProtocol.Meta> metadata = new ArrayList<>(); for (StoredItem item : items) metadata.add(item.meta);
                List<String> names = BatchProtocol.exportNames(new BatchProtocol.Plan(metadata));
                byte[] buffer = new byte[32768];
                for (int i = 0; i < items.size(); i++) {
                    Uri target = DocumentsContract.createDocument(getContentResolver(), created, items.get(i).meta.mime, names.get(i));
                    if (target == null) throw new IOException("Cannot create saved file.");
                    try (InputStream in = new FileInputStream(items.get(i).file); OutputStream out = getContentResolver().openOutputStream(target, "w")) {
                        if (out == null) throw new IOException("Cannot write saved file.");
                        int count; while ((count = in.read(buffer)) >= 0) { if (Thread.currentThread().isInterrupted()) throw new IOException("Export cancelled."); out.write(buffer, 0, count); }
                        out.flush();
                    }
                    final int saved = i + 1;
                    runOnUiThread(() -> { if (!isDestroyed()) status.setText(getString(R.string.saving_count_progress, saved, items.size())); });
                }
                result = "Saved all " + items.size() + " files in " + folder + ".";
            } catch (IOException | RuntimeException ex) {
                if (created != null) try { DocumentsContract.deleteDocument(getContentResolver(), created); } catch (Exception ignored) {}
                result = "Could not save the whole batch. The verified originals remain in this app; try another folder. A provider may leave an incomplete export folder.";
            }
            final String detail = result;
            runOnUiThread(() -> { if (!isDestroyed()) { exporting = false; busy(NONE); status.setText(detail); } });
        });
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != 100 || pendingAction == null) return;
        if (pendingAction.startsWith("background")) {
            if (!canConnect() || !ReadyService.allowed(this)) { setReadyChecked(false); status.setText(R.string.ready_needs_notifications); return; }
            prepare(Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_ADVERTISE) ? "background_paired" : pendingAction); return;
        }
        if (!canConnect()) { status.setText(R.string.ui_nearby_devices_permission_is_needed_you_can); return; }
        if (pendingAction.startsWith("receive") && Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_ADVERTISE)) prepare("receive_paired");
        else if (pendingAction.startsWith("send") && !canScan()) prepare("send_paired");
        else prepare(pendingAction);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 102) {
            if (!waitingVisibility) return;
            waitingVisibility = false;
            startReceiver(result > 0 ? Math.min(result, 300) : result == RESULT_OK ? 120 : 0);
            return;
        }
        if (request == 200) {
            if (result == RESULT_OK && data != null) {
                ArrayList<Uri> items = new ArrayList<>();
                if (data.getClipData() != null) {
                    if (data.getClipData().getItemCount() > BatchProtocol.MAX_ITEMS) { status.setText(R.string.ui_select_up_to_32_files); return; }
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) if (data.getClipData().getItemAt(i).getUri() != null) items.add(data.getClipData().getItemAt(i).getUri());
                } else if (data.getData() != null) items.add(data.getData());
                if (!items.isEmpty()) { clearAttachment(); loadAttachments(items, data.getType()); }
            }
            return;
        }
        if (request == 201) {
            choosingSave = false;
            if (result == RESULT_OK && data != null && data.getData() != null) exportFile(data.getData());
            busy(NONE); return;
        }
        if (request == 202) { choosingSave = false; if (result == RESULT_OK && data != null && data.getData() != null) exportAll(data.getData()); busy(NONE); return; }
        if (request == 101 && result == RESULT_OK) prepare(pendingAction);
        else if (request == 101) { if (pendingAction != null && pendingAction.startsWith("background")) setReadyChecked(false); status.setText(R.string.ui_turn_on_bluetooth_to_test_a_beam); }
    }
    private void choosePeer() {
        stopNearby(); nearbyDevices.clear(); nearbyReady.clear();
        final int search = ++discoveryGeneration;
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(dp(16), dp(8), dp(16), dp(8));
        nearbyStatus = text(getString(R.string.discovery_searching), 14, 0xffcccccc); layout.addView(nearbyStatus);
        ListView list = new ListView(this); nearbyAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>()); list.setAdapter(nearbyAdapter);
        layout.addView(list, new LinearLayout.LayoutParams(-1, dp(260)));
        nearbyDialog = new AlertDialog.Builder(this).setTitle(R.string.discovery_title).setView(layout)
            .setNegativeButton("Cancel", null).setNeutralButton("Search again", null).create();
        nearbyDialog.setOnDismissListener(d -> stopNearby());
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= nearbyDevices.size()) return;
            BluetoothDevice chosen = nearbyDevices.get(position);
            stopNearby(); armSender(chosen);
        });
        try { for (BluetoothDevice device : bluetooth.getBondedDevices()) if (device.getType() != BluetoothDevice.DEVICE_TYPE_LE) addNearby(device, false); }
        catch (SecurityException ex) { status.setText(R.string.ui_allow_nearby_devices_permission_in_android_settings); }
        nearbyDialog.show();
        nearbyDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> choosePeer());
        discovery = new NearbyDiscovery(this, bluetooth, new NearbyDiscovery.Listener() {
            public void onReady(BluetoothDevice device) { if (search == discoveryGeneration && nearbyDialog != null && device != null) addNearby(device, true); }
            public void onStatus(String value) { if (search == discoveryGeneration && nearbyDialog != null) nearbyStatus.setText(value); }
        });
        discovery.start();
    }
    private void addNearby(BluetoothDevice device, boolean ready) {
        String address = device.getAddress(); int index = -1;
        for (int i = 0; i < nearbyDevices.size(); i++) if (address.equals(nearbyDevices.get(i).getAddress())) { index = i; break; }
        if (index < 0) { if (nearbyDevices.size() >= 64) return; index = nearbyDevices.size(); nearbyDevices.add(device); nearbyReady.add(ready); }
        else if (ready) nearbyReady.set(index, true);
        nearbyAdapter.clear();
        for (int i = 0; i < nearbyDevices.size(); i++) nearbyAdapter.add(deviceName(nearbyDevices.get(i)) + "\n" + (nearbyReady.get(i) ? getString(R.string.discovery_ready) : getString(R.string.discovery_paired)) + " · " + nearbyDevices.get(i).getAddress());
        nearbyAdapter.notifyDataSetChanged();
    }
    private void stopNearby() {
        discoveryGeneration++;
        if (pairlessSearch != null) { pairlessSearch.close(); pairlessSearch = null; }
        if (discovery != null) { discovery.close(); discovery = null; }
        if (nearbyDialog != null) { AlertDialog old = nearbyDialog; nearbyDialog = null; old.setOnDismissListener(null); old.dismiss(); }
    }
    private void choosePairedPeer() {
        try {
        ArrayList<BluetoothDevice> devices = new ArrayList<>();
        for (BluetoothDevice device : bluetooth.getBondedDevices()) if (device.getType() != BluetoothDevice.DEVICE_TYPE_LE) devices.add(device);
        devices.sort(Comparator.comparing(this::deviceName));
        if (devices.isEmpty()) { status.setText(R.string.ui_pair_the_other_phone_in_bluetooth_settings); return; }
        String[] names = new String[devices.size()];
        for (int i = 0; i < names.length; i++) names[i] = deviceName(devices.get(i)) + "\n" + devices.get(i).getAddress();
        new AlertDialog.Builder(this).setTitle("Send to paired phone").setItems(names, (dialog, which) -> armSender(devices.get(which))).setNegativeButton("Cancel", null).show();
        } catch (SecurityException ex) { status.setText(R.string.ui_allow_nearby_devices_permission_in_android_settings); }
    }
    private String deviceName(BluetoothDevice device) {
        try { String name = device.getName(); return name == null || name.isEmpty() ? "Nearby phone" : name; }
        catch (SecurityException ex) { return "Nearby phone"; }
    }
    private void busy(int next) {
        mode = next;
        if (aboutButton != null) aboutButton.setEnabled(next == NONE && !exporting);
        ReadyService.uiBusy(next != NONE || exporting || choosingSave);
        if (backgroundReady != null) backgroundReady.setEnabled(next == NONE && !exporting);
        sendButton.setEnabled(next == NONE && !loadingAttachment && !exporting && !sharedSelectionPending); receiveButton.setEnabled(next == NONE && !loadingAttachment && !exporting);
        photoButton.setEnabled(next == NONE && !loadingAttachment && !exporting); fileButton.setEnabled(next == NONE && !loadingAttachment && !exporting); clearFile.setEnabled(next == NONE && !loadingAttachment && !exporting);
        sharedChooser.setEnabled(next == NONE && !loadingAttachment && !exporting);
        saveFile.setEnabled(next == NONE && !exporting);
        message.setEnabled(next == NONE); cancel.setEnabled(next != NONE);
        fallback.setVisibility(next == ARMED ? View.VISIBLE : View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (next != NONE) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void armSender(BluetoothDevice device) {
        String draft = message.getText().toString();
        if (selectedMetas.isEmpty() && (draft.trim().isEmpty() || draft.getBytes(StandardCharsets.UTF_8).length > Wire.MAX_BYTES)) {
            status.setText(R.string.ui_enter_text_between_1_and_16384_utf); return;
        }
        stopSession(); pendingMessage = draft; peer = device;
        pendingSources.clear();
        for (int i = 0; i < selectedUris.size(); i++) {
            final Uri uri = selectedUris.get(i); final FileProtocol.Meta meta = selectedMetas.get(i);
            pendingSources.add(new FileProtocol.Source() {
                public FileProtocol.Meta meta() { return meta; }
                public InputStream open() throws IOException { return getContentResolver().openInputStream(uri); }
            });
        }
        prefs.edit().putString("draft", draft).apply();
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(message.getWindowToken(), 0);
        busy(ARMED);
        status.setText(peer == null ? getString(R.string.tap_ready) : getString(R.string.receiver_instructions, deviceName(peer)));
        enableReader();
    }
    private void startReceiver(int visibleSeconds) {
        stopSession(); receiverToken = UUID.randomUUID().toString();
        visibleUntil = visibleSeconds > 0 ? SystemClock.elapsedRealtime() + visibleSeconds * 1000L : 0;
        busy(RECEIVE);
        status.setText(R.string.ui_opening_receiver);
        final int id = generation;
        final BeamTransport incoming = new BeamTransport(listener(id, true)); transport = incoming;
        receiverRadio = new TapRadio(bluetooth, receiverToken);
        receiverRadio.start((socket, offer) -> {
            if (id == generation && mode == RECEIVE && transport == incoming) incoming.receive(bluetooth, receiverToken, socket, offer);
        });
    }
    private void connectPeer(String token) { connectPeer(token, false); }
    private void connectPeer(String token, boolean tapped) {
        if (mode != ARMED || peer == null) return;
        try { if (canScan() && bluetooth.isDiscovering()) bluetooth.cancelDiscovery(); } catch (SecurityException ignored) {}
        disableNfc(); busy(CONNECTING); status.setText(getString(R.string.connecting_to, deviceName(peer)));
        transport = new BeamTransport(listener(generation, false)); transport.connect(peer, token, tapped);
    }
    private BeamTransport.Listener listener(final int id, final boolean receiver) {
        return new BeamTransport.Listener() {
            private boolean gotFile, tapAuthorized;
            public void onTapAuthorized() { if (receiver && id == generation) tapAuthorized = true; }
            private int fileCount;
            private void ui(Runnable action) { runOnUiThread(() -> { if (id == generation && !isFinishing() && !isDestroyed()) action.run(); }); }
            public void onListening() { ui(() -> {
                receiverListening = true; setReceiving(true);
                status.setText(receiverRadio != null && receiverRadio.available() ? R.string.pairless_receive_ready : visibleUntil > SystemClock.elapsedRealtime() ? R.string.discovery_receive_ready : R.string.discovery_receive_paired);
                if (visibleUntil > 0) mainHandler.postDelayed(visibilityExpired, Math.max(1, visibleUntil - SystemClock.elapsedRealtime()));
            }); }
            public void onConnecting() { ui(() -> { if (receiver) { receiverListening = false; setReceiving(false); if (receiverRadio != null) receiverRadio.close(); status.setText(R.string.pairless_authenticating); } }); }
            public void onReady(String name) { ui(() -> {
                if (receiver) { if (receiverRadio != null) receiverRadio.close(); receiverListening = false; setReceiving(false); status.setText(getString(R.string.connected_waiting, name)); play(startSound); }
                else { busy(READY); status.setText(getString(R.string.connected_to, name)); play(startSound); vibrate(); showOverlay(); }
            }); }
            public void onReceived(String value) throws IOException {
                if (id != generation || !prefs.edit().putString("received", value).commit()) throw new IOException("Receiver closed.");
                ui(() -> { received.setText(value); copy.setVisibility(View.VISIBLE); });
            }
            public FileProtocol.Sink onFileOffer(FileProtocol.Meta meta) throws IOException {
                if (id != generation) return null;
                if (!tapAuthorized) {
                    CountDownLatch answer = new CountDownLatch(1); AtomicBoolean accepted = new AtomicBoolean();
                    ui(() -> {
                        offerDialog = new AlertDialog.Builder(MainActivity.this).setTitle("Receive file?")
                            .setMessage(meta.name + "\n" + sizeLabel(meta.size) + "\n\nAccept this file from the connected phone?")
                            .setPositiveButton("Accept", (d, w) -> { accepted.set(true); answer.countDown(); })
                            .setNegativeButton("Decline", (d, w) -> answer.countDown())
                            .setOnCancelListener(d -> answer.countDown()).create();
                        offerDialog.show();
                    });
                    try { if (!answer.await(60, TimeUnit.SECONDS)) return null; }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("File offer cancelled.", ex); }
                    finally { ui(() -> { if (offerDialog != null) { offerDialog.dismiss(); offerDialog = null; } }); }
                    if (!accepted.get() || id != generation) return null;
                }
                if (id != generation) return null;
                return receiveIndex().fileSink(meta, () -> id == generation, () -> {
                    gotFile = true; fileCount = 1; ui(() -> refreshReceivedFile());
                });
            }

            public BatchProtocol.Sink onBatchOffer(BatchProtocol.Plan plan) throws IOException {
                if (id != generation) return null;
                if (!tapAuthorized) {
                    CountDownLatch answer = new CountDownLatch(1); AtomicBoolean accepted = new AtomicBoolean();
                    StringBuilder details = new StringBuilder();
                    for (FileProtocol.Meta meta : plan.files) details.append(meta.name).append(" · ").append(sizeLabel(meta.size)).append('\n');
                    details.append("\nTotal: ").append(plan.total < 0 ? "some sizes unknown; maximum 500 MB" : sizeLabel(plan.total));
                    ui(() -> {
                        offerDialog = new AlertDialog.Builder(MainActivity.this).setTitle("Receive " + plan.files.size() + " files?")
                            .setMessage(details.toString()).setPositiveButton("Accept all", (d, w) -> { accepted.set(true); answer.countDown(); })
                            .setNegativeButton("Decline", (d, w) -> answer.countDown()).setOnCancelListener(d -> answer.countDown()).create();
                        offerDialog.show();
                    });
                    try { if (!answer.await(60, TimeUnit.SECONDS)) return null; }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("Batch cancelled.", ex); }
                    finally { ui(() -> { if (offerDialog != null) { offerDialog.dismiss(); offerDialog = null; } }); }
                    if (!accepted.get() || id != generation) return null;
                }
                if (id != generation) return null;
                return receiveIndex().batchSink(plan, () -> id == generation, () -> {
                    gotFile = true; fileCount = plan.files.size(); ui(() -> refreshReceivedFile());
                });
            }

            public void onBatchProgress(int index, int count, String name, long done, long total) { ui(() -> {
                String overall = (total >= 0 ? (total == 0 ? 100 : done * 100 / total) + "% · " : "") + sizeLabel(done) + (total >= 0 ? " / " + sizeLabel(total) : "");
                String detail = "File " + index + " / " + count + " · " + name + "\n" + overall;
                if (receiver) status.setText(getString(R.string.receiving_batch, detail)); else if (overlay != null) overlay.progress(getString(R.string.sending_batch, detail));
            }); }
            public void onProgress(long done, long total) { ui(() -> {
                String detail = (total >= 0 ? (total == 0 ? 100 : done * 100 / total) + "% · " : "") + sizeLabel(done) + (total >= 0 ? " / " + sizeLabel(total) : "");
                if (receiver) status.setText(getString(R.string.receiving_file, detail));
                else if (overlay != null) overlay.progress("Sending · " + detail + (done == total ? "\nWaiting for verification…" : ""));
            }); }
            public void onSuccess() { ui(() -> {
                play(endSound); vibrate();
                if (receiver) { stopSession(); status.setText(gotFile ? getResources().getQuantityString(R.plurals.verified_files, fileCount, fileCount) : getString(R.string.verified_text)); scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, status.getTop() - dp(20)))); Toast.makeText(MainActivity.this, "Beam complete", Toast.LENGTH_SHORT).show(); }
                else if (overlay != null) overlay.finish(() -> { if (id != generation) return; stopSession(); status.setText(R.string.ui_beam_complete_the_other_phone_confirmed_receipt); Toast.makeText(MainActivity.this, "Beam complete", Toast.LENGTH_SHORT).show(); });
            }); }
            public void onError(String error) { ui(() -> { play(errorSound); stopSession(); status.setText(error); }); }
        };
    }
    private void showOverlay() {
        if (root.getWidth() == 0 || root.getHeight() == 0) { stopSession(); status.setText(R.string.ui_screen_not_ready_try_again); return; }
        final Bitmap image;
        try {
            double scale = Math.min(1d, Math.sqrt(2000000d / ((double)root.getWidth() * root.getHeight())));
            image = Bitmap.createBitmap(Math.max(1, (int)(root.getWidth() * scale)), Math.max(1, (int)(root.getHeight() * scale)), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image); canvas.scale(image.getWidth() / (float)root.getWidth(), image.getHeight() / (float)root.getHeight()); scroll.draw(canvas);
        } catch (RuntimeException | OutOfMemoryError ex) { stopSession(); status.setText(R.string.ui_not_enough_memory_for_the_beam_preview); return; }
        overlay = new BeamOverlay(this, image, () -> {
            if (mode != READY || transport == null) return;
            busy(SENDING); vibrate();
            if (pendingSources.isEmpty()) transport.send(pendingMessage);
            else if (pendingSources.size() == 1) transport.sendFile(pendingSources.get(0));
            else transport.sendBatch(pendingSources);
        });
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
    private void removeOverlay() {
        if (overlay != null) { BeamOverlay old = overlay; overlay = null; root.removeView(old); old.dispose(); }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }
    private void play(int id) { if (sound != null && sound.isChecked() && loadedSounds.contains(id)) sounds.play(id, 1, 1, 1, 0, 1); }
    private void vibrate() { Vibrator vibrator = (Vibrator)getSystemService(VIBRATOR_SERVICE); if (vibrator != null && vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE)); }

    private void enableReader() {
        if (!resumed || nfc == null || !nfc.isEnabled()) { status.append("\nNFC is off or unavailable; use Connect without NFC."); return; }
        try {
            nfc.enableReaderMode(this, tag -> {
                if (!readingNfc.compareAndSet(false, true)) return;
                final int id = generation;
                IsoDep card = IsoDep.get(tag);
                try {
                    if (card == null) return;
                    card.connect(); card.setTimeout(2500);
                    byte[] response = card.transceive(BeamCardService.SELECT);
                    if (response.length < 2 || response[response.length - 2] != (byte)0x90 || response[response.length - 1] != 0) return;
                    String hello = new String(response, 0, response.length - 2, StandardCharsets.US_ASCII);
                    if (!hello.startsWith(Wire.HELLO) || hello.length() != Wire.HELLO.length() + 36) return;
                    String token = hello.substring(Wire.HELLO.length());
                    NfcSession.serviceUuid(token);
                    TapOffer handover = null;
                    if (Build.VERSION.SDK_INT >= 29) {
                        byte[] extra = card.transceive(BeamCardService.PAIRLESS);
                        if (extra.length >= 2 && extra[extra.length - 2] == (byte)0x90 && extra[extra.length - 1] == 0) {
                            handover = TapOffer.parse(new String(extra, 0, extra.length - 2, StandardCharsets.US_ASCII));
                            if (!token.equals(handover.token)) throw new IllegalArgumentException("Tap identity changed");
                        }
                    }
                    final TapOffer offer = handover;
                    runOnUiThread(() -> {
                        if (id == generation && mode == ARMED && resumed && !resolvingTap) {
                            if (peer == null && offer != null) findPairlessPhone(offer);
                            else if (peer == null) findTappedPhone(token); else connectPeer(token);
                        }
                    });
                } catch (IOException | IllegalArgumentException ignored) {
                    runOnUiThread(() -> { if (id == generation && mode == ARMED) status.setText(R.string.ui_nfc_touch_interrupted_hold_the_phone_backs); });
                } finally { try { if (card != null) card.close(); } catch (IOException ignored) {} readingNfc.set(false); }
            }, NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, null);
        } catch (RuntimeException ex) { status.append("\nUse Connect without NFC on this phone."); }
    }
    private void findPairlessPhone(TapOffer offer) {
        stopNearby(); resolvingTap = true; disableNfc(); vibrate();
        final int id = generation, search = discoveryGeneration;
        status.setText(R.string.pairless_finding);
        pairlessSearch = new TapRadio.Search(bluetooth, offer, new TapRadio.Search.Listener() {
            public void found(BluetoothDevice device) {
                if (id != generation || search != discoveryGeneration || mode != ARMED) return;
                if (!resumed) { resolvingTap = false; status.setText(R.string.pairless_failed); return; }
                stopNearby(); resolvingTap = false; peer = device; disableNfc(); busy(CONNECTING); status.setText(R.string.pairless_connecting);
                try {
                    BluetoothSocket socket = TapRadio.socket(device, offer.psm);
                    transport = new BeamTransport(listener(generation, false)); transport.connectPairless(socket, offer);
                } catch (IOException | SecurityException ex) { stopSession(); status.setText(R.string.pairless_failed); }
            }
            public void failed() {
                if (id == generation && search == discoveryGeneration && mode == ARMED) {
                    resolvingTap = false; status.setText(R.string.pairless_failed); if (resumed) enableReader();
                }
            }
        }); pairlessSearch.start();
    }
    private void findTappedPhone(String token) {
        stopNearby(); resolvingTap = true; disableNfc(); vibrate();
        final int id = generation, search = discoveryGeneration;
        status.setText(R.string.tap_finding);
        discovery = new NearbyDiscovery(this, bluetooth, NfcSession.serviceUuid(token), new NearbyDiscovery.Listener() {
            private boolean found;
            public void onReady(BluetoothDevice device) {
                if (!found && id == generation && search == discoveryGeneration && mode == ARMED && resumed) {
                    found = true; peer = device; stopNearby(); resolvingTap = false; connectPeer(token, true);
                }
            }
            public void onStatus(String value) {
                if (id == generation && search == discoveryGeneration && mode == ARMED && !found)
                    status.setText(getString(R.string.tap_search_status, value));
            }
            public void onFinished() {
                if (!found && id == generation && search == discoveryGeneration && mode == ARMED) {
                    resolvingTap = false; status.setText(R.string.tap_not_found); if (resumed) enableReader();
                }
            }
        });
        discovery.start();
    }
    private void setReceiving(boolean active) {
        if (active && resumed) { NfcSession.enable(receiverToken, SystemClock.elapsedRealtime()); if (receiverRadio != null) receiverRadio.publish(); } else NfcSession.clear();
        if (nfc != null && resumed && active) try { CardEmulation.getInstance(nfc).setPreferredService(this, new ComponentName(this, BeamCardService.class)); } catch (RuntimeException ignored) {}
        if (nfc != null && !active) try { CardEmulation.getInstance(nfc).unsetPreferredService(this); } catch (RuntimeException ignored) {}
    }
    private void disableNfc() {
        setReceiving(false);
        if (nfc != null) try { nfc.disableReaderMode(this); } catch (RuntimeException ignored) {}
    }
    private void stopSession() {
        stopNearby(); resolvingTap = false; peer = null; waitingVisibility = false; pendingAction = null;
        mainHandler.removeCallbacks(pairingExpired); mainHandler.removeCallbacks(visibilityExpired); pairing.clear(); visibleUntil = 0;
        generation++; receiverListening = false; disableNfc();
        if (receiverRadio != null) { receiverRadio.close(); receiverRadio = null; }
        if (transport != null) { ReadyService.closeTransport(transport); transport = null; }
        if (offerDialog != null) { offerDialog.dismiss(); offerDialog = null; }
        removeOverlay(); busy(NONE);
    }
    @Override protected void onResume() { super.onResume(); resumed = true; if (aboutDialog != null) aboutDialog.resumeParticles(); ReadyService.uiVisible(true); ensureBackgroundReady(); received.setText(prefs.getString("received", "")); copy.setVisibility(received.length() == 0 ? View.GONE : View.VISIBLE); refreshReceivedFile(); if (backgroundOffer == null) backgroundOffer = ReadyService.showOffer(this); pairing.resumed(); mainHandler.removeCallbacks(pairingExpired); if (mode == ARMED && !resolvingTap) enableReader(); if (mode == RECEIVE && receiverListening) setReceiving(true); }
    @Override protected void onPause() { resumed = false; if (aboutDialog != null) aboutDialog.pauseParticles(); disableNfc(); super.onPause(); }
    @Override protected void onStop() {
        stopNearby();
        String address = peer == null ? null : peer.getAddress();
        if (mode != NONE && pairing.retain(mode == RECEIVE && receiverListening, mode == CONNECTING, address, SystemClock.elapsedRealtime())) {
            mainHandler.postDelayed(pairingExpired, 90000); status.setText(R.string.discovery_pairing_pending);
        } else if (mode != NONE) { stopSession(); status.setText(R.string.ui_session_closed_when_the_app_left_the); }
        if (backgroundOffer != null) { backgroundOffer.dismiss(); backgroundOffer = null; }
        ReadyService.uiVisible(false);
        super.onStop();
    }
    @Override protected void onDestroy() { stopSession(); if (aboutDialog != null) { aboutDialog.dismiss(); aboutDialog = null; } prefs.unregisterOnSharedPreferenceChangeListener(receiveChanges); if (bondReceiverRegistered) { unregisterReceiver(bondReceiver); bondReceiverRegistered = false; } mainHandler.removeCallbacksAndMessages(null); if (sharedDialog != null) sharedDialog.dismiss(); disk.shutdownNow(); ReadyService.uiBusy(false); sounds.release(); photoPreview.setImageDrawable(null); previewBitmap = null; super.onDestroy(); }
    @Override public void onBackPressed() { if (mode != NONE) { stopSession(); status.setText(R.string.ui_beam_cancelled); } else super.onBackPressed(); }
}
