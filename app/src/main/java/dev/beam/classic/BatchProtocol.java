package dev.beam.classic;

import java.io.*;
import java.security.*;
import java.util.*;

/** One manifest and one final receipt bind the entire ordered batch. */
public final class BatchProtocol {
    public static final int MAX_ITEMS = 32;
    public static final long MAX_TOTAL = 500L * 1024 * 1024;
    private BatchProtocol() {}
    public interface Sink extends Closeable {
        OutputStream open(int index) throws IOException;
        void finishFile(int index, FileProtocol.Result result) throws IOException;
        void commit(List<FileProtocol.Result> results) throws IOException;
    }
    public static final class Plan {
        public final List<FileProtocol.Meta> files;
        public final long total;
        public Plan(List<FileProtocol.Meta> files) throws IOException {
            if (files == null || files.isEmpty() || files.size() > MAX_ITEMS) throw new IOException("Choose 1–32 files.");
            long known = 0; boolean unknown = false;
            for (FileProtocol.Meta meta : files) {
                if (meta == null) throw new IOException("Missing file details.");
                if (meta.size < 0) unknown = true;
                else { known += meta.size; if (known > MAX_TOTAL) throw new IOException("A batch can be at most 500 MB."); }
            }
            this.files = Collections.unmodifiableList(new ArrayList<>(files)); total = unknown ? -1 : known;
        }
    }
    public static void write(OutputStream out, Plan plan) throws IOException {
        new DataOutputStream(out).writeInt(plan.files.size());
        for (FileProtocol.Meta meta : plan.files) FileProtocol.writeMeta(out, meta);
        out.flush();
    }
    public static Plan read(InputStream in) throws IOException {
        int count = new DataInputStream(in).readInt();
        if (count < 1 || count > MAX_ITEMS) throw new IOException("Invalid batch count.");
        ArrayList<FileProtocol.Meta> files = new ArrayList<>();
        for (int i = 0; i < count; i++) files.add(FileProtocol.readMeta(in));
        return new Plan(files);
    }
    public static String receipt(Plan plan, List<FileProtocol.Result> results) throws IOException {
        if (plan == null || results == null || results.size() != plan.files.size()) throw new IOException("Incomplete batch.");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            OutputStream discard = new OutputStream() { public void write(int value) {} public void write(byte[] b, int off, int len) {} };
            DigestOutputStream out = new DigestOutputStream(discard, digest);
            write(out, plan);
            long total = 0;
            for (int i = 0; i < results.size(); i++) {
                FileProtocol.Result result = results.get(i);
                if (result == null || result.digest == null) throw new IOException("Missing batch result.");
                if (result.bytes < 0 || result.bytes > FileProtocol.MAX_FILE || (plan.files.get(i).size >= 0 && result.bytes != plan.files.get(i).size)) throw new IOException("Invalid batch result.");
                total += result.bytes;
                if (total > MAX_TOTAL) throw new IOException("Batch exceeds 500 MB.");
                new DataOutputStream(out).writeLong(result.bytes); Wire.write(out, result.digest);
            }
            StringBuilder value = new StringBuilder("BATCH_OK:");
            for (byte b : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", b & 255));
            return value.toString();
        } catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }
    /** Stable unique names inside a new export folder, including case collisions. */
    public static List<String> exportNames(Plan plan) {
        HashSet<String> used = new HashSet<>(); ArrayList<String> names = new ArrayList<>();
        for (FileProtocol.Meta meta : plan.files) {
            String base = exportName(meta.name), name = base; int number = 2;
            while (!used.add(java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT))) {
                int dot = base.lastIndexOf('.');
                name = dot > 0 ? base.substring(0, dot) + " (" + number++ + ")" + base.substring(dot) : base + " (" + number++ + ")";
            }
            names.add(name);
        }
        return names;
    }
    public static String exportName(String value) {
        String name = FileProtocol.safeName(value);
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 ? name.substring(dot) : "";
        if (extension.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32) extension = "";
        String stem = extension.isEmpty() ? name : name.substring(0, dot);
        int budget = 220 - extension.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        StringBuilder clipped = new StringBuilder();
        for (int offset = 0; offset < stem.length();) {
            int codepoint = stem.codePointAt(offset), bytes = codepoint <= 127 ? 1 : codepoint <= 2047 ? 2 : codepoint <= 65535 ? 3 : 4;
            if (bytes > budget) break;
            clipped.appendCodePoint(codepoint); budget -= bytes; offset += Character.charCount(codepoint);
        }
        return clipped.toString() + extension;
    }
}
