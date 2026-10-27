# Preview validation

The local suite passes **118 checks** on Windows with JDK 25.0.4.1, compiling the tool with `--release 21`. Checks cover exact aggregation, histogram bounds, overlapping pause union, overflow/resource limits, owner edges, weighted/root-first profiles, comparison rates and missing-event semantics, markup/control escaping, deterministic resource redaction, CLI gates, invalid/truncated recordings, output preservation, and actual JDK event decoding.

The recording fixture is created by a separate Java example with real file I/O, contended monitors, parking, loopback socket I/O, a short sampled CPU workload, a caught exception, and requested GC. It is synthetic and is never committed or included in release archives. Tests do not require Internet targets, a production service, a Java agent, or credentials.

The CLI's core has no downloaded dependencies. A separate Python standard-library checker validates generated JSON/profile invariants. Speedscope compatibility is checked against its public JSON schema during release verification. GitHub Actions provides the Java 21/25 Linux/Windows/macOS matrix and gates tagged release packaging.

Local validation does not establish throughput on production-scale captures, arbitrary JDK/vendor event compatibility, exact profiler attribution, safe processing of malicious JFR metadata, root-cause diagnosis, or production performance improvement. The tool is a developer preview. Inspect the current commit's Actions result and release checksums for the publication being installed.
