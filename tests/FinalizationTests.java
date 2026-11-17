package dev.beam.classic;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.beam.classic.BeamTests.*;

public final class FinalizationTests {
    public static void run(String token) throws Exception {
        SessionGate gate = new SessionGate();
        long old = gate.arm(), current = gate.arm();
        check(!gate.expire(old) && gate.active(), "replaced timeout cannot expire a current step");
        check(gate.expire(current), "current timeout can terminate session");
        check(!gate.succeed() && !gate.fail(), "expired session cannot also succeed or fail twice");
        gate = new SessionGate(); old = gate.arm(); gate.disarm();
        check(!gate.expire(old) && gate.succeed(), "cancelled timeout cannot beat success");
        gate = new SessionGate(); current = gate.arm(); gate.cancel();
        check(!gate.succeed() && !gate.fail() && !gate.expire(current), "cancellation suppresses every later terminal callback");
        // Force success, failure and cancellation to race from the same starting point.
        for (int i = 0; i < 100; i++) {
            SessionGate race = new SessionGate(); CountDownLatch go = new CountDownLatch(1); AtomicInteger callbacks = new AtomicInteger();
            Thread success = new Thread(() -> { await(go); if (race.succeed()) callbacks.incrementAndGet(); });
            Thread failure = new Thread(() -> { await(go); if (race.fail()) callbacks.incrementAndGet(); });
            Thread cancel = new Thread(() -> { await(go); race.cancel(); });
            success.start(); failure.start(); cancel.start(); go.countDown(); success.join(); failure.join(); cancel.join();
            check(callbacks.get() <= 1 && !race.active(), "racing outcomes never produce duplicate completion");
        }
        NfcSession.clear(); check(NfcSession.current(0) == null, "cold process has no NFC capability");
        NfcSession.enable(token, 1000); check(token.equals(NfcSession.current(1000)), "foreground receiver exposes its current token");
        check(NfcSession.current(999) == null, "backward clock cannot revive NFC state");
        check(NfcSession.current(301000) == null, "NFC token expires at its deadline");
        NfcSession.enable(token, 0); NfcSession.clear(); check(NfcSession.current(1) == null, "paused receiver exposes no NFC token");
        String longName = new String(new char[120]).replace('\0', '界') + ".jpg";
        List<FileProtocol.Meta> names = Arrays.asList(new FileProtocol.Meta(longName,"image/jpeg",0),new FileProtocol.Meta(longName,"image/jpeg",0),new FileProtocol.Meta("café.jpg","image/jpeg",0),new FileProtocol.Meta("cafe\u0301.jpg","image/jpeg",0));
        List<String> exported = BatchProtocol.exportNames(new BatchProtocol.Plan(names));
        HashSet<String> normalized = new HashSet<>();
        File directory = java.nio.file.Files.createTempDirectory("beam-finalization").toFile();
        try {
            for (String name : exported) {
                check(name.getBytes(StandardCharsets.UTF_8).length < 255, "export filename fits filesystem byte limit");
                check(name.endsWith(".jpg"), "truncated export keeps extension");
                check(normalized.add(java.text.Normalizer.normalize(name,java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT)), "normalized duplicate export names remain unique");
                check(new File(directory,name).createNewFile(), "export name can actually be created on disk");
            }
            check(names.get(0).name.equals(longName), "filename hardening does not alter transfer metadata");
            try (StagedFile sink = new StagedFile(directory,(f,r)->{})) {
                sink.stream().write(1);
                try { sink.commit(new FileProtocol.Result(2,"test")); throw new AssertionError("incorrect disk length accepted"); }
                catch (IOException expected) { check(true,"staged file rejects actual-size mismatch"); }
            }
            try (StagedBatch sink = new StagedBatch(directory,1,(f,r)->{})) {
                sink.open(0).write(1);
                try { sink.finishFile(0,new FileProtocol.Result(2,"test")); throw new AssertionError("incorrect batch disk length accepted"); }
                catch (IOException expected) { check(true,"staged batch rejects actual-size mismatch"); }
            }
        } finally { for(File f:directory.listFiles()){if(f.isDirectory())StagedBatch.delete(f);else f.delete();}directory.delete(); }
        try { Wire.write(new ByteArrayOutputStream(),null); throw new AssertionError("null frame accepted"); }
        catch(IOException expected){check(true,"missing frame is a checked failure");}
        // A document provider can throw an unchecked error after a file was selected.
        try (Pair p = new Pair()) {
            p.start(token,token); waitFor(p.tx.ready,"provider failure ready");
            FileProtocol.Meta meta = new FileProtocol.Meta("revoked.bin","x/y",1);
            p.sender.sendFile(new FileProtocol.Source(){public FileProtocol.Meta meta(){return meta;}public InputStream open(){throw new IllegalArgumentException("provider gone");}});
            waitFor(p.tx.error,"provider failure reported immediately"); waitFor(p.rx.error,"provider failure closes peer");
            check(!p.rx.committed && p.tx.success.getCount()==1,"provider exception cannot leave a false success");
        }
        Events revokedReceive = new Events();
        try (BeamTransport receiver = new BeamTransport(revokedReceive)) {
            receiver.receive(new android.bluetooth.BluetoothAdapter(){
                @Override public android.bluetooth.BluetoothServerSocket listenUsingRfcommWithServiceRecord(String name,UUID id){throw new SecurityException("permission revoked");}
            }, token);
            waitFor(revokedReceive.error,"revoked permission stops listening cleanly");
            check(revokedReceive.problem.contains("permission") && revokedReceive.success.getCount()==1,"receive permission failure is explicit and never succeeds");
        }
        Events revokedConnect = new Events();
        try (BeamTransport sender = new BeamTransport(revokedConnect)) {
            sender.connect(new android.bluetooth.BluetoothDevice("Receiver"){
                @Override public android.bluetooth.BluetoothSocket createRfcommSocketToServiceRecord(UUID id){throw new SecurityException("permission revoked");}
            }, null);
            waitFor(revokedConnect.error,"revoked permission stops connection cleanly");
            check(revokedConnect.problem.contains("permission") && revokedConnect.success.getCount()==1,"connect permission failure is explicit and never succeeds");
        }
        try (Pair p = new Pair()) {
            p.receiver.receive(p.adapter,token);waitFor(p.rx.listening,"revoked-name receiver listening");p.adapter.server.pending.add(p.receiverSocket);
            android.bluetooth.BluetoothDevice device=new android.bluetooth.BluetoothDevice("Receiver"){
                @Override public android.bluetooth.BluetoothSocket createRfcommSocketToServiceRecord(UUID id){return p.senderSocket;}
                @Override public String getName(){throw new SecurityException("permission revoked after connect");}
            };
            p.sender.connect(device,null);waitFor(p.tx.error,"permission revoked after connection handled");
            check(p.tx.ready.getCount()==1&&p.tx.success.getCount()==1,"revoked device-name access cannot announce ready or success");
        }
        // Simulate a provider whose close() itself blocks: cancellation must return
        // promptly to the UI while cleanup continues on a background thread.
        CountDownLatch reading=new CountDownLatch(1),closeEntered=new CountDownLatch(1),allowClose=new CountDownLatch(1),releaseRead=new CountDownLatch(1);
        try (Pair p = new Pair()) {
            p.start(token,token); waitFor(p.tx.ready,"slow-close provider ready");
            FileProtocol.Meta meta = new FileProtocol.Meta("slow.bin","x/y",-1);
            p.sender.sendFile(new FileProtocol.Source(){public FileProtocol.Meta meta(){return meta;}public InputStream open(){return new InputStream(){
                public int read()throws IOException{reading.countDown();await(releaseRead);throw new IOException("closed");}
                public void close(){closeEntered.countDown();await(allowClose);releaseRead.countDown();}
            };}});
            waitFor(reading,"provider read active"); long before=System.nanoTime(); p.sender.cancelAsync();
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<500,"slow resource close never blocks caller");
            waitFor(closeEntered,"cleanup runs asynchronously"); allowClose.countDown(); waitFor(p.rx.error,"cancelled peer closed");
            check(p.tx.error.getCount()==1 && p.tx.success.getCount()==1,"explicit cancel reports neither success nor failure");
        } finally { allowClose.countDown(); releaseRead.countDown(); }
    }
    private static void await(CountDownLatch latch) {
        boolean interrupted=false;
        for(;;)try{latch.await();break;}catch(InterruptedException ex){interrupted=true;}
        if(interrupted)Thread.currentThread().interrupt();
    }
}
