package android.bluetooth;
import java.util.UUID;
public class BluetoothAdapter {
    public final BluetoothServerSocket server=new BluetoothServerSocket(), tapServer=new BluetoothServerSocket();
    public volatile UUID tapUuid;
    public BluetoothServerSocket listenUsingRfcommWithServiceRecord(String name,UUID id){
        if (id.equals(UUID.fromString("83f80119-faa5-4a3d-b7bd-927c8a23b274"))) return server;
        tapUuid=id; return tapServer;
    }
}
