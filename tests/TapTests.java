package dev.beam.classic;

import java.util.UUID;
import static dev.beam.classic.BeamTests.*;

final class TapTests {
    static void run(String token) throws Exception {
        check(NfcSession.serviceUuid(token).equals(UUID.fromString(token)), "tap service represents exact NFC identity");
        for (String invalid : new String[]{null, "", "1-1-1-1-1", "zzzzzzzz-1234-1234-1234-123456789012", BeamTransport.SERVICE.toString()}) {
            try { NfcSession.serviceUuid(invalid); throw new AssertionError("invalid tap identity accepted"); }
            catch (IllegalArgumentException expected) { check(true, "malformed or general-purpose service cannot be tap identity"); }
        }
        try (Pair p = new Pair()) {
            p.receiver.receive(p.adapter, token); waitFor(p.rx.listening, "both receive services listening");
            check(p.adapter.tapUuid.equals(NfcSession.serviceUuid(token)), "receiver advertises NFC identity before ready");
            p.adapter.tapServer.pending.add(p.receiverSocket);
            p.sender.connect(p.receiverDevice, token, true); waitFor(p.tx.ready, "tap connects without a manually selected service");
            check(p.receiverDevice.connectedService.equals(NfcSession.serviceUuid(token)), "tap connection uses exact service, not generic nearby service");
            p.sender.send("Tapped receiver"); waitFor(p.tx.success, "tap transfer acknowledged"); waitFor(p.rx.success, "tap receiver finished");
            check("Tapped receiver".equals(p.rx.text), "tap path transfers original payload");
            check(p.adapter.server.closed && p.adapter.tapServer.closed, "tap winner closes both advertised listeners");
        }
        try (Pair p = new Pair()) {
            p.receiver.receive(p.adapter, token); waitFor(p.rx.listening, "mismatched receiver listening");
            p.adapter.tapServer.pending.add(p.receiverSocket);
            p.sender.connect(p.receiverDevice, "87654321-1234-1234-1234-123456789012", true);
            waitFor(p.tx.error, "wrong NFC identity rejected after Bluetooth handshake");
            check(p.rx.text == null, "wrong receiver receives no payload");
        }
        try (Pair p = new Pair()) {
            p.receiver.receive(p.adapter, token); waitFor(p.rx.listening, "cancellable tap listener ready"); p.receiver.close();
            check(p.adapter.server.closed && p.adapter.tapServer.closed, "cancellation removes both services");
            check(p.rx.problem == null, "closing accept threads produces no late failure");
        }
    }
}
