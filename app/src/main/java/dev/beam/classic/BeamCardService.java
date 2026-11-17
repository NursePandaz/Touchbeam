package dev.beam.classic;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** NFC only identifies an active receiver. Bluetooth carries the message. */
public class BeamCardService extends HostApduService {
    public static final byte[] SELECT = {(byte)0x00,(byte)0xA4,0x04,0x00,0x06,(byte)0xF0,0x42,0x45,0x41,0x4D,0x01,0x00};
    public static final byte[] PAIRLESS = {(byte)0x80,(byte)0xCA,0,0,0};
    @Override public byte[] processCommandApdu(byte[] command, Bundle extras) {
        String token = NfcSession.current(SystemClock.elapsedRealtime());
        if (token == null) return new byte[]{0x69,(byte)0x85};
        String value;
        if (Arrays.equals(command, SELECT)) value = Wire.HELLO + token;
        else if (Arrays.equals(command, PAIRLESS)) {
            TapOffer offer = NfcSession.offer(SystemClock.elapsedRealtime());
            if (offer == null) return new byte[]{0x69,(byte)0x85};
            value = offer.encode();
        } else return new byte[]{0x69,(byte)0x85};
        byte[] payload = value.getBytes(StandardCharsets.US_ASCII);
        byte[] response = Arrays.copyOf(payload, payload.length + 2);
        response[response.length - 2] = (byte)0x90;
        return response;
    }
    @Override public void onDeactivated(int reason) {}
}
