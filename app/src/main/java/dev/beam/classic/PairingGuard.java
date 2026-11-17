package dev.beam.classic;

import java.util.*;

/** A bounded exception to foreground-only sessions for Android's pairing UI. */
final class PairingGuard {
    private final Set<String> bonding = new HashSet<>();
    private long until;
    void update(String address, boolean active) { if (address == null) return; if (active) bonding.add(address); else bonding.remove(address); }
    boolean retain(boolean waitingReceiver, boolean connectingSender, String selected, long now) {
        if (!(waitingReceiver && !bonding.isEmpty()) && !(connectingSender && bonding.contains(selected))) { until = 0; return false; }
        until = now + 90000; return true;
    }
    boolean expired(long now) { return until > 0 && now >= until; }
    void resumed() { until = 0; }
    void clear() { until = 0; bonding.clear(); }
}
