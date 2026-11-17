package dev.beam.classic;

import java.util.UUID;

/** Foreground NFC capability lives only in this process, never across a reboot. */
final class NfcSession {
    private static String token, backgroundToken;
    private static TapOffer foregroundOffer, backgroundOffer;
    private static long backgroundStarted, backgroundExpires;
    private static long started, expires;
    private NfcSession() {}
    static UUID serviceUuid(String value) {
        if (value == null || value.length() != 36) throw new IllegalArgumentException("Invalid tap identity");
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value) || uuid.equals(BeamTransport.SERVICE)) throw new IllegalArgumentException("Invalid tap identity");
        return uuid;
    }
    static synchronized void enable(String value, long now) {
        if (!value.equals(token)) foregroundOffer = null;
        token = value; started = now; expires = now + 300000L;
    }
    static synchronized void clear() { token = null; foregroundOffer = null; }
    static synchronized void enableBackground(String value, long now) { serviceUuid(value); if (!value.equals(backgroundToken)) backgroundOffer = null; backgroundToken = value; backgroundStarted = now; backgroundExpires = now + 300000L; }
    static synchronized void clearBackground() { backgroundToken = null; backgroundOffer = null; }
    static synchronized void publishOffer(TapOffer offer) {
        if (offer.token.equals(token)) foregroundOffer = offer;
        else if (offer.token.equals(backgroundToken)) backgroundOffer = offer;
    }
    static synchronized void clearOffer(String owner) {
        if (foregroundOffer != null && owner.equals(foregroundOffer.token)) foregroundOffer = null;
        if (backgroundOffer != null && owner.equals(backgroundOffer.token)) backgroundOffer = null;
    }
    static synchronized TapOffer offer(long now) {
        String active = current(now);
        return active == null ? null : active.equals(token) ? foregroundOffer : backgroundOffer;
    }
    static synchronized String current(long now) {
        return token != null && token.length() == 36 && now >= started && now < expires ? token : backgroundToken != null && now >= backgroundStarted && now < backgroundExpires ? backgroundToken : null;
    }
}
