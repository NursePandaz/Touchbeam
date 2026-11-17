package dev.beam.classic;

import static dev.beam.classic.BeamTests.*;

final class DiscoveryTests {
    static void run() {
        DiscoveryQueue q = new DiscoveryQueue();
        check(!q.add(null) && !q.add(""), "invalid discovery addresses rejected");
        check(q.add("paired") && q.add("nearby") && !q.add("paired"), "discovery deduplicates devices");
        q.prioritize("nearby");
        DiscoveryQueue.Request first = q.next();
        check(first.address.equals("nearby"), "nearby devices checked before dormant paired phones");
        check(q.next() == null, "only one SDP request active");
        check(!q.finish("other", first.ticket), "unrelated broadcasts cannot finish probe");
        check(!q.finish(first.address, first.ticket + 1), "wrong timeout ticket rejected");
        check(q.finish(first.address, first.ticket), "current probe completes");
        DiscoveryQueue.Request second = q.next();
        check(second.address.equals("paired") && !q.finish(first.address, first.ticket), "stale probe timeout cannot consume next device");
        q.close();
        check(!q.finish(second.address, second.ticket) && q.next() == null && !q.add("late"), "closed discovery ignores delayed callbacks");
        q = new DiscoveryQueue();
        for (int i = 0; i < DiscoveryQueue.LIMIT; i++) check(q.add("phone" + i), "bounded queue accepts capacity");
        check(!q.add("overflow"), "crowded discovery remains bounded");
        PairingGuard guard = new PairingGuard();
        check(!guard.retain(true, false, null, 100), "ordinary background receiver closes");
        guard.update("other", true);
        check(!guard.retain(false, true, "chosen", 100), "unrelated pairing cannot retain sender");
        guard.update("chosen", true);
        check(guard.retain(false, true, "chosen", 100), "selected phone pairing retains connection");
        check(!guard.expired(90099) && guard.expired(90100), "pairing grace has strict ninety second limit");
        guard.resumed(); check(!guard.expired(999999), "returning to app clears background expiry");
        guard.update("chosen", false);
        check(!guard.retain(false, true, "chosen", 100), "completed pairing cannot retain background sender again");
        check(guard.retain(true, false, null, 200), "receiver can survive incoming pairing confirmation");
        check(!guard.retain(false, false, null, 200), "active transfer gets no pairing exception");
        guard.clear(); check(!guard.retain(true, false, null, 300), "new session discards old bonding state");
    }
}
