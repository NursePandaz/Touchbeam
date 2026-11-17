package dev.beam.classic;

import android.bluetooth.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class BeamTests {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void waitFor(CountDownLatch latch,String why)throws Exception{check(latch.await(5,TimeUnit.SECONDS),why);}
    static void invalid(byte[] bytes)throws Exception{
        try{Wire.read(new ByteArrayInputStream(bytes));throw new AssertionError("accepted invalid frame");}catch(IOException expected){checks++;}
    }
    static class Events implements BeamTransport.Listener {
        final CountDownLatch listening=new CountDownLatch(1),ready=new CountDownLatch(1),success=new CountDownLatch(1),error=new CountDownLatch(1);
        volatile String text,problem;
        volatile ByteArrayOutputStream fileBytes;
        volatile FileProtocol.Meta fileMeta;
        volatile boolean committed;
        boolean acceptFiles = true, failCommit, autoAcceptOnTap, tapAuthorized;
        public void onTapAuthorized(){tapAuthorized=true;}
        boolean acceptBatch = true;
        volatile java.util.List<ByteArrayOutputStream> batchBytes;
        volatile BatchProtocol.Plan batchPlan;
        public void onListening(){listening.countDown();}
        public void onReady(String peer){ready.countDown();}
        public void onReceived(String value){text=value;}
        public FileProtocol.Sink onFileOffer(FileProtocol.Meta meta) {
            if (!acceptFiles && !(autoAcceptOnTap && tapAuthorized)) return null;
            fileMeta=meta; fileBytes=new ByteArrayOutputStream();
            return new FileProtocol.Sink(){
                public OutputStream stream(){return fileBytes;}
                public void commit(FileProtocol.Result result)throws IOException{if(failCommit)throw new IOException("disk full");committed=true;}
                public void close(){if(!committed)fileBytes.reset();}
            };
        }
        public BatchProtocol.Sink onBatchOffer(BatchProtocol.Plan plan) {
            if (!acceptBatch && !(autoAcceptOnTap && tapAuthorized)) return null;
            batchPlan = plan; batchBytes = new java.util.ArrayList<>();
            return new BatchProtocol.Sink() {
                public OutputStream open(int index) { ByteArrayOutputStream out = new ByteArrayOutputStream(); batchBytes.add(out); return out; }
                public void finishFile(int index, FileProtocol.Result result) {}
                public void commit(java.util.List<FileProtocol.Result> results) throws IOException { if(failCommit)throw new IOException("disk full"); committed = true; }
                public void close() { if(!committed)for(ByteArrayOutputStream out:batchBytes)out.reset(); }
            };
        }
        public void onSuccess(){success.countDown();}
        public void onError(String value){problem=value;error.countDown();}
    }
    static class Pair implements AutoCloseable {
        final BluetoothAdapter adapter=new BluetoothAdapter();
        final BluetoothDevice receiverDevice=new BluetoothDevice("Receiver"),senderDevice=new BluetoothDevice("Sender");
        final Events rx=new Events(),tx=new Events();
        final BeamTransport receiver=new BeamTransport(rx),sender=new BeamTransport(tx);
        final BluetoothSocket receiverSocket,senderSocket;
        Pair()throws Exception {
            PipedInputStream a=new PipedInputStream(65536),b=new PipedInputStream(65536);
            senderSocket=new BluetoothSocket(a,new PipedOutputStream(b),receiverDevice);
            receiverSocket=new BluetoothSocket(b,new PipedOutputStream(a),senderDevice);
            receiverDevice.socket=senderSocket;
        }
        void start(String token,String expected)throws Exception {
            receiver.receive(adapter,token);waitFor(rx.listening,"receiver listening");
            adapter.server.pending.add(receiverSocket);sender.connect(receiverDevice,expected);
        }
        public void close(){sender.close();receiver.close();}
    }
    public static void main(String[] args)throws Exception {
        String token="12345678-1234-1234-1234-123456789012";
        String[] messages={"Beam me up, Android!","Hello 🌌\nこんにちは — مرحباً",new String(new char[Wire.MAX_BYTES]).replace('\0','x')};
        for(String value:messages){
            ByteArrayOutputStream out=new ByteArrayOutputStream();Wire.write(out,value);
            check(value.equals(Wire.read(new ByteArrayInputStream(out.toByteArray()))),"wire UTF-8 roundtrip");
            try(Pair p=new Pair()){
                p.receiverDevice.bondState = 10; // Exercise a newly discovered, initially unpaired connection.
                p.start(token,token);waitFor(p.tx.ready,"sender ready");p.sender.send(value);
                waitFor(p.tx.success,"sender acknowledged");waitFor(p.rx.success,"receiver complete");
                check(value.equals(p.rx.text),"actual transport payload integrity");check(p.tx.problem==null&&p.rx.problem==null,"no transport error");
            }
        }
        invalid(new byte[]{-1,-1,-1,-1});invalid(new byte[]{0,0,0,0});invalid(new byte[]{0,1,0,0});
        invalid(new byte[]{0,0,0,2,65});invalid(new byte[]{0,0,0,2,(byte)0xc3,0x28});
        try{Wire.write(new ByteArrayOutputStream(),new String(new char[Wire.MAX_BYTES+1]).replace('\0','x'));throw new AssertionError("oversized send accepted");}catch(IOException expected){checks++;}
        check(!Wire.receipt("a").equals(Wire.receipt("b")),"receipts depend on content");
        try(Pair p=new Pair()){
            p.start(token,"87654321-1234-1234-1234-123456789012");waitFor(p.tx.error,"NFC mismatch rejected");
            check(p.tx.ready.getCount()==1,"mismatched phone never ready");check(p.rx.text==null,"no payload on mismatch");
        }
        try(Pair p=new Pair()){
            p.receiver.receive(p.adapter,token);waitFor(p.rx.listening,"cancel listener ready");p.receiver.close();
            Thread.sleep(70);check(p.rx.problem==null,"cancel does not report failure");
        }
        // A remote peer cannot fake success with an unrelated acknowledgement.
        try(Pair p=new Pair()){
            Thread rogue=new Thread(()->{try{
                Wire.write(p.receiverSocket.getOutputStream(),Wire.HELLO+token);
                Wire.read(p.receiverSocket.getInputStream());Wire.read(p.receiverSocket.getInputStream());Wire.write(p.receiverSocket.getOutputStream(),Wire.receipt("wrong text"));
            }catch(IOException ex){throw new RuntimeException(ex);}});
            rogue.start();p.sender.connect(p.receiverDevice,null);waitFor(p.tx.ready,"fallback connection ready");
            p.sender.send("correct text");waitFor(p.tx.error,"bad receipt rejected");
            check(p.tx.success.getCount()==1,"no false success");rogue.join(1000);
        }
        fileTests(token);
        ShareTests.run();
        BatchTests.run(token);
        FinalizationTests.run(token);
        DiscoveryTests.run();
        TapTests.run(token);
        ReadyTests.run(token);
        PairlessTests.run(token);
        System.out.println("PASS: "+checks+" checks (text/files, streaming integrity, storage commit and transport; no phone hardware simulated).");
    }
    static FileProtocol.Source source(byte[] bytes,long size)throws Exception {
        FileProtocol.Meta meta=new FileProtocol.Meta("photo.jpg","image/jpeg",size);
        return new FileProtocol.Source(){public FileProtocol.Meta meta(){return meta;}public InputStream open(){return new ByteArrayInputStream(bytes);}};
    }
    static void fileTests(String token)throws Exception {
        for(int size:new int[]{0,65543,2*1024*1024+317}){
            byte[] bytes=new byte[size];new java.util.Random(42).nextBytes(bytes);
            for(long declared:new long[]{size,-1})try(Pair p=new Pair()){
                p.start(token,token);waitFor(p.tx.ready,"file sender ready");p.sender.sendFile(source(bytes,declared));
                waitFor(p.tx.success,"file sender verified");waitFor(p.rx.success,"file receiver saved");
                check(p.rx.committed,"file committed before receipt");check(java.util.Arrays.equals(bytes,p.rx.fileBytes.toByteArray()),"binary file exact");
                check(p.rx.text==null,"binary file not treated as text");
            }
        }
        try(Pair p=new Pair()){
            p.rx.acceptFiles=false;p.start(token,token);waitFor(p.tx.ready,"decline ready");p.sender.sendFile(source(new byte[30],30));
            waitFor(p.tx.error,"decline error");check(p.rx.fileBytes==null,"declined files create no output");check(p.tx.success.getCount()==1,"decline never succeeds");
        }
        try(Pair p=new Pair()){
            p.rx.failCommit=true;p.start(token,token);waitFor(p.tx.ready,"storage failure ready");p.sender.sendFile(source(new byte[30],30));
            waitFor(p.tx.error,"storage failure reported to sender");check(!p.rx.committed,"failed storage not committed");check(p.tx.success.getCount()==1,"no success before durable commit");
        }
        try(Pair p=new Pair()){
            p.start(token,token);waitFor(p.tx.ready,"changed file ready");p.sender.sendFile(source(new byte[20],30));
            waitFor(p.tx.error,"truncated source rejected");waitFor(p.rx.error,"truncated receive rejected");check(!p.rx.committed,"incomplete file not kept");
        }
        FileProtocol.Meta meta=new FileProtocol.Meta("../../unsafe\nname.jpg","bad\nmime",128);
        check(meta.name.equals("unsafename.jpg"),"filename safe display basename");check(meta.mime.equals("application/octet-stream"),"MIME sanitized");
        check(FileProtocol.safeName("..").equals("beam-file"),"dot path safe");
        try{new FileProtocol.Meta("big","x/y",FileProtocol.MAX_FILE+1);throw new AssertionError("oversize metadata accepted");}catch(IOException expected){checks++;}
        try{new FileProtocol.Meta("bad","x/y",-2);throw new AssertionError("negative metadata accepted");}catch(IOException expected){checks++;}
        byte[] bytes=new byte[128];new java.util.Random(10).nextBytes(bytes);
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();
        FileProtocol.send(new ByteArrayInputStream(bytes),encoded,meta,(done,total)->{});
        byte[] corrupt=encoded.toByteArray();corrupt[4]^=1;
        File dir=java.nio.file.Files.createTempDirectory("beam-file-test").toFile();
        try{
            try(StagedFile sink=new StagedFile(dir,(file,result)->{throw new AssertionError("corruption committed");})){
                try{FileProtocol.Result result=FileProtocol.receive(new ByteArrayInputStream(corrupt),sink.stream(),meta,(done,total)->{});sink.commit(result);throw new AssertionError("checksum ignored");}
                catch(IOException expected){checks++;}
            }
            check(dir.list().length==0,"corrupt staging file deleted");
            final File[] saved={null};
            try(StagedFile sink=new StagedFile(dir,(file,result)->saved[0]=file)){
                FileProtocol.Result result=FileProtocol.receive(new ByteArrayInputStream(encoded.toByteArray()),sink.stream(),meta,(done,total)->{});sink.commit(result);
            }
            check(saved[0]!=null&&java.util.Arrays.equals(bytes,java.nio.file.Files.readAllBytes(saved[0].toPath())),"verified file finalized on disk");saved[0].delete();
            try(StagedFile sink=new StagedFile(dir,(file,result)->{throw new IOException("metadata full");})){
                sink.stream().write(bytes);
                try{sink.commit(new FileProtocol.Result(bytes.length,"test"));throw new AssertionError("metadata failure ignored");}catch(IOException expected){checks++;}
            }
            check(dir.list().length==0,"failed metadata commit cleaned up");
            try(StagedFile sink=new StagedFile(dir,(file,result)->{})){sink.stream().write(bytes);}
            check(dir.list().length==0,"cancelled staging file deleted");
        }finally{for(File f:dir.listFiles())f.delete();dir.delete();}
        for(int chunk:new int[]{-1,FileProtocol.CHUNK+1}){
            ByteArrayOutputStream invalid=new ByteArrayOutputStream();new DataOutputStream(invalid).writeInt(chunk);
            try{FileProtocol.receive(new ByteArrayInputStream(invalid.toByteArray()),new ByteArrayOutputStream(),new FileProtocol.Meta("x","x/y",-1),(d,t)->{});throw new AssertionError("bad chunk accepted");}catch(IOException expected){checks++;}
        }
        // Unknown-length providers are bounded too, without allocating a 100 MB buffer.
        InputStream large=new InputStream(){long remaining=FileProtocol.MAX_FILE+1;
            public int read(){if(remaining--<=0)return -1;return 0;}
            public int read(byte[] b,int off,int len){if(remaining<=0)return -1;int n=(int)Math.min(len,remaining);java.util.Arrays.fill(b,off,off+n,(byte)0);remaining-=n;return n;}};
        try{FileProtocol.send(large,new OutputStream(){public void write(int b){}public void write(byte[] b,int off,int len){}},new FileProtocol.Meta("unknown","x/y",-1),(d,t)->{});throw new AssertionError("unknown oversized input accepted");}catch(IOException expected){checks++;}
        // Cancelling a mid-stream sender closes its source and cleans the receive sink.
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
        try(Pair p=new Pair()){
            p.start(token,token);waitFor(p.tx.ready,"cancelled file ready");
            FileProtocol.Meta info=new FileProtocol.Meta("cancel.bin","application/octet-stream",-1);
            p.sender.sendFile(new FileProtocol.Source(){public FileProtocol.Meta meta(){return info;}public InputStream open(){return new InputStream(){boolean first=true;
                public int read()throws IOException{throw new IOException("use bulk");}
                public int read(byte[] b,int off,int len)throws IOException{if(first){first=false;java.util.Arrays.fill(b,off,off+len,(byte)1);return len;}blocked.countDown();try{release.await();}catch(InterruptedException ex){Thread.currentThread().interrupt();}throw new IOException("cancelled");}
                public void close(){closed.set(true);release.countDown();}};}});
            waitFor(blocked,"source mid-stream");p.sender.close();waitFor(p.rx.error,"receiver detects interruption");
            check(closed.get(),"cancel closes source");check(!p.rx.committed,"cancel keeps no final file");check(p.rx.fileBytes.size()==0,"cancel discards staged bytes");
        }
    }
}
