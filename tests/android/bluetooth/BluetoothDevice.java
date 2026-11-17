package android.bluetooth;
import java.io.*;
import java.util.UUID;
public class BluetoothDevice {
    public static final int BOND_BONDED = 12;
    public int bondState = BOND_BONDED;
    public int getBondState(){return bondState;}
    public final String name;
    public BluetoothSocket socket;
    public UUID connectedService;
    public BluetoothDevice(String name) { this.name=name; }
    public String getName() { return name; }
    public BluetoothSocket createRfcommSocketToServiceRecord(UUID id) { connectedService=id; return socket; }
}
