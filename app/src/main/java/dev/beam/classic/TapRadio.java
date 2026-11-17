package dev.beam.classic;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.*;
import java.io.*;
import java.util.*;

/** Android 10+ unbonded LE CoC rendezvous; no Classic address or visibility needed. */
final class TapRadio implements AutoCloseable {
    interface Receiver { void ready(BluetoothServerSocket socket, TapOffer offer); }
    private final BluetoothAdapter adapter;
    private final String token;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile BluetoothServerSocket server;
    private BluetoothLeAdvertiser advertiser;
    private volatile boolean closed;
    private boolean reported;
    private TapOffer offer;
    private Receiver receiver;
    TapRadio(BluetoothAdapter adapter, String token) { this.adapter = adapter; this.token = token; }
    private final AdvertiseCallback callback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settings) {
            if (closed || reported) { stopAdvertising(); return; }
            try {
                if (Build.VERSION.SDK_INT < 29) { fallback(); return; }
                offer = TapOffer.create(token, server.getPsm()); reported = true; main.removeCallbacksAndMessages(null); receiver.ready(server, offer);
            } catch (RuntimeException ex) { fallback(); }
        }
        @Override public void onStartFailure(int error) { fallback(); }
    };
    static boolean possible(BluetoothAdapter adapter) {
        try { return Build.VERSION.SDK_INT >= 29 && adapter != null && adapter.isMultipleAdvertisementSupported(); }
        catch (SecurityException ex) { return false; }
    }
    void start(Receiver receiver) {
        this.receiver = receiver;
        main.postDelayed(this::fallback, 6000);
        Thread setup = new Thread(() -> {
            BluetoothServerSocket opened = null;
            try {
                if (Build.VERSION.SDK_INT >= 29 && adapter != null && adapter.isMultipleAdvertisementSupported()) opened = adapter.listenUsingInsecureL2capChannel();
            } catch (SecurityException ex) { /* The Classic receiver remains available. */ }
            catch (IOException | RuntimeException ex) { /* Unsupported firmware: keep Classic. */ }
            final BluetoothServerSocket listening = opened;
            main.post(() -> {
                if (closed || reported) { closeSocket(listening); return; }
                server = listening;
                if (listening == null) { fallback(); return; }
                try {
                    advertiser = adapter.getBluetoothLeAdvertiser();
                    if (advertiser == null) { fallback(); return; }
                    AdvertiseData data = new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(NfcSession.serviceUuid(token))).build();
                    AdvertiseSettings settings = new AdvertiseSettings.Builder().setConnectable(true).setTimeout(0)
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build();
                    advertiser.startAdvertising(settings, data, callback);
                } catch (SecurityException ex) { fallback(); }
                catch (RuntimeException ex) { fallback(); }
            });
        }, "Touchbeam LE setup"); setup.setDaemon(true); setup.start();
    }
    boolean available() { return !closed && offer != null; }
    void publish() { if (available()) NfcSession.publishOffer(offer); }
    private void fallback() {
        if (closed || reported) return;
        reported = true; main.removeCallbacksAndMessages(null); stopAdvertising();
        closeSocket(server); server = null; receiver.ready(null, null);
    }
    private void stopAdvertising() {
        if (advertiser != null) try { advertiser.stopAdvertising(callback); } catch (SecurityException ignored) {} catch (RuntimeException ignored) {}
    }
    private static void closeSocket(BluetoothServerSocket socket) {
        if (socket == null) return;
        Thread close = new Thread(() -> { try { socket.close(); } catch (IOException | RuntimeException ignored) {} }, "Touchbeam LE cleanup"); close.setDaemon(true); close.start();
    }
    @Override public void close() {
        closed = true; main.removeCallbacksAndMessages(null); stopAdvertising(); NfcSession.clearOffer(token); offer = null;
        closeSocket(server); server = null;
    }
    static BluetoothSocket socket(BluetoothDevice device, int psm) throws IOException {
        if (Build.VERSION.SDK_INT < 29) throw new IOException("Pair-free taps need Android 10 or newer.");
        try { return device.createInsecureL2capChannel(psm); }
        catch (SecurityException ex) { throw new IOException("Nearby devices permission is missing.", ex); }
    }

    static final class Search implements AutoCloseable {
        interface Listener { void found(BluetoothDevice device); void failed(); }
        private final BluetoothAdapter adapter;
        private final TapOffer offer;
        private final Listener listener;
        private final Handler main = new Handler(Looper.getMainLooper());
        private BluetoothLeScanner scanner;
        private boolean closed;
        Search(BluetoothAdapter adapter, TapOffer offer, Listener listener) { this.adapter = adapter; this.offer = offer; this.listener = listener; }
        private final ScanCallback callback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) { main.post(() -> {
                if (closed || result == null || result.getScanRecord() == null) return;
                List<ParcelUuid> uuids = result.getScanRecord().getServiceUuids();
                if (uuids != null && uuids.contains(new ParcelUuid(NfcSession.serviceUuid(offer.token)))) { close(); listener.found(result.getDevice()); }
            }); }
            @Override public void onBatchScanResults(List<ScanResult> results) { for (ScanResult result : results) onScanResult(0, result); }
            @Override public void onScanFailed(int error) { main.post(Search.this::fail); }
        };
        void start() {
            if (Build.VERSION.SDK_INT < 29) { fail(); return; }
            try {
                scanner = adapter.getBluetoothLeScanner();
                if (scanner == null) { fail(); return; }
                ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(NfcSession.serviceUuid(offer.token))).build();
                scanner.startScan(Collections.singletonList(filter), new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback);
                main.postDelayed(this::fail, 25000);
            } catch (SecurityException ex) { fail(); }
            catch (RuntimeException ex) { fail(); }
        }
        private void fail() { if (!closed) { close(); listener.failed(); } }
        @Override public void close() {
            if (closed) return; closed = true; main.removeCallbacksAndMessages(null);
            if (scanner != null) try { scanner.stopScan(callback); } catch (SecurityException ignored) {} catch (RuntimeException ignored) {}
        }
    }
}
