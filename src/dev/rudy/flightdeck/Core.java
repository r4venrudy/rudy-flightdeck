package dev.rudy.flightdeck;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Streaming aggregation, independent of the JFR decoder and report renderers. */
final class Core {
  static final String VERSION = "0.1.0-preview";
  enum Kind { CPU, NATIVE, ALLOCATION, MONITOR, PARK, FILE_READ, FILE_WRITE, SOCKET_READ, SOCKET_WRITE, GC_PAUSE, EXCEPTION, PROCESS }
  record Limits(long fileBytes, long events, int groups, int stackDepth, int intervals) {
    static Limits defaults() { return new Limits(512L * 1024 * 1024, 2_000_000, 100_000, 128, 100_000); }
    Limits {
      if (fileBytes < 1 || fileBytes > 8L * 1024 * 1024 * 1024 || events < 1 || events > 20_000_000
          || groups < 1 || groups > 1_000_000 || stackDepth < 1 || stackDepth > 512 || intervals < 1 || intervals > 1_000_000)
        throw new IllegalArgumentException("Limits must be positive and within the documented hard ceilings.");
    }
  }
  record Datum(Kind kind, Instant start, long nanos, String thread, String resource,
               List<String> stack, long bytes, String owner, boolean truncated) {
    Datum {
      Objects.requireNonNull(kind); Objects.requireNonNull(start);
      if (nanos < 0 || bytes < 0) throw new IllegalArgumentException("Negative event measurement.");
      stack = List.copyOf(stack);
      thread = Objects.requireNonNullElse(thread, "unknown");
      resource = Objects.requireNonNullElse(resource, "unknown");
    }
  }
  record Interval(Instant start, Instant end) {}
  record StackKey(Kind kind, List<String> frames) { StackKey { frames = List.copyOf(frames); } }
  record Edge(String waiter, String owner, String monitor) {}
  record Finding(String id, String level, String title, String evidence) {}

  static final class Budget {
    private final int max;
    private int used;
    private long characters;
    private final Map<String, String> symbols = new HashMap<>();
    Budget(int max) { this.max = max; }
    void add() throws IOException { if (++used > max) throw new IOException("limit: distinct aggregation groups/symbols exceeded"); }
    String symbol(String value) throws IOException {
      if (value.length() > 4096) throw new IOException("limit: symbol exceeds 4096 characters");
      String prior = symbols.get(value);
      if (prior != null) return prior;
      add(); characters += value.length();
      if (characters > 8_000_000) throw new IOException("limit: retained symbol character budget exceeded");
      symbols.put(value, value); return value;
    }
    <K, V> V entry(Map<K, V> map, K key, java.util.function.Supplier<V> create) throws IOException {
      V value = map.get(key);
      if (value == null) { add(); value = create.get(); map.put(key, value); }
      return value;
    }
  }

  static final class Stats {
    long count, nanos, maximum, bytes;
    final long[] histogram = new long[64];
    void add(long duration, long size) throws IOException {
      try {
        count = Math.addExact(count, 1); nanos = Math.addExact(nanos, duration);
        bytes = Math.addExact(bytes, size); maximum = Math.max(maximum, duration);
        int bucket = duration == 0 ? 0 : 64 - Long.numberOfLeadingZeros(duration);
        histogram[bucket] = Math.addExact(histogram[bucket], 1);
      } catch (ArithmeticException ex) { throw new IOException("limit: aggregate measurement overflow", ex); }
    }
    long percentileUpper(double percentile) {
      if (count == 0) return 0;
      long rank = (long) Math.ceil(count * percentile), cumulative = 0;
      for (int i = 0; i < histogram.length; i++) {
        cumulative += histogram[i];
        if (cumulative >= rank) return i == 63 ? Long.MAX_VALUE : (1L << i) - 1;
      }
      return maximum;
    }
    Map<String, Object> data() {
      return map("count", count, "totalMs", nanos / 1e6, "maxMs", maximum / 1e6,
          "p95UpperMs", percentileUpper(.95) / 1e6, "bytes", bytes);
    }
  }

  static final class Analysis {
    final Limits limits;
    final Budget budget;
    final EnumMap<Kind, Stats> metrics = new EnumMap<>(Kind.class);
    final Map<String, Long> eventTypes = new TreeMap<>();
    final Map<String, Stats> threads = new TreeMap<>(), resources = new TreeMap<>();
    final Map<StackKey, Long> stacks = new HashMap<>();
    final Map<Edge, Stats> edges = new HashMap<>();
    final NavigableMap<Long, EnumMap<Kind, Stats>> timeline = new TreeMap<>();
    final List<Interval> pauses = new ArrayList<>();
    final String source;
    long read, recognized, lossEvents, lossBytes, stacksRecorded, stacksTruncated;
    Instant first, last;
    String sha256 = "";
    Analysis(Limits limits, String source) { this.limits = limits; this.source = source; budget = new Budget(limits.groups()); }
    void observe(String type, Instant start, Instant end) throws IOException {
      if (++read > limits.events()) throw new IOException("limit: event count exceeded");
      if (end.isBefore(start)) throw new IOException("invalid: event ends before its start");
      if (first == null || start.isBefore(first)) first = start;
      if (last == null || end.isAfter(last)) last = end;
      if (Duration.between(first, last).compareTo(Duration.ofDays(366)) > 0)
        throw new IOException("limit: observed event span exceeds 366 days");
      String name = budget.symbol(type);
      budget.entry(eventTypes, name, () -> 0L);
      eventTypes.put(name, eventTypes.get(name) + 1);
    }
    void loss(long bytes) throws IOException {
      if (bytes < 0) throw new IOException("invalid: negative JFR data-loss size");
      lossEvents++;
      try { lossBytes = Math.addExact(lossBytes, bytes); }
      catch (ArithmeticException ex) { throw new IOException("limit: data-loss measurement overflow", ex); }
    }
    void accept(Datum event) throws IOException {
      recognized++;
      Kind kind = event.kind();
      budget.entry(metrics, kind, Stats::new).add(event.nanos(), event.bytes());
      String thread = budget.symbol(event.thread());
      budget.entry(threads, thread, Stats::new).add(waitKind(kind) ? event.nanos() : 0, event.bytes());
      String resource = budget.symbol(kind + ":" + event.resource());
      budget.entry(resources, resource, Stats::new).add(event.nanos(), event.bytes());
      var bucket = budget.entry(timeline, event.start().getEpochSecond(), () -> new EnumMap<>(Kind.class));
      budget.entry(bucket, kind, Stats::new).add(event.nanos(), event.bytes());
      if (!event.stack().isEmpty() && (kind == Kind.CPU || kind == Kind.NATIVE || kind == Kind.ALLOCATION)) {
        List<String> frames = new ArrayList<>();
        for (String frame : event.stack()) frames.add(budget.symbol(frame));
        var key = new StackKey(kind, frames);
        long weight = kind == Kind.ALLOCATION ? event.bytes() : 1;
        long old = budget.entry(stacks, key, () -> 0L);
        try { stacks.put(key, Math.addExact(old, weight)); }
        catch (ArithmeticException ex) { throw new IOException("limit: stack weight overflow", ex); }
        stacksRecorded++; if (event.truncated()) stacksTruncated++;
      }
      if (kind == Kind.MONITOR && event.owner() != null && !event.owner().equals("unknown") && !event.owner().equals(thread)) {
        var edge = new Edge(thread, budget.symbol(event.owner()), budget.symbol(event.resource()));
        budget.entry(edges, edge, Stats::new).add(event.nanos(), 0);
      }
      if (kind == Kind.GC_PAUSE && event.nanos() > 0) {
        if (pauses.size() >= limits.intervals()) throw new IOException("limit: retained GC intervals exceeded");
        pauses.add(new Interval(event.start(), event.start().plusNanos(event.nanos())));
      }
    }
    double seconds() { return first == null ? 0 : Duration.between(first, last).toNanos() / 1e9; }
    Stats metric(Kind kind) { return metrics.getOrDefault(kind, new Stats()); }
    long pauseUnion() { return union(pauses); }
    List<Finding> findings() {
      List<Finding> out = new ArrayList<>();
      if (lossEvents > 0) out.add(new Finding("FD.COVERAGE.DATA_LOSS", "warning", "Recording reports data loss", lossEvents + " events; " + lossBytes + " bytes reported lost. Results are incomplete."));
      if (metric(Kind.CPU).count == 0) out.add(new Finding("FD.COVERAGE.NO_CPU", "info", "No Java execution samples observed", "Sampling may be disabled or the workload idle; absence does not establish low CPU use."));
      if (stacksRecorded > 0 && (double)stacksTruncated / stacksRecorded > .1)
        out.add(new Finding("FD.COVERAGE.TRUNCATED_STACKS", "warning", "More than 10% of sampled stacks are truncated", stacksTruncated + " of " + stacksRecorded + " retained sampled-stack events."));
      if (metric(Kind.GC_PAUSE).maximum >= 200_000_000L)
        out.add(new Finding("FD.GC.LONG_PAUSE", "warning", "A recorded top-level GC pause exceeds 200 ms", "Maximum " + ms(metric(Kind.GC_PAUSE).maximum) + " ms; concurrent GC duration is not counted as a pause."));
      if (seconds() > 0 && pauseUnion() / 1e9 / seconds() >= .05)
        out.add(new Finding("FD.GC.PAUSE_SHARE", "warning", "GC pause intervals cover at least 5% of the observed event span", "Overlapping top-level pause intervals are unioned. This ratio uses observed timestamps, not recording wall-clock metadata."));
      for (Kind kind : List.of(Kind.MONITOR, Kind.FILE_READ, Kind.FILE_WRITE, Kind.SOCKET_READ, Kind.SOCKET_WRITE)) {
        long threshold = kind == Kind.MONITOR ? 100_000_000L : 250_000_000L;
        if (metric(kind).maximum >= threshold)
          out.add(new Finding("FD.STALL." + kind, "warning", "Long recorded " + kind.name().toLowerCase(Locale.ROOT) + " event", "Maximum " + ms(metric(kind).maximum) + " ms. Investigative threshold; expected blocking may be normal."));
      }
      return List.copyOf(out);
    }
    boolean incomplete() { return lossEvents > 0; }
  }

  static boolean waitKind(Kind kind) { return switch (kind) {
    case MONITOR, PARK, FILE_READ, FILE_WRITE, SOCKET_READ, SOCKET_WRITE -> true;
    default -> false;
  }; }
  static long union(List<Interval> input) {
    if (input.isEmpty()) return 0;
    List<Interval> ordered = input.stream().sorted(Comparator.comparing(Interval::start)).toList();
    Instant start = ordered.getFirst().start(), end = ordered.getFirst().end(); long result = 0;
    for (Interval interval : ordered.subList(1, ordered.size())) {
      if (!interval.start().isAfter(end)) { if (interval.end().isAfter(end)) end = interval.end(); }
      else { result = Math.addExact(result, Duration.between(start, end).toNanos()); start = interval.start(); end = interval.end(); }
    }
    return Math.addExact(result, Duration.between(start, end).toNanos());
  }
  static double ms(long nanos) { return nanos / 1e6; }
  static Map<String, Object> map(Object... pairs) {
    var result = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) result.put((String)pairs[i], pairs[i + 1]);
    return result;
  }
  private Core() {}
}
