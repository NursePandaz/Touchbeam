package dev.beam.classic;

import java.util.concurrent.*;
import static dev.beam.classic.BeamTests.*;

final class ReadyTests {
    static void run(String token) throws Exception {
        ReceiveConsent old = new ReceiveConsent(), current = new ReceiveConsent();
        check(!old.id.equals(current.id), "each background offer has an independent capability");
        check(!current.decide(old.id, true), "stale notification cannot accept a later offer");
        check(current.decide(current.id, true) && current.await(1), "current offer can be accepted");
        check(!current.decide(current.id, false), "decision cannot be changed by a second action");
        ReceiveConsent expired = new ReceiveConsent(0); check(!expired.decide(expired.id, true), "notification cannot approve an offer after its monotonic deadline");
        current = new ReceiveConsent(); check(!current.await(1), "unanswered background offer expires");
        check(!current.decide(current.id, true), "late acceptance cannot revive an expired offer");
        current = new ReceiveConsent(); current.cancel();
        check(!current.await(1) && !current.decide(current.id, true), "Stop revokes acceptance");
        current = new ReceiveConsent(); current.decide(current.id, false);
        check(!current.await(1), "declined offer produces no accepted sink");
        current = new ReceiveConsent(); current.decide(current.id, true); current.cancel();
        check(!current.await(1), "Stop before sink creation overrides an earlier acceptance");
        for (int i = 0; i < 50; i++) {
            ReceiveConsent race = new ReceiveConsent(); CountDownLatch go = new CountDownLatch(1);
            Thread accept = new Thread(() -> { await(go); race.decide(race.id, true); });
            Thread cancel = new Thread(() -> { await(go); race.cancel(); });
            accept.start(); cancel.start(); go.countDown(); accept.join(); cancel.join();
            check(!race.await(1), "concurrent Stop cannot leave consent active");
        }
        NfcSession.clear(); NfcSession.clearBackground();
        check(NfcSession.current(1000) == null, "cold service has no stale receiver identity");
        NfcSession.enableBackground(token, 1000);
        NfcSession.clear(); check(token.equals(NfcSession.current(1001)), "closing foreground activity preserves active background identity");
        String foreground = "87654321-1234-1234-1234-123456789012";
        NfcSession.enable(foreground, 1000);
        check(foreground.equals(NfcSession.current(1001)), "manual receiver identity takes priority");
        NfcSession.clearBackground(); check(foreground.equals(NfcSession.current(1001)), "background Stop does not erase foreground receiver");
        NfcSession.clear(); NfcSession.enableBackground(token, 1000);
        check(NfcSession.current(999) == null && NfcSession.current(301000) == null, "background identity has monotonic bounded lifetime");
        NfcSession.clearBackground(); check(NfcSession.current(1001) == null, "stopped service exposes no NFC capability");
        Events events = new Events(); BeamTransport transport = new BeamTransport(events);
        CountDownLatch cleaned = new CountDownLatch(1); transport.cancelAsync(cleaned::countDown);
        waitFor(cleaned, "asynchronous cancellation signals socket cleanup completion");
        check(events.problem == null && events.text == null, "cleanup callback does not invent a transfer result");
    }
    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
