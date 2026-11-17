package dev.beam.classic;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.beam.classic.BeamTests.*;

public final class BatchTests {
    private static FileProtocol.Source source(String name, byte[] bytes, long size) throws Exception {
        FileProtocol.Meta meta = new FileProtocol.Meta(name,"application/octet-stream",size);
        return new FileProtocol.Source(){public FileProtocol.Meta meta(){return meta;}public InputStream open(){return new ByteArrayInputStream(bytes);}};
    }
    public static void run(String token) throws Exception {
        byte[] first = new byte[65579], second = new byte[1024*1024+11];
        new Random(12).nextBytes(first);new Random(42).nextBytes(second);
        List<FileProtocol.Source> sources=Arrays.asList(source("photo.jpg",first,first.length),source("photo.jpg",second,-1),source("empty.txt",new byte[0],0));
        try(Pair p=new Pair()){
            p.start(token,token);waitFor(p.tx.ready,"batch sender ready");p.sender.sendBatch(sources);
            waitFor(p.tx.success,"whole batch receipt");waitFor(p.rx.success,"whole batch saved");
            check(p.rx.committed,"one commit for entire batch");check(p.rx.batchBytes.size()==3,"all batch files kept");
            check(Arrays.equals(first,p.rx.batchBytes.get(0).toByteArray()),"first file exact");check(Arrays.equals(second,p.rx.batchBytes.get(1).toByteArray()),"unknown-size second file exact");
            check(p.rx.batchBytes.get(2).size()==0,"empty batch member preserved");check(p.rx.batchPlan.total==-1,"unknown combined size preserved");
            check(p.rx.text==null,"batch not mistaken for text");
        }
        ArrayList<FileProtocol.Source> maximum=new ArrayList<>();for(int i=0;i<32;i++)maximum.add(source("item-"+i,new byte[0],0));
        try(Pair p=new Pair()){
            p.start(token,token);waitFor(p.tx.ready,"32-file batch ready");p.sender.sendBatch(maximum);waitFor(p.tx.success,"32-file batch complete");
            check(p.rx.batchBytes.size()==32,"all 32 files retained");
        }
        AtomicInteger opened = new AtomicInteger();FileProtocol.Meta simple=new FileProtocol.Meta("private.bin","x/y",1);
        FileProtocol.Source counted=new FileProtocol.Source(){public FileProtocol.Meta meta(){return simple;}public InputStream open(){opened.incrementAndGet();return new ByteArrayInputStream(new byte[1]);}};
        try(Pair p=new Pair()){
            p.rx.acceptBatch=false;p.start(token,token);waitFor(p.tx.ready,"decline batch ready");p.sender.sendBatch(Arrays.asList(counted,counted));
            waitFor(p.tx.error,"batch decline received");check(opened.get()==0,"declined batch opens no source streams");check(p.rx.batchBytes==null,"declined batch creates no staging");
        }
        FileProtocol.Source inaccessible=new FileProtocol.Source(){public FileProtocol.Meta meta(){return simple;}public InputStream open()throws IOException{throw new IOException("permission revoked");}};
        try(Pair p=new Pair()){
            p.start(token,token);waitFor(p.tx.ready,"late failure ready");p.sender.sendBatch(Arrays.asList(sources.get(0),inaccessible));
            waitFor(p.tx.error,"late source failure sender");waitFor(p.rx.error,"late source failure receiver");
            check(!p.rx.committed,"first file not published when second fails");check(p.rx.batchBytes.get(0).size()==0,"verified first file cleaned on later failure");
        }
        try(Pair p=new Pair()){
            p.rx.failCommit=true;p.start(token,token);waitFor(p.tx.ready,"batch commit failure ready");p.sender.sendBatch(sources);
            waitFor(p.tx.error,"batch metadata failure sender");check(p.tx.success.getCount()==1,"no completion before all metadata persisted");check(!p.rx.committed,"failed commit unpublished");
        }
        List<FileProtocol.Meta> two=Arrays.asList(new FileProtocol.Meta("a.jpg","image/jpeg",0),new FileProtocol.Meta("A.jpg","image/jpeg",0));
        BatchProtocol.Plan plan=new BatchProtocol.Plan(two);
        check(BatchProtocol.exportNames(plan).equals(Arrays.asList("a.jpg","A (2).jpg")),"case-insensitive duplicate export names safe");
        ByteArrayOutputStream manifest=new ByteArrayOutputStream();BatchProtocol.write(manifest,plan);
        BatchProtocol.Plan parsed=BatchProtocol.read(new ByteArrayInputStream(manifest.toByteArray()));
        check(parsed.files.size()==2&&parsed.total==0,"batch manifest roundtrip");
        List<FileProtocol.Result> results=Arrays.asList(new FileProtocol.Result(0,"abc"),new FileProtocol.Result(0,"def"));
        String receipt=BatchProtocol.receipt(plan,results);
        check(!receipt.equals(BatchProtocol.receipt(plan,Arrays.asList(results.get(1),results.get(0)))),"receipt binds file order");
        check(!receipt.equals(BatchProtocol.receipt(new BatchProtocol.Plan(Arrays.asList(new FileProtocol.Meta("b.jpg","image/jpeg",0),two.get(1))),results)),"receipt binds names and types");
        for(int n:new int[]{-1,0,33,Integer.MAX_VALUE}){
            ByteArrayOutputStream bad=new ByteArrayOutputStream();new DataOutputStream(bad).writeInt(n);
            try{BatchProtocol.read(new ByteArrayInputStream(bad.toByteArray()));throw new AssertionError("bad count accepted");}catch(IOException expected){check(true,"invalid count rejected");}
        }
        ArrayList<FileProtocol.Meta> tooBig=new ArrayList<>();for(int i=0;i<6;i++)tooBig.add(new FileProtocol.Meta("x","x/y",FileProtocol.MAX_FILE));
        try{new BatchProtocol.Plan(tooBig);throw new AssertionError("oversized batch accepted");}catch(IOException expected){check(true,"known total limit enforced");}
        try{new BatchProtocol.Plan(Collections.emptyList());throw new AssertionError("empty batch accepted");}catch(IOException expected){check(true,"empty manifest rejected");}
        try{BatchProtocol.receipt(plan,Collections.singletonList(results.get(0)));throw new AssertionError("subset acknowledged");}catch(IOException expected){check(true,"subset cannot complete batch");}
        ByteArrayOutputStream frames=new ByteArrayOutputStream();FileProtocol.Meta unknown=new FileProtocol.Meta("x","x/y",-1);
        FileProtocol.send(new ByteArrayInputStream(new byte[6]),frames,unknown,(d,t)->{});
        try{FileProtocol.receive(new ByteArrayInputStream(frames.toByteArray()),new ByteArrayOutputStream(),unknown,(d,t)->{},5);throw new AssertionError("remaining batch capacity ignored");}catch(IOException expected){check(true,"receiver enforces remaining capacity even for unknown sizes");}
        try{FileProtocol.send(new ByteArrayInputStream(new byte[6]),new ByteArrayOutputStream(),unknown,(d,t)->{},5);throw new AssertionError("sender remaining capacity ignored");}catch(IOException expected){check(true,"sender enforces remaining capacity");}
        storageTests();
        // A cancellation while reading the second source removes the entire provisional batch.
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean closed=new AtomicBoolean();
        FileProtocol.Source blocking=new FileProtocol.Source(){public FileProtocol.Meta meta(){return unknown;}public InputStream open(){return new InputStream(){
            public int read()throws IOException{blocked.countDown();try{release.await();}catch(InterruptedException ex){Thread.currentThread().interrupt();}throw new IOException("cancelled");}
            public void close(){closed.set(true);release.countDown();}};}};
        try(Pair p=new Pair()){
            p.start(token,token);waitFor(p.tx.ready,"batch cancel ready");p.sender.sendBatch(Arrays.asList(sources.get(0),blocking));waitFor(blocked,"second source opened");p.sender.close();
            waitFor(p.rx.error,"cancelled batch receiver");check(closed.get(),"batch cancel closes source");check(!p.rx.committed&&p.rx.batchBytes.get(0).size()==0,"cancel discards earlier staged files");
        }
        // A malicious receiver cannot acknowledge a different batch.
        try(Pair p=new Pair()){
            Thread rogue=new Thread(()->{try{
                Wire.write(p.receiverSocket.getOutputStream(),Wire.HELLO+token);Wire.read(p.receiverSocket.getInputStream());
                BatchProtocol.Plan offered=BatchProtocol.read(p.receiverSocket.getInputStream());Wire.write(p.receiverSocket.getOutputStream(),"ACCEPT");
                for(FileProtocol.Meta meta:offered.files)FileProtocol.receive(p.receiverSocket.getInputStream(),new ByteArrayOutputStream(),meta,(d,t)->{});
                Wire.read(p.receiverSocket.getInputStream());Wire.write(p.receiverSocket.getOutputStream(),"BATCH_OK:wrong");
            }catch(IOException ex){throw new RuntimeException(ex);}});
            rogue.start();p.sender.connect(p.receiverDevice,null);waitFor(p.tx.ready,"batch receipt attack ready");p.sender.sendBatch(sources);waitFor(p.tx.error,"wrong batch receipt rejected");
            check(p.tx.success.getCount()==1,"wrong batch receipt never succeeds");rogue.join(1000);
        }
    }
    private static void storageTests()throws Exception{
        File directory=java.nio.file.Files.createTempDirectory("beam-batch-test").toFile();
        File previous=new File(directory,"previous.bin");java.nio.file.Files.write(previous.toPath(),new byte[]{9});
        FileProtocol.Result one=new FileProtocol.Result(1,"one"),two=new FileProtocol.Result(1,"two");
        try{
            try(StagedBatch batch=new StagedBatch(directory,2,(dir,results)->{throw new AssertionError("partial commit");})){
                batch.open(0).write(1);batch.finishFile(0,one);batch.open(1).write(2);
            }
            check(directory.list().length==1&&previous.exists(),"cancel deletes all staged members and preserves previous batch");
            try(StagedBatch batch=new StagedBatch(directory,2,(dir,results)->{})){
                batch.open(0).write(1);batch.finishFile(0,one);
                try{batch.commit(Collections.singletonList(one));throw new AssertionError("incomplete batch published");}catch(IOException expected){check(true,"incomplete storage commit rejected");}
            }
            final File[] saved={null};
            try(StagedBatch batch=new StagedBatch(directory,2,(dir,results)->saved[0]=dir)){
                batch.open(0).write(1);batch.finishFile(0,one);batch.open(1).write(2);batch.finishFile(1,two);batch.commit(Arrays.asList(one,two));
            }
            check(saved[0]!=null&&saved[0].list().length==2,"whole directory published together");
            check(java.nio.file.Files.readAllBytes(new File(saved[0],StagedBatch.fileName(0)).toPath())[0]==1,"duplicate names cannot overwrite internal files");
            StagedBatch.delete(saved[0]);
            try(StagedBatch batch=new StagedBatch(directory,1,(dir,results)->{throw new IOException("prefs failure");})){
                batch.open(0).write(1);batch.finishFile(0,one);
                try{batch.commit(Collections.singletonList(one));throw new AssertionError("metadata failure ignored");}catch(IOException expected){check(true,"batch metadata failure propagated");}
            }
            check(directory.list().length==1&&previous.exists(),"metadata failure cleans renamed directory and preserves previous file");
        }finally{for(File f:directory.listFiles()){if(f.isDirectory())StagedBatch.delete(f);else f.delete();}directory.delete();}
    }
}
