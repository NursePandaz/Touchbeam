package android.bluetooth;
import java.io.*;
public class BluetoothSocket implements Closeable {
    final InputStream in; final OutputStream out; final BluetoothDevice remote;
    boolean connected;
    public BluetoothSocket(InputStream in, OutputStream out, BluetoothDevice remote) { this.in=in; this.out=out; this.remote=remote; }
    public void connect() throws IOException { connected=true; }
    public boolean isConnected() { return connected; }
    public InputStream getInputStream() { return in; }
    public OutputStream getOutputStream() { return out; }
    public BluetoothDevice getRemoteDevice() { return remote; }
    public void close() throws IOException { connected=false; in.close(); out.close(); }
}
