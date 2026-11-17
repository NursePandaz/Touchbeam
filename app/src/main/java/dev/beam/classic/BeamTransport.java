package dev.beam.classic;

import android.bluetooth.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** One foreground-only, encrypted Bluetooth RFCOMM session. */
public final class BeamTransport implements AutoCloseable {
    public interface Listener {
        void onListening();
        default void onConnecting() {}
        default void onTapAuthorized() {}
        void onReady(String peer);
        void onReceived(String text) throws IOException;
        default FileProtocol.Sink onFileOffer(FileProtocol.Meta meta) throws IOException { return null; }
        default void onProgress(long done, long total) {}
        default BatchProtocol.Sink onBatchOffer(BatchProtocol.Plan plan) throws IOException { return null; }
        default void onBatchProgress(int index, int count, String name, long done, long total) {}
        void onSuccess();
        void onError(String message);
    }
    static final UUID SERVICE = UUID.fromString("83f80119-faa5-4a3d-b7bd-927c8a23b274");
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledThreadPoolExecutor clock = new ScheduledThreadPoolExecutor(1);
    private final Listener listener;
    private volatile BluetoothSocket socket;
    private volatile BluetoothServerSocket server, tapServer, leServer;
    private volatile InputStream linkInput;
    private volatile OutputStream linkOutput;
    private volatile TapChannel channel;
    private final ExecutorService acceptors = Executors.newFixedThreadPool(3);
    private volatile boolean closed;
    private volatile InputStream fileInput;
    private volatile FileProtocol.Sink fileOutput;
    private volatile BatchProtocol.Sink batchOutput;
    private long lastProgress;
    private final SessionGate gate = new SessionGate();
    private ScheduledFuture<?> timeout;
    public BeamTransport(Listener listener) { this.listener = listener; clock.setRemoveOnCancelPolicy(true); }
    private void fileProgress(long done, long total) throws IOException {
        if (closed) throw new IOException("Transfer cancelled.");
        long now = System.nanoTime();
        if (done == 0 || done == total || now - lastProgress > 250000000L) {
            lastProgress = now;
            deadline(45, "File transfer stalled. Check the other phone and retry.");
            listener.onProgress(done, total);
        }
    }
    private void batchProgress(BatchProtocol.Plan plan, int index, long base, long done, long fileTotal) throws IOException {
        if (closed) throw new IOException("Batch cancelled.");
        long now = System.nanoTime();
        if (done == 0 || done == fileTotal || now - lastProgress > 250000000L) {
            lastProgress = now; deadline(45, "Batch transfer stalled. Keep both apps open and retry.");
            listener.onBatchProgress(index + 1, plan.files.size(), plan.files.get(index).name, base + done, plan.total);
        }
    }

    private synchronized void deadline(int seconds, String message) {
        if (closed || !gate.active()) return;
        if (timeout != null) timeout.cancel(false);
        final long ticket = gate.arm();
        timeout = clock.schedule(() -> {
            if (gate.expire(ticket)) { disconnect(false); listener.onError(message); }
        }, seconds, TimeUnit.SECONDS);
    }
    private synchronized void cancelDeadline() { gate.disarm(); if (timeout != null) timeout.cancel(false); }
    private void fail(String message) {
        if (gate.fail()) { disconnect(false); listener.onError(message); }
    }
    private void complete() {
        if (gate.succeed()) { disconnect(false); listener.onSuccess(); }
    }
    private void job(Runnable task) {
        if (closed) return;
        try { worker.execute(() -> { if (!closed) task.run(); }); }
        catch (RejectedExecutionException ignored) {}
    }

    public void receive(BluetoothAdapter adapter, String token) { receive(adapter, token, null, null); }
    public void receive(BluetoothAdapter adapter, String token, BluetoothServerSocket pairless, TapOffer offer) {
        if (pairless != null && (offer == null || !token.equals(offer.token))) {
            try { pairless.close(); } catch (IOException ignored) {} fail("Invalid tap handover."); return;
        }
        synchronized (this) {
            if (closed) { if (pairless != null) try { pairless.close(); } catch (IOException ignored) {} return; }
            leServer = pairless;
        }
        job(() -> {
            try {
                BluetoothServerSocket listening = adapter.listenUsingRfcommWithServiceRecord("Touchbeam", SERVICE);
                synchronized (this) { if (closed) { listening.close(); return; } server = listening; }
                BluetoothServerSocket tapped = adapter.listenUsingRfcommWithServiceRecord("Touchbeam tap", NfcSession.serviceUuid(token));
                synchronized (this) { if (closed) { tapped.close(); return; } tapServer = tapped; }
                BlockingQueue<Accepted> incomingSockets = new LinkedBlockingQueue<>();
                acceptors.execute(() -> acceptInto(listening, incomingSockets, false));
                acceptors.execute(() -> acceptInto(tapped, incomingSockets, false));
                if (pairless != null) acceptors.execute(() -> acceptInto(pairless, incomingSockets, true));
                deadline(300, "Receiver timed out. Tap Receive to try again.");
                listener.onListening();
                Accepted accepted;
                try { accepted = incomingSockets.take(); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
                listening.close(); tapped.close(); if (pairless != null) pairless.close();
                server = null; tapServer = null; leServer = null;
                acceptors.shutdownNow();
                listener.onConnecting();
                deadline(25, "The tapping phone did not finish connecting. Try another tap.");
                initialize(accepted.pairless ? offer : null, false);
                if (accepted.pairless && !closed && gate.active()) listener.onTapAuthorized();
                Wire.write(linkOutput, Wire.HELLO + token);
                String name = socket.getRemoteDevice().getName(); listener.onReady(name == null || name.isEmpty() ? "Tapped phone" : name);
                deadline(90, "No message arrived. Start another beam.");
                InputStream input = linkInput;
                OutputStream output = linkOutput;
                String kind = Wire.read(input);
                if ("TEXT".equals(kind)) {
                    String text = Wire.read(input);
                    listener.onReceived(text);
                    Wire.write(output, Wire.receipt(text));
                } else if ("FILE".equals(kind)) {
                    FileProtocol.Meta meta = FileProtocol.readMeta(input);
                    deadline(90, "File offer expired. Start another beam.");
                    FileProtocol.Sink incoming = listener.onFileOffer(meta);
                    if (incoming == null) { Wire.write(output, "REJECT"); fail("File offer declined or expired."); return; }
                    synchronized (this) { if (closed) { incoming.close(); return; } fileOutput = incoming; }
                    Wire.write(output, "ACCEPT");
                    FileProtocol.Result result = FileProtocol.receive(input, incoming.stream(), meta, this::fileProgress);
                    deadline(45, "Could not finish saving the file.");
                    incoming.commit(result);
                    Wire.write(output, result.receipt());
                    incoming.close(); fileOutput = null;
                } else if ("BATCH".equals(kind)) {
                    BatchProtocol.Plan plan = BatchProtocol.read(input);
                    deadline(90, "Batch offer expired.");
                    BatchProtocol.Sink incoming = listener.onBatchOffer(plan);
                    if (incoming == null) { Wire.write(output, "REJECT"); fail("Batch declined or expired."); return; }
                    synchronized (this) { if (closed) { incoming.close(); return; } batchOutput = incoming; }
                    Wire.write(output, "ACCEPT");
                    ArrayList<FileProtocol.Result> results = new ArrayList<>(); long bytes = 0;
                    for (int i = 0; i < plan.files.size(); i++) {
                        final int index = i; final long base = bytes;
                        FileProtocol.Result result = FileProtocol.receive(input, incoming.open(i), plan.files.get(i),
                            (done, total) -> batchProgress(plan, index, base, done, total), BatchProtocol.MAX_TOTAL - bytes);
                        incoming.finishFile(i, result); results.add(result); bytes += result.bytes;
                    }
                    String receipt = BatchProtocol.receipt(plan, results);
                    if (!("END:" + receipt).equals(Wire.read(input))) throw new IOException("Batch manifest or receipt mismatch.");
                    deadline(45, "Could not finish saving the batch.");
                    incoming.commit(results);
                    Wire.write(output, receipt); incoming.close(); batchOutput = null;
                } else throw new IOException("Unsupported transfer type.");
                complete();
            } catch (SecurityException ex) { fail("Nearby devices permission was removed. Allow it in Android settings and retry."); }
            catch (IOException | RuntimeException ex) { fail("Connection ended. Keep both apps open, check Bluetooth, and retry."); }
        });
    }

    private static final class Accepted {
        final boolean pairless;
        Accepted(boolean pairless) { this.pairless = pairless; }
    }
    private void acceptInto(BluetoothServerSocket listening, BlockingQueue<Accepted> incoming, boolean pairless) {
        try {
            BluetoothSocket accepted = listening.accept();
            synchronized (this) {
                if (closed || socket != null) { accepted.close(); return; }
                socket = accepted; incoming.offer(new Accepted(pairless));
            }
        } catch (IOException | RuntimeException ignored) { /* The other listener or receiver deadline remains active. */ }
    }

    public void connect(BluetoothDevice device, String nfcToken) { connect(device, nfcToken, false); }
    public void connect(BluetoothDevice device, String nfcToken, boolean tapped) {
        job(() -> {
            try {
                BluetoothSocket connecting = device.createRfcommSocketToServiceRecord(tapped ? NfcSession.serviceUuid(nfcToken) : SERVICE);
                synchronized (this) { if (closed) { connecting.close(); return; } socket = connecting; }
                deadline(device.getBondState() == BluetoothDevice.BOND_BONDED ? 25 : 90, "Could not connect. Approve Android pairing on both phones and check Receive mode.");
                socket.connect();
                deadline(25, "Connected, but the other phone did not answer. Open Receive and retry.");
                initialize(null, true);
                String hello = Wire.read(linkInput);
                if (!hello.startsWith(Wire.HELLO) || hello.length() != Wire.HELLO.length() + 36) {
                    fail("Update Touchbeam on both phones to the latest test build, then retry."); return;
                }
                if (nfcToken != null && !hello.equals(Wire.HELLO + nfcToken)) {
                    fail("The tapped phone differs from the selected Bluetooth phone. Select the correct receiver.");
                    return;
                }
                deadline(90, "Beam expired. Reconnect to send.");
                String name = device.getName(); listener.onReady(name == null || name.isEmpty() ? "Tapped phone" : name);
            } catch (SecurityException ex) { fail("Nearby devices permission was removed. Allow it in Android settings and retry."); }
            catch (IOException | RuntimeException ex) { fail("Could not connect. Pair the phones in Bluetooth settings and open Receive on the other phone."); }
        });
    }


    private void initialize(TapOffer offer, boolean client) throws IOException {
        InputStream input = socket.getInputStream(); OutputStream output = socket.getOutputStream();
        if (offer != null) {
            TapChannel secure = TapChannel.open(input, output, offer, client);
            synchronized (this) { if (closed) { secure.close(); throw new IOException("Cancelled."); } channel = secure; }
            input = secure.input; output = new BufferedOutputStream(secure.output, TapChannel.BLOCK);
        }
        linkInput = input; linkOutput = output;
    }
    public void connectPairless(BluetoothSocket connecting, TapOffer offer) {
        synchronized (this) { if (closed) { try { connecting.close(); } catch (IOException ignored) {} return; } socket = connecting; }
        job(() -> {
            try {
                deadline(25, "Pair-free connection timed out. Keep the receiver ready and tap again.");
                connecting.connect();
                deadline(25, "The tapped receiver did not answer. Tap again.");
                initialize(offer, true);
                if (!(Wire.HELLO + offer.token).equals(Wire.read(linkInput))) throw new IOException("Wrong tapped receiver.");
                deadline(90, "Beam expired. Reconnect to send.");
                String name = connecting.getRemoteDevice().getName(); listener.onReady(name == null || name.isEmpty() ? "Tapped phone" : name);
            } catch (SecurityException ex) { fail("Allow Nearby devices permission and tap again."); }
            catch (IOException | RuntimeException ex) { fail("Pair-free beam could not connect. Keep the receiver ready and tap again, or choose a phone manually."); }
        });
    }

    public void send(String message) {
        job(() -> {
            try {
                if (socket == null || !socket.isConnected()) throw new IOException("Disconnected.");
                deadline(20, "No receipt arrived. Check the other phone before retrying.");
                Wire.write(linkOutput, "TEXT");
                Wire.write(linkOutput, message);
                String receipt = Wire.read(linkInput);
                if (!Wire.receipt(message).equals(receipt)) throw new IOException("Receipt mismatch.");
                complete();
            } catch (IOException | RuntimeException ex) { fail("Beam did not complete. Check the other phone before retrying."); }
        });
    }
    public void sendFile(FileProtocol.Source source) {
        job(() -> {
            try {
                if (socket == null || !socket.isConnected()) throw new IOException("Disconnected.");
                InputStream input = linkInput; OutputStream output = linkOutput;
                FileProtocol.Meta meta = source.meta();
                deadline(90, "The other phone did not accept the file.");
                Wire.write(output, "FILE"); FileProtocol.writeMeta(output, meta);
                if (!"ACCEPT".equals(Wire.read(input))) { fail("The receiving phone declined the file."); return; }
                deadline(45, "Could not read the selected file.");
                InputStream opened = source.open();
                if (opened == null) throw new IOException("Cannot open file.");
                synchronized (this) { if (closed) { opened.close(); return; } fileInput = opened; }
                FileProtocol.Result result;
                try { result = FileProtocol.send(opened, output, meta, this::fileProgress); }
                finally { opened.close(); fileInput = null; }
                deadline(45, "No receipt arrived. Check the receiving phone before resending.");
                if (!result.receipt().equals(Wire.read(input))) throw new IOException("File receipt mismatch.");
                complete();
            } catch (IOException | RuntimeException ex) { fail("File beam did not complete. Check storage, keep both apps open, and reselect the file if needed."); }
        });
    }
    public void sendBatch(List<FileProtocol.Source> sources) {
        if (sources == null || sources.isEmpty() || sources.size() > BatchProtocol.MAX_ITEMS) { fail("Choose 1–32 files."); return; }
        final List<FileProtocol.Source> batch = new ArrayList<>(sources);
        job(() -> {
            try {
                if (socket == null || !socket.isConnected()) throw new IOException("Disconnected.");
                ArrayList<FileProtocol.Meta> metadata = new ArrayList<>();
                for (FileProtocol.Source source : batch) metadata.add(source.meta());
                BatchProtocol.Plan plan = new BatchProtocol.Plan(metadata);
                InputStream input = linkInput; OutputStream output = linkOutput;
                deadline(90, "The other phone did not accept the batch.");
                Wire.write(output, "BATCH"); BatchProtocol.write(output, plan);
                if (!"ACCEPT".equals(Wire.read(input))) { fail("The receiving phone declined the batch."); return; }
                ArrayList<FileProtocol.Result> results = new ArrayList<>(); long bytes = 0;
                for (int i = 0; i < batch.size(); i++) {
                    final int index = i; final long base = bytes;
                    deadline(45, "Could not read a selected file.");
                    InputStream opened = batch.get(i).open();
                    if (opened == null) throw new IOException("Cannot open file.");
                    synchronized (this) { if (closed) { opened.close(); return; } fileInput = opened; }
                    FileProtocol.Result result;
                    try { result = FileProtocol.send(opened, output, plan.files.get(i),
                        (done, total) -> batchProgress(plan, index, base, done, total), BatchProtocol.MAX_TOTAL - bytes); }
                    finally { opened.close(); fileInput = null; }
                    results.add(result); bytes += result.bytes;
                }
                String receipt = BatchProtocol.receipt(plan, results);
                Wire.write(output, "END:" + receipt);
                deadline(45, "No batch receipt arrived. Check the other phone before resending.");
                if (!receipt.equals(Wire.read(input))) throw new IOException("Batch receipt mismatch.");
                complete();
            } catch (IOException | RuntimeException ex) { fail("Batch did not complete. No partial batch is kept. Check the other phone before retrying."); }
        });
    }
    public void cancelAsync() { gate.cancel(); disconnect(true); }
    public void cancelAsync(Runnable after) {
        gate.cancel();
        Thread cleanup = new Thread(() -> { try { disconnect(false); } finally { after.run(); } }, "Beam cleanup");
        cleanup.setDaemon(true); cleanup.start();
    }
    @Override public void close() { gate.cancel(); disconnect(false); }
    private void disconnect(boolean background) {
        final Closeable[] resources;
        synchronized (this) {
            if (closed) return;
            closed = true; cancelDeadline();
            resources = new Closeable[]{server, tapServer, leServer, socket, channel, fileInput, fileOutput, batchOutput};
            server = null; tapServer = null; leServer = null; socket = null; channel = null; linkInput = null; linkOutput = null; fileInput = null; fileOutput = null; batchOutput = null;
        }
        clock.shutdownNow(); worker.shutdownNow(); acceptors.shutdownNow();
        Runnable cleanup = () -> { for (Closeable resource : resources) if (resource != null) try { resource.close(); } catch (IOException | RuntimeException ignored) {} };
        if (background) { Thread thread = new Thread(cleanup, "Beam cleanup"); thread.setDaemon(true); thread.start(); }
        else cleanup.run();
    }
}
