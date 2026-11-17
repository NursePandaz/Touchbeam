package dev.beam.classic;

import android.bluetooth.*;
import android.content.*;
import android.os.*;
import java.util.*;

/** Foreground-only Classic Bluetooth inquiry followed by fresh Touchbeam SDP checks. */
final class NearbyDiscovery implements AutoCloseable {
    interface Listener {
        void onReady(BluetoothDevice device);
        void onStatus(String text);
        default void onFinished() {}
    }
    private final Context context;
    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final UUID target;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DiscoveryQueue queue = new DiscoveryQueue();
    private final Map<String, BluetoothDevice> devices = new HashMap<>();
    private boolean registered, closed, ownInquiry, probing;
    NearbyDiscovery(Context context, BluetoothAdapter adapter, Listener listener) {
        this(context, adapter, BeamTransport.SERVICE, listener);
    }
    NearbyDiscovery(Context context, BluetoothAdapter adapter, UUID target, Listener listener) {
        this.context = context.getApplicationContext(); this.adapter = adapter; this.target = target; this.listener = listener;
    }
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (closed) return;
            try {
                if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null) add(device, true);
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) beginProbes();
                else if (BluetoothDevice.ACTION_UUID.equals(intent.getAction())) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    DiscoveryQueue.Request request = queue.active();
                    if (device == null || request == null || !request.address.equals(device.getAddress())) return;
                    boolean ready = false;
                    Parcelable[] uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
                    if (uuids != null) for (Parcelable uuid : uuids) if (uuid instanceof ParcelUuid && target.equals(((ParcelUuid)uuid).getUuid())) ready = true;
                    if (queue.finish(request.address, request.ticket)) {
                        if (ready) listener.onReady(devices.get(request.address));
                        probeNext();
                    }
                }
            } catch (SecurityException ex) { unavailable(); }
            catch (RuntimeException ex) { listener.onStatus("Discovery could not read a device. Paired phones remain available."); }
        }
    };
    void start() {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_FOUND); filter.addAction(BluetoothDevice.ACTION_UUID);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            // Bluetooth broadcasts originate from a privileged system process, not
            // necessarily the system UID. The platform protects these action names.
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(receiver, filter);
            registered = true;
            int seeded = 0;
            for (BluetoothDevice device : adapter.getBondedDevices()) { if (seeded++ >= (target.equals(BeamTransport.SERVICE) ? 8 : DiscoveryQueue.LIMIT)) break; add(device, false); }
            listener.onStatus(target.equals(BeamTransport.SERVICE) ? "Looking nearby… The other person can use Receive with visibility, or background readiness for a paired phone." : "Looking for the tapped receiver. Keep its ready notification active, or keep Receive open. New phones need temporary Bluetooth visibility.");
            boolean inProgress = adapter.isDiscovering();
            ownInquiry = !inProgress && adapter.startDiscovery();
            if (!inProgress && !ownInquiry) {
                listener.onStatus("Inquiry could not start. On Android 8–11, check Location is on. Checking paired phones instead…");
                beginProbes();
            } else handler.postDelayed(this::beginProbes, 18000);
            handler.postDelayed(() -> {
                if (!closed) { listener.onStatus("Search finished. Tap Search again, or select a paired phone. Nearby visibility on the receiver lasts about two minutes."); finish(); }
            }, 75000);
        } catch (SecurityException ex) { unavailable(); }
        catch (RuntimeException ex) { listener.onStatus("Nearby search is unavailable. You can still use a paired phone."); finish(); }
    }
    private void add(BluetoothDevice device, boolean nearby) {
        try {
            if (device.getType() == BluetoothDevice.DEVICE_TYPE_LE) return;
            BluetoothClass kind = device.getBluetoothClass();
            if (kind != null) {
                int major = kind.getMajorDeviceClass();
                if (major != BluetoothClass.Device.Major.PHONE && major != BluetoothClass.Device.Major.COMPUTER && major != BluetoothClass.Device.Major.UNCATEGORIZED) return;
            }
            String address = device.getAddress();
            if (queue.add(address)) devices.put(address, device);
            if (nearby) queue.prioritize(address);
            if (probing && queue.active() == null) handler.post(this::probeNext);
        } catch (SecurityException ex) { unavailable(); }
    }
    private void beginProbes() {
        if (closed || probing) return;
        probing = true;
        try { if (ownInquiry && adapter.isDiscovering()) adapter.cancelDiscovery(); }
        catch (SecurityException ex) { unavailable(); return; }
        listener.onStatus("Checking which phones have Touchbeam ready…"); probeNext();
    }
    private void probeNext() {
        if (closed || queue.active() != null) return;
        DiscoveryQueue.Request request = queue.next();
        if (request == null) {
            listener.onStatus(target.equals(BeamTransport.SERVICE) ? "Ready phones are marked Nearby Touchbeam. Paired phones can also be selected. Tap Search again if the receiver just became visible." : "No matching service yet. Check the receiver’s ready notification. New phones need temporary visibility. You can tap again after this search finishes, or choose manually.");
            return;
        }
        try {
            BluetoothDevice device = devices.get(request.address);
            if (device == null || !device.fetchUuidsWithSdp()) {
                queue.finish(request.address, request.ticket); handler.post(this::probeNext); return;
            }
            handler.postDelayed(() -> {
                if (!closed && queue.finish(request.address, request.ticket)) probeNext();
            }, 5000);
        } catch (SecurityException ex) { unavailable(); }
        catch (RuntimeException ex) { queue.finish(request.address, request.ticket); handler.post(this::probeNext); }
    }
    private void unavailable() {
        listener.onStatus("Nearby devices permission is missing. Use a paired phone or allow discovery permission and search again."); finish();
    }
    private void finish() { close(); listener.onFinished(); }
    @Override public void close() {
        if (closed) return;
        closed = true; queue.close(); handler.removeCallbacksAndMessages(null);
        if (registered) { try { context.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {} registered = false; }
        try { if (ownInquiry && adapter.isDiscovering()) adapter.cancelDiscovery(); } catch (SecurityException ignored) {}
    }
}
