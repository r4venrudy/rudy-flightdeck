package dev.rudy.flightdeck;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import jdk.jfr.Recording;

/** Controlled synthetic workload; no JVM attachment or external network traffic. */
public final class CaptureDemo {
  private static volatile long sink;
  public static void capture(Path destination, long blockedMillis) throws Exception {
    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Refusing existing output");
    Path payload = Files.createTempFile("flightdeck-synthetic-", ".bin");
    Path recording = Files.createTempFile(destination.toAbsolutePath().getParent(), "flightdeck-recording-", ".tmp");
    try (Recording rec = new Recording()) {
      for (String event : new String[]{"jdk.FileRead", "jdk.FileWrite", "jdk.SocketRead", "jdk.SocketWrite",
          "jdk.JavaMonitorEnter", "jdk.ThreadPark", "jdk.GCPhasePause", "jdk.JavaExceptionThrow"})
        rec.enable(event).withThreshold(Duration.ZERO).withStackTrace();
      rec.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(5)).withStackTrace();
      rec.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(5)).withStackTrace();
      rec.enable("jdk.ObjectAllocationSample").withStackTrace();
      rec.start();
      try (OutputStream out = new FileOutputStream(payload.toFile())) { out.write(new byte[32768]); }
      try (InputStream in = new FileInputStream(payload.toFile())) { sink = in.readAllBytes().length; }
      Object monitor = new Object();
      Thread waiter;
      synchronized (monitor) {
        waiter = new Thread(() -> { synchronized (monitor) { sink++; } }, "synthetic-monitor-waiter");
        waiter.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (waiter.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.onSpinWait();
        if (waiter.getState() != Thread.State.BLOCKED) throw new IOException("Fixture waiter did not reach monitor");
        Thread.sleep(blockedMillis);
      }
      waiter.join(5000);
      if (waiter.isAlive()) throw new IOException("Fixture waiter did not finish");
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(12));
      loopback();
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(350);
      long value = 1;
      while (System.nanoTime() < deadline) { for (int i = 0; i < 10000; i++) value = Long.rotateLeft(value * 31 + i, 7); sink = value; }
      try { throw new IOException("Synthetic fixture exception"); } catch (IOException expected) { sink += expected.getClass().getName().length(); }
      System.gc();
      rec.stop(); rec.dump(recording);
      Files.copy(recording, destination); // No replace: an existing file/link is refused atomically.
    } finally { Files.deleteIfExists(payload); Files.deleteIfExists(recording); }
  }
  private static void loopback() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      server.setSoTimeout(5000);
      var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
      Thread responder = new Thread(() -> {
        try (Socket connection = server.accept()) {
          connection.setSoTimeout(5000); connection.getInputStream().readNBytes(3);
          Thread.sleep(15); connection.getOutputStream().write(new byte[]{1, 2, 3});
        } catch (IOException | InterruptedException ex) { failure.set(ex); }
      }, "synthetic-loopback-responder");
      responder.start();
      try (Socket client = new Socket()) {
        client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()), 5000);
        client.setSoTimeout(5000); client.getOutputStream().write(new byte[]{1, 2, 3}); client.getInputStream().readNBytes(3);
      }
      responder.join(6000);
      if (responder.isAlive() || failure.get() != null) throw new IOException("Loopback fixture failed", failure.get());
    }
  }
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("usage: java examples/dev/rudy/flightdeck/CaptureDemo.java NEW_RECORDING.jfr");
    capture(Path.of(args[0]), 130);
    System.out.println("Created synthetic recording: " + Path.of(args[0]).getFileName());
  }
  private CaptureDemo() {}
}
