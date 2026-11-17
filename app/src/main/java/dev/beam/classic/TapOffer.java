package dev.beam.classic;

import java.util.*;
import java.security.SecureRandom;

/** Private NFC handover. Only its random UUID is advertised over the air. */
final class TapOffer {
    final String token;
    final int psm;
    private final byte[] secret;
    TapOffer(String token, int psm, byte[] secret) {
        NfcSession.serviceUuid(token);
        if (psm < 1 || psm > 255 || secret == null || secret.length != 32) throw new IllegalArgumentException("Invalid tap offer");
        this.token = token; this.psm = psm; this.secret = secret.clone();
    }
    static TapOffer create(String token, int psm) { byte[] key = new byte[32]; new SecureRandom().nextBytes(key); return new TapOffer(token, psm, key); }
    byte[] key() { return secret.clone(); }
    String encode() { return "TOUCH8|" + token + "|" + psm + "|" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret); }
    static TapOffer parse(String text) {
        if (text == null || text.length() > 100) throw new IllegalArgumentException("Invalid tap offer");
        String[] parts = text.split("\\|", -1);
        if (parts.length != 4 || !parts[0].equals("TOUCH8") || !parts[2].matches("[1-9][0-9]{0,2}") || !parts[3].matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid tap offer");
        TapOffer offer = new TapOffer(parts[1], Integer.parseInt(parts[2]), Base64.getUrlDecoder().decode(parts[3]));
        if (!offer.encode().equals(text)) throw new IllegalArgumentException("Noncanonical tap offer");
        return offer;
    }
}
