package dev.beam.classic;

import java.io.*;
import java.util.*;

/** All files stay private and provisional until the complete batch is verified. */
public final class StagedBatch implements BatchProtocol.Sink {
    public interface Commit { void save(File directory, List<FileProtocol.Result> results) throws IOException; }
    private final File part, target;
    private final Commit callback;
    private final FileProtocol.Result[] completed;
    private FileOutputStream current;
    private int next;
    private boolean committed, closed;
    public StagedBatch(File parent, int count, Commit callback) throws IOException {
        if (count < 1 || count > BatchProtocol.MAX_ITEMS) throw new IOException("Invalid batch count.");
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create receive directory.");
        String name = "batch-" + UUID.randomUUID();
        part = new File(parent, name + ".part"); target = new File(parent, name + ".ready");
        if (!part.mkdir()) throw new IOException("Cannot stage batch.");
        this.callback = callback; completed = new FileProtocol.Result[count];
    }
    public static String fileName(int index) { return String.format(Locale.ROOT, "%03d.bin", index); }
    public synchronized OutputStream open(int index) throws IOException {
        if (closed || index != next || current != null || index >= completed.length) throw new IOException("Invalid file sequence.");
        current = new FileOutputStream(new File(part, fileName(index))); return current;
    }
    public synchronized void finishFile(int index, FileProtocol.Result result) throws IOException {
        if (closed || index != next || current == null) throw new IOException("Invalid file completion.");
        if (result == null || result.bytes != new File(part, fileName(index)).length()) throw new IOException("Received file length changed.");
        current.flush(); current.getFD().sync(); current.close(); current = null;
        completed[index] = result; next++;
    }
    public synchronized void commit(List<FileProtocol.Result> results) throws IOException {
        if (closed || committed || next != completed.length || current != null || results == null || results.size() != completed.length) throw new IOException("Incomplete batch.");
        for (int i = 0; i < completed.length; i++) if (results.get(i) == null || completed[i].bytes != results.get(i).bytes || !completed[i].digest.equals(results.get(i).digest)) throw new IOException("Batch result changed.");
        if (!part.renameTo(target)) throw new IOException("Cannot finalize batch.");
        try { callback.save(target, Collections.unmodifiableList(new ArrayList<>(results))); committed = true; }
        finally { if (!committed) delete(target); }
    }
    public static void delete(File directory) {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) if (file.isFile()) file.delete();
        directory.delete();
    }
    public synchronized void close() throws IOException {
        if (closed) return; closed = true;
        try { if (current != null) current.close(); }
        finally { if (!committed) { delete(part); delete(target); } }
    }
}
