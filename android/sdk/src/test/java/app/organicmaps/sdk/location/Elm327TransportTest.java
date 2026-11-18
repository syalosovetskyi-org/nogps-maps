package app.organicmaps.sdk.location;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class Elm327TransportTest
{
  private static ServerSocket proxy() throws IOException
  {
    final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    server.setSoTimeout(2000);
    return server;
  }

  @Test(timeout = 5000)
  public void tcpExchangesElm327Bytes() throws Exception
  {
    try (ServerSocket server = proxy(); Socket client = Elm327Transport.connectTcpSocket(server.getLocalPort());
         Socket peer = server.accept())
    {
      client.setSoTimeout(2000);
      peer.setSoTimeout(2000);
      assertTrue(client.getInetAddress().isLoopbackAddress());
      final byte[] command = "010D\r".getBytes(US_ASCII);
      client.getOutputStream().write(command);
      client.getOutputStream().flush();
      assertArrayEquals(command, peer.getInputStream().readNBytes(command.length));

      // TCP can split an ELM327 response anywhere, including before its prompt.
      final byte[] response = "410D2A\r".getBytes(US_ASCII);
      peer.getOutputStream().write(response);
      peer.getOutputStream().flush();
      assertArrayEquals(response, client.getInputStream().readNBytes(response.length));
      peer.getOutputStream().write('>');
      peer.getOutputStream().flush();
      assertEquals('>', client.getInputStream().read());
    }
  }

  @Test(timeout = 5000)
  public void tcpReportsEofAndCanReconnect() throws Exception
  {
    try (ServerSocket server = proxy())
    {
      for (int attempt = 0; attempt < 2; ++attempt)
      {
        try (Socket client = Elm327Transport.connectTcpSocket(server.getLocalPort()))
        {
          client.setSoTimeout(2000);
          try (Socket peer = server.accept())
          {
            peer.getOutputStream().write('>');
          }
          assertEquals('>', client.getInputStream().read());
          assertEquals(-1, client.getInputStream().read());
        }
      }
    }
  }

  @Test(timeout = 5000)
  public void closingTcpUnblocksReader() throws Exception
  {
    try (ServerSocket server = proxy(); Socket client = Elm327Transport.connectTcpSocket(server.getLocalPort());
         Socket peer = server.accept())
    {
      final CompletableFuture<Void> reading = new CompletableFuture<>();
      final CompletableFuture<Integer> read = CompletableFuture.supplyAsync(() -> {
        try
        {
          reading.complete(null);
          return client.getInputStream().read();
        }
        catch (IOException e)
        {
          throw new UncheckedIOException("ELM327 read interrupted by close", e);
        }
      });
      reading.get(2, TimeUnit.SECONDS);
      client.close();
      final ExecutionException error = assertThrows(ExecutionException.class, () -> read.get(2, TimeUnit.SECONDS));
      assertTrue(error.getCause() instanceof UncheckedIOException);
    }
  }

  @Test(timeout = 5000)
  public void absentTcpProxyThrowsIOException() throws Exception
  {
    final int port;
    try (ServerSocket server = proxy())
    {
      port = server.getLocalPort();
    }
    assertThrows(IOException.class, () -> Elm327Transport.connectTcpSocket(port));
  }
}
