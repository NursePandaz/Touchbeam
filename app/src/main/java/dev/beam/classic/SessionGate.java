package dev.beam.classic;

/** Exactly one terminal outcome; replaced timers cannot terminate a newer step. */
final class SessionGate {
    private int outcome;
    private long timer;
    synchronized boolean active() { return outcome == 0; }
    synchronized long arm() { return outcome == 0 ? ++timer : -1; }
    synchronized void disarm() { timer++; }
    synchronized boolean expire(long ticket) { if (outcome != 0 || ticket != timer || ticket < 0) return false; outcome = 2; timer++; return true; }
    synchronized boolean fail() { if (outcome != 0) return false; outcome = 2; timer++; return true; }
    synchronized boolean succeed() { if (outcome != 0) return false; outcome = 1; timer++; return true; }
    synchronized void cancel() { if (outcome == 0) outcome = 3; timer++; }
}
