# Architecture and measurement contract

```text
trusted recording + limits
          |
 SHA-256 -> JDK RecordingFile stream -> SHA-256 recheck
          |
 explicit JDK-event adapter (selected fields only)
          |
 shared budget / interned symbols / exact counters / logarithmic histograms
          |
          +-- stack groups (root-first, sample counts or allocation weights)
          +-- thread/resource aggregates and historical monitor-owner edges
          +-- sparse start-second event buckets
          +-- top-level GC intervals -> sorted interval union
          |
          +-- investigation thresholds + explicit capture coverage
          +-- paired-recording rates + CPU sample-mix deltas
          |
          +-- text / versioned JSON / local HTML / Speedscope
```

`JfrInput` owns JDK field decoding and privacy transformation. `Core` owns aggregation and limits independently of JFR. `Reports` defines report data and comparisons. `Json` bounds serialization and escapes HTML script delimiters. `Html` renders an interactive report without external code. `Main` owns usage, output creation, and exit/gate precedence. The standard-JDK build helper compiles with a Java 21 target and strict warnings.

## Time and duration

All decoded event timestamps, including unrecognized event types, determine the earliest start and latest end. Their difference is the **observed event span**, not an assertion about the full recording's configured duration. Inter-event quiet periods remain inside that span. A nonpositive span makes normalized rates unavailable.

Event-duration totals sum recorded intervals across threads and can exceed elapsed time. File/socket events describe the recorder's instrumented operations; they are not pure device/network service time. Parks are not automatically treated as a defect. The sparse timeline assigns each recognized event's count and full recorded duration to its start-second bucket. The HTML chart displays rebucketed counts, not a reconstruction of concurrent occupancy.

Top-level `jdk.GCPhasePause` intervals are sorted and unioned before calculating pause share. Nested `jdk.GCPhasePauseLevel*` events and concurrent `jdk.GarbageCollection` duration are counted only in the raw inventory, not as additional pause time. Overlapping duplicate top-level intervals do not double-count the union.

The duration histogram uses base-two nanosecond ranges: 0, 1, 2–3, 4–7, and so on. `p95UpperMs` is the upper edge containing the nearest-rank 95th percentile; it is an approximate upper bound, not an exact percentile. Exact count, summed duration, maximum, and byte totals are maintained separately. Arithmetic overflow fails analysis.

## Profiles and owners

JFR frame lists are leaf-first; the adapter reverses retained frames into root-first paths. CPU/native profile weights are sample counts. Allocation weights come from `jdk.ObjectAllocationSample.weight`; they are estimated allocated bytes, not retained objects. Samples without stacks remain in metric counts but cannot become profile stacks. A frame cap or JFR truncation flag marks sampled-stack coverage incomplete at that depth.

Speedscope includes all aggregated nonzero sampled stacks and deduplicates shared frame names. Its `endValue` equals the sum of weights. JSON/HTML top-stack tables apply `--top` separately to CPU, native, and allocation profiles; the interactive chart is the displayed CPU subset, not every sample. Historical monitor-owner edges join the blocked thread, recorded previous owner, and monitor class. Unknown/self owners are excluded. An edge is not a contemporaneous ownership claim.

## Comparisons

Each recording is read under the same caller-selected limits/privacy mode. Event rates use each recording's observed span. A kind is marked incomparable if either side has no corresponding events or no positive span; missing activity is not silently treated as zero. Wait kinds additionally expose summed recorded milliseconds per observed second.

CPU changes compare each root-first stack's share of all observed Java execution samples, including those without retained stacks in the denominator. Differences are percentage points. New warning IDs can gate CI; changed sample mix or a rate delta alone does not produce a performance-regression verdict. Match recorder settings and representative workloads before interpreting a comparison.

## Resource ownership

Only aggregate maps, interned bounded symbols, selected stack keys, and bounded top-level GC intervals are retained. No complete raw-event list is built. A shared group budget covers frame symbols and entries in every aggregation map. Report serialization has a separate character ceiling. Input hashes are computed in bounded-buffer passes before and after event decoding. Those checks detect ordinary changes, not adversarial filesystem races controlled by a local administrator.
