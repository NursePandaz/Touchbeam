package android.bluetooth;
import java.io.*;
import java.util.concurrent.*;
public class BluetoothServerSocket implements Closeable {
    public final BlockingQueue<BluetoothSocket> pending=new LinkedBlockingQueue<>();
    public volatile boolean closed;
    public BluetoothSocket accept() throws IOException {
        try { while(!closed) { BluetoothSocket socket=pending.poll(20,TimeUnit.MILLISECONDS); if(socket!=null)return socket; } }
        catch(InterruptedException ex){Thread.currentThread().interrupt();}
        throw new IOException("closed");
    }
    public void close() throws IOException {closed=true;}
}
