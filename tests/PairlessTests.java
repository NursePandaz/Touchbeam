package dev.beam.classic;

import android.bluetooth.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.beam.classic.BeamTests.*;

final class PairlessTests {
    static final class Capture extends OutputStream {
        final OutputStream delegate; final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int corruptAfter = -1;
        Capture(OutputStream delegate) { this.delegate = delegate; }
        public void write(int value) throws IOException { write(new byte[]{(byte)value}); }
        public synchronized void write(byte[] b, int offset, int size) throws IOException {
            byte[] copy = Arrays.copyOfRange(b, offset, offset + size);
            if (corruptAfter >= 0) {
                if (corruptAfter < size) { copy[corruptAfter] ^= 1; corruptAfter = -1; }
                else corruptAfter -= size;
            }
            bytes.write(copy); delegate.write(copy);
        }
        public void flush() throws IOException { delegate.flush(); }
        public void close() throws IOException { delegate.close(); }
    }
    static final class Duplex implements AutoCloseable {
        final ExecutorService work = Executors.newFixedThreadPool(2);
        final PipedInputStream serverIn = new PipedInputStream(65536), clientIn = new PipedInputStream(65536);
        final Capture clientOut = new Capture(new PipedOutputStream(serverIn)), serverOut = new Capture(new PipedOutputStream(clientIn));
        TapChannel client, server;
        Duplex() throws IOException {}
        void open(TapOffer clientOffer, TapOffer serverOffer) throws Exception {
            Future<TapChannel> first = work.submit(() -> TapChannel.open(serverIn, serverOut, serverOffer, false));
            Future<TapChannel> second = work.submit(() -> TapChannel.open(clientIn, clientOut, clientOffer, true));
            server = first.get(5, TimeUnit.SECONDS); client = second.get(5, TimeUnit.SECONDS);
        }
        public void close() throws Exception { serverIn.close(); clientIn.close(); clientOut.close(); serverOut.close(); work.shutdownNow(); }
    }
    static void badRead(InputStream input, String why) throws Exception {
        try { input.read(); throw new AssertionError(why); } catch (IOException expected) { check(true, why); }
    }
    static void rejectedHandshake(TapOffer clientOffer, TapOffer serverOffer, String why) throws Exception {
        try (Duplex d = new Duplex()) {
            Future<Boolean> server = d.work.submit(() -> {
                try { TapChannel.open(d.serverIn, d.serverOut, serverOffer, false); return false; }
                catch (IOException expected) { return true; }
                finally { d.serverOut.close(); }
            });
            Future<Boolean> client = d.work.submit(() -> {
                try { TapChannel.open(d.clientIn, d.clientOut, clientOffer, true); return false; }
                catch (IOException expected) { return true; }
            });
            check(server.get(5, TimeUnit.SECONDS) && client.get(5, TimeUnit.SECONDS), why);
        }
    }
    static FileProtocol.Source source(String name, byte[] data) throws Exception {
        FileProtocol.Meta meta = new FileProtocol.Meta(name, "application/octet-stream", data.length);
        return new FileProtocol.Source() { public FileProtocol.Meta meta() { return meta; } public InputStream open() { return new ByteArrayInputStream(data); } };
    }
    static BluetoothServerSocket pairless(Pair p, TapOffer offer) throws Exception {
        BluetoothServerSocket le = new BluetoothServerSocket();
        p.receiver.receive(p.adapter, offer.token, le, offer); waitFor(p.rx.listening, "three receive paths ready");
        le.pending.add(p.receiverSocket); p.sender.connectPairless(p.senderSocket, offer);
        waitFor(p.tx.ready, "unbonded tap handshake ready"); return le;
    }
    static void run(String token) throws Exception {
        TapOffer offer = TapOffer.create(token, 129);
        check(offer.encode().equals(TapOffer.parse(offer.encode()).encode()), "NFC handover roundtrips canonically");
        check(!Arrays.equals(offer.key(), TapOffer.create(token, 129).key()), "tap secret is fresh and independent of public UUID");
        byte[] key = offer.key(); key[0] ^= 1; check(!Arrays.equals(key, offer.key()), "callers cannot mutate the stored NFC secret");
        for (String invalid : new String[]{null, "", "TOUCH8|x|129|x", offer.encode()+"|extra", offer.encode().replace("|129|", "|0|"), offer.encode().replace("|129|", "|256|"), offer.encode().replace("|129|", "|0129|"), offer.encode()+"="}) {
            try { TapOffer.parse(invalid); throw new AssertionError("malformed handover accepted"); }
            catch (IllegalArgumentException expected) { check(true, "malformed NFC handover rejected"); }
        }
        NfcSession.clear(); NfcSession.clearBackground(); NfcSession.publishOffer(offer);
        check(NfcSession.offer(1000) == null, "offer cannot publish without an active receiver");
        NfcSession.enableBackground(token, 1000); NfcSession.publishOffer(offer);
        check(NfcSession.offer(1001) == offer, "live background receiver publishes its private handover");
        NfcSession.clear(); check(NfcSession.offer(1001) == offer, "foreground pause preserves background handover");
        NfcSession.clearOffer("87654321-1234-1234-1234-123456789012"); check(NfcSession.offer(1001) == offer, "old session cannot erase another offer");
        check(NfcSession.offer(301000) == null, "private handover expires with receiver identity");
        NfcSession.clearBackground(); check(NfcSession.offer(1001) == null, "Stop removes NFC key handover");

        rejectedHandshake(new TapOffer(token, 129, key), offer, "wrong NFC key rejects both roles before application data");
        rejectedHandshake(new TapOffer(token, 131, offer.key()), offer, "handover binds the endpoint port");
        rejectedHandshake(new TapOffer("87654321-1234-1234-1234-123456789012", 129, offer.key()), offer, "handover binds the tapped session identity");
        try (Duplex d = new Duplex()) {
            d.open(offer, offer); d.clientOut.bytes.reset();
            byte[] binary = new byte[TapChannel.BLOCK * 3 + 17]; new Random(73).nextBytes(binary);
            Future<?> write = d.work.submit(() -> { try { d.client.output.write(binary); d.client.output.flush(); } catch (IOException ex) { throw new RuntimeException(ex); } });
            Future<byte[]> read = d.work.submit(() -> { byte[] actual = new byte[binary.length]; new DataInputStream(d.server.input).readFully(actual); return actual; });
            check(Arrays.equals(binary, read.get(5, TimeUnit.SECONDS)), "bounded encrypted records stream arbitrary multi-record payloads"); write.get(5, TimeUnit.SECONDS);
            check(!Arrays.equals(binary, d.clientOut.bytes.toByteArray()), "wire bytes differ from payload");
            Wire.write(d.server.output, "verified receipt 🌌"); check("verified receipt 🌌".equals(Wire.read(d.client.input)), "independent reverse channel carries Unicode receipt");
        }
        try (Duplex d = new Duplex()) {
            d.open(offer, offer); d.clientOut.corruptAfter = 4;
            d.client.output.write(new byte[]{1,2,3}); d.client.output.flush(); d.client.output.write(new byte[]{4,5}); d.client.output.flush();
            badRead(d.server.input, "ciphertext modification is rejected before plaintext is released");
            badRead(d.server.input, "authentication failure permanently closes record decoding");
        }
        try (Duplex d = new Duplex()) {
            d.open(offer, offer); d.clientOut.bytes.reset(); d.client.output.write(new byte[]{7}); d.client.output.flush();
            byte[] replay = d.clientOut.bytes.toByteArray(); check(d.server.input.read() == 7, "first authenticated record accepted");
            d.clientOut.write(replay); d.clientOut.flush(); badRead(d.server.input, "replayed record fails sequence authentication");
        }
        byte[] oldProof;
        try (Duplex d = new Duplex()) { d.open(offer, offer); oldProof = d.clientOut.bytes.toByteArray(); }
        try (Duplex d = new Duplex()) {
            d.clientOut.write(oldProof); d.clientOut.flush();
            Future<Boolean> replay = d.work.submit(() -> {
                try { TapChannel.open(d.serverIn, d.serverOut, offer, false); return false; }
                catch (IOException expected) { return true; }
            });
            check(replay.get(5, TimeUnit.SECONDS), "old client proof cannot authenticate against a fresh server nonce");
        }
        try (Duplex d = new Duplex()) {
            d.open(offer, offer); new DataOutputStream(d.clientOut).writeInt(20); d.clientOut.write(1); d.clientOut.close();
            badRead(d.server.input, "truncated ciphertext produces no partial plaintext");
        }
        for (int length : new int[]{-1, 0, 16, TapChannel.BLOCK+17}) try (Duplex d = new Duplex()) {
            d.open(offer, offer); new DataOutputStream(d.clientOut).writeInt(length); d.clientOut.flush();
            badRead(d.server.input, "malformed record length rejected without allocation");
        }
        byte[] first = new byte[100003], second = new byte[37]; new Random(5).nextBytes(first); new Random(6).nextBytes(second);
        try (Pair p = new Pair()) {
            p.rx.autoAcceptOnTap = true; p.rx.acceptFiles = false;
            pairless(p, offer); check(p.rx.tapAuthorized, "receiver recognizes possession of the private NFC tap key");
            p.sender.sendFile(source("automatic.jpg", first)); waitFor(p.tx.success, "verified tap needs no receiver Accept action");
            waitFor(p.rx.success, "automatically received file committed");
            check(p.rx.committed && Arrays.equals(first, p.rx.fileBytes.toByteArray()), "automatic NFC receipt keeps exact bytes and durable commit");
        }
        try (Pair p = new Pair()) {
            p.rx.autoAcceptOnTap = true; p.rx.acceptBatch = false;
            pairless(p, offer); p.sender.sendBatch(Arrays.asList(source("one.jpg", first), source("two.bin", second)));
            waitFor(p.tx.success, "one NFC authorization covers the complete batch"); waitFor(p.rx.success, "automatic batch committed");
            check(p.rx.committed && p.rx.batchBytes.size() == 2, "batch receives without per-file Accept actions");
        }
        try (Pair p = new Pair()) {
            p.rx.autoAcceptOnTap = true; p.rx.acceptFiles = false;
            p.start(token, token); waitFor(p.tx.ready, "manual Classic connection ready");
            p.sender.sendFile(source("manual.bin", second)); waitFor(p.tx.error, "manual connection still needs receiver acceptance");
            check(!p.rx.tapAuthorized && !p.rx.committed && p.rx.fileBytes == null, "Bluetooth connection alone cannot authorize automatic receipt");
        }
        try (Pair p = new Pair()) {
            p.rx.autoAcceptOnTap = true; p.rx.acceptFiles = false;
            BluetoothServerSocket le = new BluetoothServerSocket(); p.receiver.receive(p.adapter, token, le, offer);
            waitFor(p.rx.listening, "receiver ready to reject forged tap"); le.pending.add(p.receiverSocket);
            p.sender.connectPairless(p.senderSocket, new TapOffer(token, offer.psm, key));
            waitFor(p.tx.error, "forged tap fails before automatic receipt");
            check(!p.rx.tapAuthorized && !p.rx.committed && p.rx.fileBytes == null, "wrong NFC key never grants automatic file authorization");
        }
        try (Pair p = new Pair()) {
            BluetoothServerSocket le = pairless(p, offer); p.sender.send("No Bluetooth bond");
            waitFor(p.tx.success, "pair-free text receipt"); waitFor(p.rx.success, "pair-free text committed");
            check("No Bluetooth bond".equals(p.rx.text), "pair-free text exact");
            check(p.receiverDevice.connectedService == null, "pair-free sender never opens the Classic pairing socket");
            check(le.closed && p.adapter.server.closed && p.adapter.tapServer.closed, "winning pair-free connection closes all listeners");
        }
        try (Pair p = new Pair()) {
            pairless(p, offer); p.sender.sendFile(source("photo.jpg", first)); waitFor(p.tx.success, "pair-free file receipt"); waitFor(p.rx.success, "pair-free file saved");
            check(p.rx.committed && Arrays.equals(first, p.rx.fileBytes.toByteArray()), "pair-free file remains exact and durable");
        }
        try (Pair p = new Pair()) {
            pairless(p, offer); p.sender.sendBatch(Arrays.asList(source("a.bin", first), source("b.bin", second), source("empty", new byte[0])));
            waitFor(p.tx.success, "pair-free batch receipt"); waitFor(p.rx.success, "pair-free batch saved");
            check(p.rx.committed && p.rx.batchBytes.size() == 3 && Arrays.equals(first, p.rx.batchBytes.get(0).toByteArray()) && Arrays.equals(second, p.rx.batchBytes.get(1).toByteArray()), "pair-free batch preserves all bytes");
        }
        try (Pair p = new Pair()) {
            p.rx.acceptFiles = false; pairless(p, offer); p.sender.sendFile(source("decline.bin", second)); waitFor(p.tx.error, "pair-free decline reaches sender");
            check(!p.rx.committed && p.rx.fileBytes == null, "pair-free path cannot bypass file acceptance");
        }
        try (Pair p = new Pair()) {
            BluetoothServerSocket le = new BluetoothServerSocket(); p.receiver.receive(p.adapter, token, le, offer); waitFor(p.rx.listening, "cancellable pair-free receiver ready");
            le.pending.add(p.receiverSocket); CountDownLatch closed = new CountDownLatch(1); p.receiver.cancelAsync(closed::countDown);
            waitFor(closed, "cancelling a pending cryptographic handshake unblocks cleanup");
            check(le.closed && p.rx.ready.getCount() == 1 && !p.rx.committed, "cancelled handshake cannot announce ready or publish files");
        }
    }
}
