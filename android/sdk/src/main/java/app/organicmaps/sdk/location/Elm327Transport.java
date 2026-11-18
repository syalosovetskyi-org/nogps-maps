package app.organicmaps.sdk.location;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import app.organicmaps.sdk.util.log.Logger;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The TCP proxy or Bluetooth SPP connection to an ELM327 adapter for navigation without GPS in the core: the core
 * talks to the adapter, this class only connects, writes and passes the received bytes back on the UI thread.
 */
class Elm327Transport
{
  private static final String TAG = Elm327Transport.class.getSimpleName();
  private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
  private static final int TCP_PORT = 35000;
  private static final int TCP_CONNECT_TIMEOUT_MS = 500;

  private final Handler mMainHandler = new Handler(Looper.getMainLooper());
  // Writes may block, they go one after another on their own thread.
  private final ExecutorService mWriter = Executors.newSingleThreadExecutor();
  @Nullable
  private Closeable mSocket;
  @Nullable
  private volatile OutputStream mOutput;
  // The connection the callbacks are for: the ones of a closed connection are dropped. Changed on the UI thread.
  private volatile int mConnection;

  @UiThread
  void connect(@NonNull String address)
  {
    close();
    final int connection = ++mConnection;
    new Thread(() -> run(address, connection), "Elm327").start();
  }

  @UiThread
  void write(@NonNull byte[] data)
  {
    final OutputStream out = mOutput;
    if (out == null)
      return;
    mWriter.execute(() -> {
      try
      {
        out.write(data);
        out.flush();
      }
      catch (IOException e)
      {
        // The reader notices the broken connection.
        Logger.w(TAG, "ELM327 write failed", e);
      }
    });
  }

  @UiThread
  void close()
  {
    ++mConnection;
    closeSocket();
  }

  private void run(@NonNull String address, int connection)
  {
    Closeable socket = null;
    try
    {
      Socket tcp = null;
      try
      {
        tcp = connectTcpSocket(TCP_PORT);
      }
      catch (IOException | SecurityException e)
      {
        Logger.i(TAG, "TCP 127.0.0.1:" + TCP_PORT + " unavailable; trying Bluetooth SPP", e);
      }
      final InputStream in;
      final OutputStream out;
      if (tcp != null)
      {
        socket = tcp;
        setSocket(tcp, connection);
        in = tcp.getInputStream();
        out = tcp.getOutputStream();
        Logger.i(TAG, "Connected via TCP 127.0.0.1:" + TCP_PORT);
      }
      else
      {
        final BluetoothSocket bluetooth = connectBluetoothSocket(address, connection);
        socket = bluetooth;
        in = bluetooth.getInputStream();
        out = bluetooth.getOutputStream();
      }
      synchronized (this)
      {
        if (connection != mConnection)
          throw new IOException("Closed");
        mOutput = out;
      }
      post(connection, NoGps::nativeOnElm327Connected);
      final byte[] buffer = new byte[256];
      while (true)
      {
        final int read = in.read(buffer);
        if (read < 0)
          throw new IOException("Connection closed");
        final byte[] bytes = Arrays.copyOf(buffer, read);
        post(connection, () -> NoGps.nativeOnElm327Bytes(bytes));
      }
    }
    catch (IOException | SecurityException e)
    {
      Logger.w(TAG, "ELM327 connection closed", e);
      final String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
      post(connection, () -> NoGps.nativeOnElm327Closed(reason));
    }
    finally
    {
      if (socket != null)
      {
        synchronized (this)
        {
          if (mSocket == socket)
          {
            mSocket = null;
            mOutput = null;
          }
        }
        closeQuietly(socket);
      }
    }
  }

  // The port is supplied separately so JVM tests can use an ephemeral loopback port.
  @NonNull
  static Socket connectTcpSocket(int port) throws IOException
  {
    final Socket socket = new Socket();
    try
    {
      socket.connect(new InetSocketAddress("127.0.0.1", port), TCP_CONNECT_TIMEOUT_MS);
      return socket;
    }
    catch (IOException | RuntimeException e)
    {
      try
      {
        socket.close();
      }
      catch (IOException closeError)
      {
        e.addSuppressed(closeError);
      }
      throw e;
    }
  }

  @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is checked when the adapter is chosen.
  @NonNull
  private BluetoothSocket connectBluetoothSocket(@NonNull String address, int connection) throws IOException
  {
    if (connection != mConnection)
      throw new IOException("Closed");
    final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    if (adapter == null || !adapter.isEnabled())
      throw new IOException("Bluetooth is off");
    final BluetoothDevice device = adapter.getRemoteDevice(address);
    BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
    setSocket(socket, connection);
    try
    {
      socket.connect();
    }
    catch (IOException e)
    {
      Logger.i(TAG, "Bluetooth SPP secure connection failed; trying insecure connection", e);
      // Many cheap clones work only with an insecure connection. A closed connection is not tried again: it would
      // block the adapter for a new one for seconds.
      closeQuietly(socket);
      if (connection != mConnection)
        throw e;
      socket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
      setSocket(socket, connection);
      try
      {
        socket.connect();
      }
      catch (IOException | SecurityException insecureError)
      {
        closeQuietly(socket);
        throw insecureError;
      }
    }
    catch (SecurityException e)
    {
      closeQuietly(socket);
      throw e;
    }
    // Closed while connecting: the adapter must not be kept busy.
    if (connection != mConnection)
    {
      closeQuietly(socket);
      throw new IOException("Closed");
    }
    Logger.i(TAG, "Connected via Bluetooth SPP to " + address);
    return socket;
  }

  private void post(int connection, @NonNull Runnable task)
  {
    mMainHandler.post(() -> {
      if (connection == mConnection)
        task.run();
    });
  }

  private synchronized void setSocket(@NonNull Closeable socket, int connection) throws IOException
  {
    if (connection != mConnection)
    {
      closeQuietly(socket);
      throw new IOException("Closed");
    }
    mSocket = socket;
  }

  private synchronized void closeSocket()
  {
    final Closeable socket = mSocket;
    mSocket = null;
    mOutput = null;
    if (socket != null)
      closeQuietly(socket);
  }

  private static void closeQuietly(@NonNull Closeable socket)
  {
    try
    {
      socket.close();
    }
    catch (IOException e)
    {
      Logger.w(TAG, "Failed to close the ELM327 socket", e);
    }
  }
}
