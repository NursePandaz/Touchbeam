package dev.beam.classic;

import java.util.*;

/** Bounded SDP work queue. Stale probe timeouts cannot consume a newer result. */
final class DiscoveryQueue {
    static final int LIMIT = 32;
    static final class Request {
        final String address; final long ticket;
        Request(String address, long ticket) { this.address = address; this.ticket = ticket; }
    }
    private final Set<String> seen = new HashSet<>();
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private Request active;
    private long serial;
    private boolean closed;
    boolean add(String address) {
        if (closed || address == null || address.isEmpty() || seen.size() >= LIMIT || !seen.add(address)) return false;
        pending.add(address); return true;
    }
    Request next() {
        if (closed || active != null || pending.isEmpty()) return null;
        active = new Request(pending.remove(), ++serial); return active;
    }
    Request active() { return active; }
    void prioritize(String address) { if (!closed && pending.remove(address)) pending.addFirst(address); }
    boolean finish(String address, long ticket) {
        if (closed || active == null || active.ticket != ticket || !active.address.equals(address)) return false;
        active = null; return true;
    }
    void close() { closed = true; active = null; pending.clear(); serial++; }
}
