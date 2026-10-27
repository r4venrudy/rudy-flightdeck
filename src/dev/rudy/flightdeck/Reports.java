package dev.rudy.flightdeck;

import java.util.*;
import static dev.rudy.flightdeck.Core.*;

final class Reports {
  static final List<String> CAVEATS = List.of(
      "Use trusted recordings with a patched JDK; application budgets do not sandbox the JDK's JFR parser.",
      "Counts describe observed events. Missing events do not establish that an activity did not occur or that its recorder setting was enabled.",
      "CPU profiles are sample counts, not CPU utilization. Allocation weights estimate allocation, not retained heap or exact object sizes.",
      "Wait durations sum across threads and can exceed elapsed time. Histogram p95UpperMs is an approximate upper bound.",
      "Timeline buckets use event start time; they do not distribute duration over the covered seconds.",
      "Monitor-owner edges are historical observations, not proof of a simultaneous wait graph or deadlock.",
      "Default redaction hashes paths/endpoints and omits thread names; class/method symbols and the input basename remain visible.");

  static Map<String, Object> summary(Analysis a) {
    return map("source", a.source, "sha256", a.sha256, "observedStart", a.first == null ? null : a.first.toString(),
        "observedEnd", a.last == null ? null : a.last.toString(), "observedSeconds", a.seconds(),
        "eventsRead", a.read, "recognizedEvents", a.recognized, "complete", !a.incomplete(),
        "gcPauseUnionMs", ms(a.pauseUnion()), "gcPauseShare", a.seconds() > 0 ? a.pauseUnion() / 1e9 / a.seconds() : null);
  }
  static List<Map<String, Object>> findings(List<Finding> input) {
    return input.stream().map(f -> map("id", f.id(), "level", f.level(), "title", f.title(), "evidence", f.evidence())).toList();
  }
  static List<Map.Entry<StackKey, Long>> orderedStacks(Analysis a, Kind kind) {
    return a.stacks.entrySet().stream().filter(e -> e.getKey().kind() == kind)
        .sorted(Comparator.<Map.Entry<StackKey, Long>>comparingLong(Map.Entry::getValue).reversed()
            .thenComparing(e -> String.join("\n", e.getKey().frames()))).toList();
  }
  static Map<String, Object> data(Analysis a, int top, boolean sensitive) {
    var metricData = new LinkedHashMap<String, Object>();
    for (Kind kind : Kind.values()) metricData.put(kind.name(), a.metric(kind).data());
    var resourceData = new ArrayList<Map<String, Object>>();
    for (Kind kind : Kind.values()) a.resources.entrySet().stream().filter(e -> e.getKey().startsWith(kind + ":"))
        .sorted(Comparator.<Map.Entry<String, Stats>>comparingLong(e -> e.getValue().nanos).reversed()
            .thenComparing(Comparator.<Map.Entry<String, Stats>>comparingLong(e -> e.getValue().bytes).reversed())
            .thenComparing(Map.Entry::getKey)).limit(top)
        .forEach(e -> resourceData.add(map("kind", kind.name(), "resource", e.getKey().substring(kind.name().length() + 1), "stats", e.getValue().data())));
    var threadData = a.threads.entrySet().stream()
        .sorted(Comparator.<Map.Entry<String, Stats>>comparingLong(e -> e.getValue().nanos).reversed().thenComparing(Map.Entry::getKey))
        .limit(top).map(e -> map("thread", e.getKey(), "recordedEvents", e.getValue().count, "summedWaitMs", ms(e.getValue().nanos))).toList();
    var profiles = new LinkedHashMap<String, Object>();
    for (Kind kind : List.of(Kind.CPU, Kind.NATIVE, Kind.ALLOCATION)) {
      profiles.put(kind.name(), orderedStacks(a, kind).stream().limit(top)
          .map(e -> map("frames", e.getKey().frames(), "weight", e.getValue(), "unit", kind == Kind.ALLOCATION ? "estimated-bytes" : "samples")).toList());
    }
    var edges = a.edges.entrySet().stream()
        .sorted(Comparator.<Map.Entry<Edge, Stats>>comparingLong(e -> e.getValue().nanos).reversed()
            .thenComparing(e -> e.getKey().toString())).limit(top)
        .map(e -> map("waiter", e.getKey().waiter(), "owner", e.getKey().owner(), "monitorClass", e.getKey().monitor(), "stats", e.getValue().data())).toList();
    var timeline = a.timeline.entrySet().stream().map(e -> {
      var values = new LinkedHashMap<String, Object>();
      for (Kind kind : Kind.values()) if (e.getValue().containsKey(kind)) values.put(kind.name(), e.getValue().get(kind).data());
      return map("epochSecond", e.getKey(), "metrics", values);
    }).toList();
    return map("schema", "rudy-flightdeck/v1", "version", VERSION, "summary", summary(a),
        "redaction", sensitive ? "sensitive-values-included" : "paths-endpoints-hashed-thread-names-omitted",
        "coverage", map("eventsByType", a.eventTypes, "sampledStacksRecorded", a.stacksRecorded,
            "sampledStacksTruncated", a.stacksTruncated, "dataLossEvents", a.lossEvents, "dataLossBytes", a.lossBytes),
        "metrics", metricData, "threads", threadData, "resources", resourceData, "stackProfiles", profiles,
        "monitorEdges", edges, "timeline", timeline, "findings", findings(a.findings()), "caveats", CAVEATS);
  }

  static Map<String, Object> speedscope(Analysis a) {
    var frameIndex = new LinkedHashMap<String, Integer>();
    var profiles = new ArrayList<Map<String, Object>>();
    for (Kind kind : List.of(Kind.CPU, Kind.NATIVE, Kind.ALLOCATION)) {
      var samples = new ArrayList<List<Integer>>(); var weights = new ArrayList<Long>(); long total = 0;
      for (var stack : orderedStacks(a, kind)) {
        if (stack.getValue() == 0) continue;
        var indices = new ArrayList<Integer>();
        for (String frame : stack.getKey().frames()) {
          frameIndex.computeIfAbsent(frame, ignored -> frameIndex.size()); indices.add(frameIndex.get(frame));
        }
        samples.add(indices); weights.add(stack.getValue()); total = Math.addExact(total, stack.getValue());
      }
      if (!samples.isEmpty()) profiles.add(map("type", "sampled", "name", kind == Kind.ALLOCATION ? "Estimated allocation bytes" : kind + " samples",
          "unit", kind == Kind.ALLOCATION ? "bytes" : "none", "startValue", 0, "endValue", total, "samples", samples, "weights", weights));
    }
    return map("$schema", "https://www.speedscope.app/file-format-schema.json", "name", "Rudy Flightdeck",
        "exporter", "rudy-flightdeck " + VERSION, "shared", map("frames", frameIndex.keySet().stream().map(name -> map("name", name)).toList()),
        "profiles", profiles);
  }
  static Map<String, Object> comparison(Analysis before, Analysis after, int top) {
    var metrics = new ArrayList<Map<String, Object>>();
    for (Kind kind : Kind.values()) {
      boolean comparable = before.seconds() > 0 && after.seconds() > 0 && before.metric(kind).count > 0 && after.metric(kind).count > 0;
      double left = comparable ? before.metric(kind).count / before.seconds() : 0;
      double right = comparable ? after.metric(kind).count / after.seconds() : 0;
      metrics.add(map("kind", kind.name(), "comparable", comparable, "unit", "observed-events/second",
          "baseline", comparable ? left : null, "candidate", comparable ? right : null, "delta", comparable ? right - left : null,
          "baselineSummedMsPerSecond", comparable && waitKind(kind) ? ms(before.metric(kind).nanos) / before.seconds() : null,
          "candidateSummedMsPerSecond", comparable && waitKind(kind) ? ms(after.metric(kind).nanos) / after.seconds() : null));
    }
    Set<String> oldWarnings = warningIds(before), newWarnings = warningIds(after);
    Set<StackKey> keys = new HashSet<>(before.stacks.keySet()); keys.addAll(after.stacks.keySet());
    var changes = new ArrayList<Map<String, Object>>();
    boolean cpuComparable = before.metric(Kind.CPU).count > 0 && after.metric(Kind.CPU).count > 0;
    if (cpuComparable) for (StackKey key : keys) if (key.kind() == Kind.CPU) {
      double left = before.stacks.getOrDefault(key, 0L) * 100.0 / before.metric(Kind.CPU).count;
      double right = after.stacks.getOrDefault(key, 0L) * 100.0 / after.metric(Kind.CPU).count;
      changes.add(map("frames", key.frames(), "baselinePercent", left, "candidatePercent", right, "deltaPercentagePoints", right - left));
    }
    changes.sort(Comparator.<Map<String, Object>>comparingDouble(e -> Math.abs((Double)e.get("deltaPercentagePoints"))).reversed()
        .thenComparing(e -> e.get("frames").toString()));
    return map("schema", "rudy-flightdeck-comparison/v1", "version", VERSION, "baseline", summary(before), "candidate", summary(after),
        "metrics", metrics, "cpuComparable", cpuComparable, "cpuStackChanges", changes.stream().limit(top).toList(),
        "newWarnings", findings(after.findings().stream().filter(f -> f.level().equals("warning") && !oldWarnings.contains(f.id())).toList()),
        "resolvedWarningIds", oldWarnings.stream().filter(id -> !newWarnings.contains(id)).sorted().toList(),
        "caveats", List.of("Compare recordings with matching recorder settings and representative workloads.",
            "A metric is incomparable if either recording has no corresponding events or no positive observed span.",
            "CPU percentage-point changes describe sample mix; they are not proof of a performance regression."));
  }
  static Set<String> warningIds(Analysis a) {
    Set<String> result = new TreeSet<>();
    for (Finding finding : a.findings()) if (finding.level().equals("warning")) result.add(finding.id());
    return result;
  }
  static String text(Analysis a, int top) {
    var out = new StringBuilder("Rudy Flightdeck " + VERSION + "\n");
    out.append("Source: ").append(visible(a.source)).append(" | SHA-256 ").append(a.sha256).append('\n');
    out.append(String.format(Locale.ROOT, "Events: %d read / %d recognized | observed span %.3f s | complete: %s%n", a.read, a.recognized, a.seconds(), !a.incomplete()));
    out.append("\nEVENT METRICS (summed durations can overlap across threads)\n");
    for (Kind kind : Kind.values()) { Stats s = a.metric(kind); if (s.count > 0)
      out.append(String.format(Locale.ROOT, "%-14s count=%-8d total_ms=%12.3f max_ms=%10.3f p95_upper_ms=%10.3f bytes=%d%n", kind, s.count, ms(s.nanos), ms(s.maximum), ms(s.percentileUpper(.95)), s.bytes)); }
    out.append("\nJAVA EXECUTION SAMPLE STACKS\n");
    for (var entry : orderedStacks(a, Kind.CPU).stream().limit(top).toList())
      out.append(entry.getValue()).append(" samples | ").append(visible(String.join(" -> ", entry.getKey().frames()))).append('\n');
    out.append("\nINVESTIGATION LEADS\n");
    for (Finding f : a.findings()) out.append(f.level()).append(" ").append(f.id()).append(" | ").append(f.title()).append(" | ").append(f.evidence()).append('\n');
    out.append("\n"); for (String caveat : CAVEATS) out.append("- ").append(caveat).append('\n');
    return out.toString();
  }
  static String comparisonText(Analysis before, Analysis after) {
    return "Rudy Flightdeck comparison\nBaseline: " + visible(before.source) + "\nCandidate: " + visible(after.source)
        + "\nNew warning IDs: " + warningIds(after).stream().filter(id -> !warningIds(before).contains(id)).toList()
        + "\nUse --format json or html for normalized rates and CPU sample-mix changes.\n";
  }
  static String visible(String value) {
    var out = new StringBuilder();
    for (char c : value.toCharArray()) { if (Character.isISOControl(c)) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c)); else out.append(c); }
    return out.toString();
  }
  private Reports() {}
}
