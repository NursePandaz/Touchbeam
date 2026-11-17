package dev.beam.classic;

import java.io.*;
import java.security.*;

/** Bounded, streaming file frames. A file is never buffered in full. */
public final class FileProtocol {
    public static final long MAX_FILE = 100L * 1024 * 1024;
    public static final int CHUNK = 32768;
    private FileProtocol() {}
    public interface Progress { void update(long done, long total) throws IOException; }
    public interface Source { Meta meta(); InputStream open() throws IOException; }
    public interface Sink extends Closeable { OutputStream stream(); void commit(Result result) throws IOException; }
    public static final class Meta {
        public final String name, mime;
        public final long size;
        public Meta(String name, String mime, long size) throws IOException {
            if (size < -1 || size > MAX_FILE) throw new IOException("Files must be at most 100 MB.");
            this.name = safeName(name);
            this.mime = mime != null && mime.length() <= 127 && mime.matches("[a-zA-Z0-9!#$&^_.+\\-]+/[a-zA-Z0-9!#$&^_.+\\-]+") ? mime : "application/octet-stream";
            this.size = size;
        }
    }
    public static final class Result {
        public final long bytes;
        public final String digest;
        Result(long bytes, String digest) { this.bytes = bytes; this.digest = digest; }
        public String receipt() { return "OK:" + digest; }
    }
    public static String safeName(String name) {
        if (name == null) return "beam-file";
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        StringBuilder safe = new StringBuilder();
        name.codePoints().filter(c -> !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT)
            .limit(128).forEach(safe::appendCodePoint);
        String result = safe.toString().trim();
        return result.isEmpty() || result.equals(".") || result.equals("..") ? "beam-file" : result;
    }
    public static void writeMeta(OutputStream out, Meta meta) throws IOException {
        Wire.write(out, meta.name); Wire.write(out, meta.mime);
        new DataOutputStream(out).writeLong(meta.size); out.flush();
    }
    public static Meta readMeta(InputStream in) throws IOException {
        return new Meta(Wire.read(in), Wire.read(in), new DataInputStream(in).readLong());
    }
    private static MessageDigest sha() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); } }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
    public static Result send(InputStream input, OutputStream stream, Meta meta, Progress progress) throws IOException {
        return send(input, stream, meta, progress, MAX_FILE);
    }
    public static Result send(InputStream input, OutputStream stream, Meta meta, Progress progress, long remaining) throws IOException {
        DataOutputStream out = new DataOutputStream(stream);
        byte[] buffer = new byte[CHUNK];
        MessageDigest digest = sha(); long total = 0;
        progress.update(0, meta.size);
        for (;;) {
            int count = input.read(buffer);
            if (count < 0) break;
            if (count == 0) { int one = input.read(); if (one < 0) break; buffer[0] = (byte)one; count = 1; }
            if (count > Math.min(MAX_FILE, remaining) - total || (meta.size >= 0 && count > meta.size - total)) throw new IOException("File grew or exceeds the file/batch limit.");
            out.writeInt(count); out.write(buffer, 0, count);
            digest.update(buffer, 0, count); total += count;
            progress.update(total, meta.size);
        }
        if (meta.size >= 0 && total != meta.size) throw new IOException("The selected file changed. Select it again.");
        Result result = new Result(total, hex(digest.digest()));
        out.writeInt(0); Wire.write(out, "SHA256:" + result.digest); out.flush();
        return result;
    }
    public static Result receive(InputStream stream, OutputStream out, Meta meta, Progress progress) throws IOException {
        return receive(stream, out, meta, progress, MAX_FILE);
    }
    public static Result receive(InputStream stream, OutputStream out, Meta meta, Progress progress, long remaining) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        MessageDigest digest = sha(); byte[] buffer = new byte[CHUNK]; long total = 0;
        progress.update(0, meta.size);
        for (;;) {
            int count = in.readInt();
            if (count == 0) break;
            if (count < 0 || count > CHUNK || count > Math.min(MAX_FILE, remaining) - total || (meta.size >= 0 && count > meta.size - total))
                throw new IOException("Invalid file chunk or size.");
            in.readFully(buffer, 0, count); out.write(buffer, 0, count);
            digest.update(buffer, 0, count); total += count;
            progress.update(total, meta.size);
        }
        if (meta.size >= 0 && total != meta.size) throw new IOException("Incomplete file.");
        Result result = new Result(total, hex(digest.digest()));
        if (!("SHA256:" + result.digest).equals(Wire.read(in))) throw new IOException("File checksum mismatch.");
        out.flush(); return result;
    }
}
