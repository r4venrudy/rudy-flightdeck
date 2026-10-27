# Supported events and thresholds

| JDK event | Aggregation |
| --- | --- |
| `jdk.ExecutionSample` | Sampled thread; root-first Java execution stacks; count weights |
| `jdk.NativeMethodSample` | Native-method sample stacks; count weights |
| `jdk.ObjectAllocationSample` | Object class; estimated byte weight; stack profile |
| `jdk.JavaMonitorEnter` | Blocked duration; monitor class; previous-owner edge |
| `jdk.ThreadPark` | Park duration and parked class; no automatic stall warning |
| `jdk.FileRead`, `jdk.FileWrite` | Path token, recorded bytes and operation durations |
| `jdk.SocketRead`, `jdk.SocketWrite` | Endpoint token, recorded bytes and operation durations |
| `jdk.GCPhasePause` | Top-level pause duration and interval union |
| `jdk.JavaExceptionThrow` | Recorded thrown class and count; exception message omitted |
| `jdk.ProcessStart` | Recorded count; command and arguments omitted |
| `jdk.DataLoss` | Incomplete-recording status and reported lost bytes |
| Other events | Event-name inventory and total decoded-event budget only |

Availability and semantics depend on the JDK and recorder configuration. Optional absent fields become unknown/zero measurements; missing event types do not establish that recording was enabled. The adapter does not interpret arbitrary custom-event payloads.

## Investigation prompts

| ID | Level | Trigger |
| --- | --- | --- |
| `FD.COVERAGE.DATA_LOSS` | warning | A JFR data-loss event is observed; exit status becomes incomplete |
| `FD.COVERAGE.NO_CPU` | info | No Java execution samples observed |
| `FD.COVERAGE.TRUNCATED_STACKS` | warning | More than 10% of sampled-stack events are truncated by JFR or the frame cap |
| `FD.GC.LONG_PAUSE` | warning | A top-level recorded pause is at least 200 ms |
| `FD.GC.PAUSE_SHARE` | warning | Unioned pause intervals cover at least 5% of a positive observed event span |
| `FD.STALL.MONITOR` | warning | A recorded contended monitor acquisition lasts at least 100 ms |
| `FD.STALL.FILE_READ`, `FD.STALL.FILE_WRITE` | warning | A recorded operation lasts at least 250 ms |
| `FD.STALL.SOCKET_READ`, `FD.STALL.SOCKET_WRITE` | warning | A recorded operation lasts at least 250 ms |

These preview thresholds are fixed investigative defaults. A socket waiting on an idle peer, a deliberate lock, an expected full collection, or an intentional long poll can be normal. Repeated warning IDs are aggregated as leads, not separate vulnerabilities.
