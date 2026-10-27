Rudy Flightdeck is an offline Java Flight Recorder investigation engine with a Java 21+ core and no downloaded runtime dependencies.

- Weighted Java/native/allocation stack profiles and Speedscope export.
- Thread/resource I/O and wait aggregates, historical monitor-owner edges, and unioned top-level GC pauses.
- Paired-recording rate/sample-mix comparison, explicit capture coverage, data-loss status, and optional warning gates.
- Self-contained interactive HTML and versioned JSON reports; field-specific redaction and new-file-only output.
- Synthetic recording example, meaningful regression suite, and Java 21/25 CI on Linux, Windows, and macOS.

Verify `SHA256SUMS.txt` before extracting a distribution. The JAR is portable; Java 21+ with `jdk.jfr` must already be installed. Use trusted recordings and a patched JDK. This preview is not a JFR parser sandbox, exact CPU/heap profiler, vulnerability detector, or deadlock proof system. Read the measurement and security boundaries before sharing recordings or reports.
