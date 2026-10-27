# Security boundaries

Flightdeck consumes local JFR recordings with the JDK's supported `jdk.jfr.consumer` API. Oracle's API documentation advises using recordings from trusted sources. Use a patched JDK and isolate unfamiliar files with process-level memory/time limits; Flightdeck is not a sandbox or a replacement for JDK parser fixes.

The application enforces file/event/aggregation/symbol/interval/report budgets, computes SHA-256 before and after reading, and rejects changed or empty inputs. Event budgets are checked after the JDK decodes an event. JVM metadata and constant-pool allocation can occur before those checks. A heap limit can terminate the JVM without a structured report.

Analysis does not attach to a JVM, execute the recorded program, load its classes, resolve remote addresses, or transmit evidence. Only documented fields from selected JDK event types enter reports. Unknown event names are counted; arbitrary event fields and process command lines are omitted. The separate example producer creates local synthetic activity and loopback traffic.

Reports omit thread names and hash file/endpoint values by default. This is field-specific redaction. Symbols, IDs, input basenames, timestamps, activity patterns, and stable resource hashes can remain sensitive. Low-entropy hashes may be guessed. `--include-sensitive` includes raw resource values/thread names. Review recordings and reports before sharing either.

HTML embeds JSON with script-delimiter/control escaping, uses textContent for untrusted DOM values, and restricts executable code with a fixed-script SHA-256 CSP. It needs no external resources. Output files are created with CREATE_NEW and existing paths/links are refused. A failed write may leave a partial newly created output; old files are not replaced. Concurrent modification of local files by an administrator is outside the process's isolation boundary.

Report implementation defects through the repository's private vulnerability-reporting channel if available, or contact the maintainer privately through the contact details on the GitHub profile. Do not attach real recordings, private symbols, credentials, or identifiable service traffic to a public issue. Include a small synthetic reproduction, JDK version, command, and expected/actual behavior.
