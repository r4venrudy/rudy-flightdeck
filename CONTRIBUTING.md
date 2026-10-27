# Contributing

Use a Java 21+ JDK and run `java tools/Build.java test`. The core has no downloaded dependencies. CI also checks Java 25 on Linux, Windows, and macOS. Prefer deterministic measurement tests and a minimal real-JFR integration fixture when changing field decoding. Never commit generated `.jfr` recordings or recordings from another person's service.

Keep sampling and duration semantics explicit. CPU samples are not utilization; allocations are weighted estimates; historical monitor edges are not simultaneous deadlocks; missing events do not prove absence. Add fields additively to the versioned JSON contracts or version the schema when changing meaning.

For parser-budget changes, test failure before reporting a clean result. For output changes, test Unicode, markup delimiters, terminal controls, stable ordering, and refusal of existing output paths. Run the independent JSON/profile checks with `python tools/verify_reports.py build/examples` when Python is available.

Small focused changes with a reproducible synthetic example are welcome. Follow [SECURITY.md](SECURITY.md) for sensitive reports.
