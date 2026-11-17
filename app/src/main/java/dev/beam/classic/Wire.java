package dev.beam.classic;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;

/** Bounded, versioned text frames; never trust lengths from a remote phone. */
public final class Wire {
    public static final int MAX_BYTES = 16384;
    public static final String HELLO = "BEAM3:";
    private Wire() {}

    public static void write(OutputStream stream, String value) throws IOException {
        if (value == null) throw new IOException("Missing message.");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Message must be 1–16384 UTF-8 bytes.");
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    public static String read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        int size = in.readInt();
        if (size <= 0 || size > MAX_BYTES) throw new IOException("Invalid message length.");
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) { throw new IOException("Invalid UTF-8 message.", ex); }
    }

    public static String receipt(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("OK:");
            for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }
}
