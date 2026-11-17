package dev.beam.classic;

import java.io.*;

/** A received file becomes visible only after checksum verification and commit. */
public final class StagedFile implements FileProtocol.Sink {
    public interface Commit { void save(File file, FileProtocol.Result result) throws IOException; }
    private final File part, target;
    private final FileOutputStream output;
    private final Commit commit;
    private boolean committed, closed;
    public StagedFile(File directory, Commit commit) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create receive folder.");
        part = File.createTempFile("beam-", ".part", directory);
        target = new File(directory, part.getName().replace(".part", ".bin"));
        FileOutputStream opened;
        try { opened = new FileOutputStream(part); } catch (IOException ex) { part.delete(); throw ex; }
        output = opened; this.commit = commit;
    }
    public OutputStream stream() { return output; }
    public synchronized void commit(FileProtocol.Result result) throws IOException {
        if (closed || committed) throw new IOException("Receive cancelled or already committed.");
        if (result == null || result.bytes != part.length()) throw new IOException("Received file length changed.");
        output.flush(); output.getFD().sync(); output.close();
        if (!part.renameTo(target)) throw new IOException("Could not finalize received file.");
        try { commit.save(target, result); committed = true; }
        finally { if (!committed) target.delete(); }
    }
    public synchronized void close() throws IOException {
        if (closed) return; closed = true;
        try { output.close(); } finally { if (!committed) { part.delete(); target.delete(); } }
    }
}
