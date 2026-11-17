package dev.beam.classic;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** A notification action can approve only the current, unexpired offer once. */
final class ReceiveConsent {
    final String id = UUID.randomUUID().toString();
    private final CountDownLatch answer = new CountDownLatch(1);
    private int outcome;
    private final long expires;
    ReceiveConsent() { this(60000); }
    ReceiveConsent(long milliseconds) { expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, Math.min(milliseconds, 60000))); }
    synchronized boolean decide(String token, boolean accept) {
        if (outcome != 0 || System.nanoTime() >= expires || !id.equals(token)) return false;
        outcome = accept ? 1 : 2; answer.countDown(); return true;
    }
    boolean await(long milliseconds) throws InterruptedException {
        answer.await(Math.max(0, Math.min(TimeUnit.MILLISECONDS.toNanos(milliseconds), expires - System.nanoTime())), TimeUnit.NANOSECONDS);
        synchronized (this) { if (outcome == 0 || System.nanoTime() >= expires) outcome = 2; return outcome == 1; }
    }
    synchronized void cancel() { outcome = 2; answer.countDown(); }
}
