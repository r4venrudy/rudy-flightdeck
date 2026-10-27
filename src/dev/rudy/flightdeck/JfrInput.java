package dev.rudy.flightdeck;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import jdk.jfr.consumer.*;
import static dev.rudy.flightdeck.Core.*;

/** Uses the supported JDK consumer. This is not a sandbox for hostile JFR files. */
final class JfrInput {
  private static final Map<String, Kind> KINDS = Map.ofEntries(
      Map.entry("jdk.ExecutionSample", Kind.CPU), Map.entry("jdk.NativeMethodSample", Kind.NATIVE),
      Map.entry("jdk.ObjectAllocationSample", Kind.ALLOCATION), Map.entry("jdk.JavaMonitorEnter", Kind.MONITOR),
      Map.entry("jdk.ThreadPark", Kind.PARK), Map.entry("jdk.FileRead", Kind.FILE_READ),
      Map.entry("jdk.FileWrite", Kind.FILE_WRITE), Map.entry("jdk.SocketRead", Kind.SOCKET_READ),
      Map.entry("jdk.SocketWrite", Kind.SOCKET_WRITE), Map.entry("jdk.GCPhasePause", Kind.GC_PAUSE),
      Map.entry("jdk.JavaExceptionThrow", Kind.EXCEPTION), Map.entry("jdk.ProcessStart", Kind.PROCESS));

  static Analysis read(Path path, Limits limits, boolean sensitive) throws IOException {
    if (!Files.isRegularFile(path) || Files.size(path) > limits.fileBytes())
      throw new IOException("input: require a regular JFR file within --max-file-mib");
    Analysis result = new Analysis(limits, path.getFileName().toString());
    String before = digest(path, limits.fileBytes());
    try (RecordingFile input = new RecordingFile(path)) {
      while (input.hasMoreEvents()) {
        RecordedEvent event = input.readEvent();
        String type = event.getEventType().getName();
        result.observe(type, event.getStartTime(), event.getEndTime());
        if (type.equals("jdk.DataLoss")) { result.loss(number(event, "amount")); continue; }
        Kind kind = KINDS.get(type);
        if (kind == null) continue;
        long nanos = event.getDuration().toNanos();
        RecordedThread recordedThread = event.hasField("sampledThread") ? event.getThread("sampledThread") : event.getThread();
        String thread = thread(recordedThread, sensitive);
        String owner = kind == Kind.MONITOR && event.hasField("previousOwner") ? thread(event.getThread("previousOwner"), sensitive) : null;
        String resource = resource(event, kind, sensitive);
        RecordedStackTrace trace = event.getStackTrace();
        List<String> frames = new ArrayList<>();
        boolean truncated = trace != null && trace.isTruncated();
        if (trace != null && (kind == Kind.CPU || kind == Kind.NATIVE || kind == Kind.ALLOCATION)) {
          List<RecordedFrame> raw = trace.getFrames();
          truncated |= raw.size() > limits.stackDepth();
          for (RecordedFrame frame : raw.subList(0, Math.min(raw.size(), limits.stackDepth()))) {
            RecordedMethod method = frame.getMethod();
            if (method != null) frames.add(method.getType().getName() + "." + method.getName() + method.getDescriptor());
          }
          Collections.reverse(frames); // JFR is leaf-first; sampled profiles are root-first.
        }
        long bytes = switch (kind) {
          case ALLOCATION -> number(event, "weight");
          case FILE_READ, SOCKET_READ -> Math.max(0, number(event, "bytesRead"));
          case FILE_WRITE, SOCKET_WRITE -> Math.max(0, number(event, "bytesWritten"));
          default -> 0;
        };
        if (bytes < 0) throw new IOException("invalid: negative allocation weight");
        result.accept(new Datum(kind, event.getStartTime(), nanos, thread, resource, frames, bytes, owner, truncated));
      }
    } catch (IllegalArgumentException | ArithmeticException ex) {
      throw new IOException("input: invalid or unsupported JFR event fields", ex);
    }
    if (!before.equals(digest(path, limits.fileBytes()))) throw new IOException("input: recording changed during analysis");
    result.sha256 = before;
    if (result.read == 0) throw new IOException("input: recording contains no events");
    return result;
  }

  static String digest(Path path, long maxBytes) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = Files.newInputStream(path)) {
        byte[] buffer = new byte[65536]; long size = 0; int read;
        while ((read = in.read(buffer)) >= 0) {
          size += read; if (size > maxBytes) throw new IOException("limit: file grew beyond byte budget");
          digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
  }
  private static long number(RecordedObject object, String name) { return object.hasField(name) ? object.getLong(name) : 0; }
  private static String klass(RecordedObject event, String name) {
    RecordedClass type = event.hasField(name) ? event.getClass(name) : null;
    return type == null ? "unknown" : type.getName();
  }
  static String thread(RecordedThread thread, boolean sensitive) {
    if (thread == null) return "unknown";
    String id = "java:" + thread.getJavaThreadId() + "/os:" + thread.getOSThreadId();
    return sensitive ? id + " " + Objects.requireNonNullElse(thread.getJavaName(), "") : id;
  }
  static String token(String input, boolean sensitive) {
    if (input == null || input.isEmpty()) return "unknown";
    if (sensitive) return input;
    try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
  }
  private static String resource(RecordedEvent event, Kind kind, boolean sensitive) {
    return switch (kind) {
      case FILE_READ, FILE_WRITE -> token(event.hasField("path") ? event.getString("path") : null, sensitive);
      case SOCKET_READ, SOCKET_WRITE -> token((event.hasField("host") ? event.getString("host") : "") + "|"
          + (event.hasField("address") ? event.getString("address") : "") + ":" + number(event, "port"), sensitive);
      case ALLOCATION -> klass(event, "objectClass");
      case MONITOR -> klass(event, "monitorClass");
      case PARK -> klass(event, "parkedClass");
      case EXCEPTION -> klass(event, "thrownClass");
      default -> kind.name().toLowerCase(Locale.ROOT);
    };
  }
  private JfrInput() {}
}
