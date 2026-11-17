package dev.beam.classic;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Versioned PSK channel for an unbonded socket; the PSK is shared only by NFC.
 * Both roles prove possession, bind the token and fresh nonces, and derive
 * separate directional AES-GCM keys. Every bounded record authenticates order.
 * This experimental application protocol is not Bluetooth link authentication.
 */
final class TapChannel implements Closeable {
    static final int BLOCK = 32768;
    final InputStream input;
    final OutputStream output;
    private TapChannel(InputStream input, OutputStream output) { this.input = input; this.output = output; }
    @Override public void close() throws IOException { try { input.close(); } finally { output.close(); } }
    private static byte[] mac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(data);
    }
    private static byte[] label(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    static TapChannel open(InputStream input, OutputStream output, TapOffer offer, boolean client) throws IOException {
        DataInputStream in = new DataInputStream(input); DataOutputStream out = new DataOutputStream(output);
        byte[] local = new byte[32], remote = new byte[32]; new SecureRandom().nextBytes(local);
        try {
            if (client) { in.readFully(remote); out.write(local); out.flush(); }
            else { out.write(local); out.flush(); in.readFully(remote); }
            byte[] transcript = ByteBuffer.allocate(32 + 32 + 36 + 8 + 4).put(label("TOUCH8v1")).put(label(offer.token)).putInt(offer.psm).put(client ? remote : local).put(client ? local : remote).array();
            byte[] master = mac(offer.key(), transcript);
            byte[] proof = new byte[32];
            if (client) {
                out.write(mac(master, label("client proof"))); out.flush(); in.readFully(proof);
                if (!MessageDigest.isEqual(proof, mac(master, label("server proof")))) throw new IOException("Tapped receiver proof failed.");
            } else {
                in.readFully(proof);
                if (!MessageDigest.isEqual(proof, mac(master, label("client proof")))) throw new IOException("Tap sender proof failed.");
                out.write(mac(master, label("server proof"))); out.flush();
            }
            byte[] sending = mac(master, label(client ? "client records" : "server records"));
            byte[] receiving = mac(master, label(client ? "server records" : "client records"));
            TapChannel channel = new TapChannel(new RecordsIn(in, receiving), new RecordsOut(out, sending));
            Arrays.fill(master, (byte)0); return channel;
        } catch (GeneralSecurityException ex) { throw new IOException("Tap encryption unavailable.", ex); }
    }
    private static byte[] crypt(int mode, byte[] key, long sequence, byte[] data) throws IOException {
        if (sequence < 0) throw new IOException("Tap record limit exceeded.");
        try {
            byte[] nonce = ByteBuffer.allocate(12).putInt(0).putLong(sequence).array();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(label("TOUCH8 record")); return cipher.doFinal(data);
        } catch (GeneralSecurityException ex) { throw new IOException("Tap record authentication failed.", ex); }
    }
    private static final class RecordsOut extends OutputStream {
        final DataOutputStream out; final byte[] key; long sequence;
        RecordsOut(DataOutputStream out, byte[] key) { this.out = out; this.key = key; }
        @Override public void write(int value) throws IOException { write(new byte[]{(byte)value}); }
        @Override public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
            while (length > 0) {
                int size = Math.min(length, BLOCK);
                byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, key, sequence++, Arrays.copyOfRange(bytes, offset, offset + size));
                out.writeInt(encrypted.length); out.write(encrypted); offset += size; length -= size;
            }
        }
        @Override public synchronized void flush() throws IOException { out.flush(); }
        @Override public void close() throws IOException { Arrays.fill(key, (byte)0); out.close(); }
    }
    private static final class RecordsIn extends InputStream {
        final DataInputStream in; final byte[] key; long sequence; byte[] plain = new byte[0]; int offset; boolean failed;
        RecordsIn(DataInputStream in, byte[] key) { this.in = in; this.key = key; }
        @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one) < 0 ? -1 : one[0] & 255; }
        @Override public synchronized int read(byte[] bytes, int start, int length) throws IOException {
            if (start < 0 || length < 0 || start > bytes.length - length) throw new IndexOutOfBoundsException();
            if (length == 0) return 0;
            if (failed) throw new IOException("Tap channel already failed.");
            try {
            if (offset == plain.length) {
                // Bare socket EOF is an interruption, never an authenticated end.
                int size = in.readInt();
                if (size < 17 || size > BLOCK + 16) throw new IOException("Invalid tap record size.");
                byte[] encrypted = new byte[size]; in.readFully(encrypted);
                plain = crypt(Cipher.DECRYPT_MODE, key, sequence++, encrypted); offset = 0;
            }
            int count = Math.min(length, plain.length - offset); System.arraycopy(plain, offset, bytes, start, count); offset += count; return count;
            } catch (IOException ex) { failed = true; throw ex; }
        }
        @Override public void close() throws IOException { failed = true; Arrays.fill(key, (byte)0); in.close(); }
    }
}
