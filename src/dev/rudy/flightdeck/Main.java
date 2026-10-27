package dev.rudy.flightdeck;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static dev.rudy.flightdeck.Core.*;

public final class Main {
  static final String HELP = """
      Rudy Flightdeck — offline Java Flight Recorder investigations
      Usage:
        flightdeck analyze INPUT.jfr [options]
        flightdeck compare BASELINE.jfr CANDIDATE.jfr [options]
      Options:
        --format text|json|html|speedscope  Default: text; speedscope is analyze-only
        --output PATH                     Create a new report; existing files are refused
        --top N                           Top rows per category, 1..200 (default: 20)
        --fail-on none|warning             Compare gates newly introduced warning IDs
        --include-sensitive               Include raw file/endpoint values and thread names
        --max-file-mib N                  File limit, 1..8192 (default: 512)
        --max-events N                    Event limit, 1..20000000 (default: 2000000)
        --max-groups N                    Groups/symbols limit, 1..1000000 (default: 100000)
        --stack-depth N                   Retained frame limit, 1..512 (default: 128)
        --max-gc-intervals N               Retained pause limit, 1..1000000 (default: 100000)
        --help / --version
      Exit: 0 completed; 1 requested warning gate; 2 input/usage/incomplete recording.
      Requires Java 21+. Use a patched JDK and trusted JFR inputs. No live JVM attachment.
      """;
  public static void main(String[] arguments) { System.exit(run(arguments, System.out, System.err)); }
  static int run(String[] arguments, PrintStream output, PrintStream error) {
    try {
      if (arguments.length == 1 && arguments[0].equals("--version")) { output.println("flightdeck " + VERSION); return 0; }
      if (arguments.length == 1 && (arguments[0].equals("--help") || arguments[0].equals("-h"))) { output.print(HELP); return 0; }
      if (arguments.length < 2 || !Set.of("analyze", "compare").contains(arguments[0])) throw new IllegalArgumentException("Expected analyze or compare. Use --help.");
      boolean compare = arguments[0].equals("compare"), sensitive = false, fail = false;
      String format = "text"; Path destination = null; int top = 20;
      Limits defaults = Limits.defaults(); long file = defaults.fileBytes(), events = defaults.events();
      int groups = defaults.groups(), depth = defaults.stackDepth(), intervals = defaults.intervals();
      var paths = new ArrayList<Path>();
      for (int i = 1; i < arguments.length; i++) {
        String arg = arguments[i];
        if (arg.equals("--include-sensitive")) { sensitive = true; continue; }
        if (arg.startsWith("--")) {
          if (i + 1 == arguments.length) throw new IllegalArgumentException("Missing value for " + arg);
          String value = arguments[++i];
          switch (arg) {
            case "--format" -> format = value;
            case "--output" -> { if (destination != null) throw new IllegalArgumentException("Duplicate --output"); destination = Path.of(value); }
            case "--top" -> top = (int)positive(value, 200);
            case "--fail-on" -> { if (!Set.of("none", "warning").contains(value)) throw new IllegalArgumentException("--fail-on requires none|warning"); fail = value.equals("warning"); }
            case "--max-file-mib" -> file = positive(value, 8192) * 1024 * 1024;
            case "--max-events" -> events = positive(value, 20_000_000);
            case "--max-groups" -> groups = (int)positive(value, 1_000_000);
            case "--stack-depth" -> depth = (int)positive(value, 512);
            case "--max-gc-intervals" -> intervals = (int)positive(value, 1_000_000);
            default -> throw new IllegalArgumentException("Unknown option: " + arg);
          }
        } else paths.add(Path.of(arg));
      }
      if (paths.size() != (compare ? 2 : 1)) throw new IllegalArgumentException("Wrong number of input recordings");
      if (!Set.of("text", "json", "html", "speedscope").contains(format) || compare && format.equals("speedscope")) throw new IllegalArgumentException("Unsupported report format for this command");
      if (destination != null && Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new IOException("output: existing files and links are refused");
      Limits limits = new Limits(file, events, groups, depth, intervals);
      Analysis before = JfrInput.read(paths.getFirst(), limits, sensitive);
      Analysis after = compare ? JfrInput.read(paths.get(1), limits, sensitive) : null;
      Object data = compare ? Reports.comparison(before, after, top) : format.equals("speedscope") ? Reports.speedscope(before) : Reports.data(before, top, sensitive);
      String report = switch (format) {
        case "text" -> compare ? Reports.comparisonText(before, after) : Reports.text(before, top);
        case "html" -> Html.render(data, compare);
        default -> Json.write(data) + "\n";
      };
      if (destination == null) { output.print(report); output.flush(); if (output.checkError()) throw new IOException("output: report write failed"); }
      else try (OutputStream out = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { out.write(report.getBytes(StandardCharsets.UTF_8)); }
      return exitStatus(before, after, fail);
    } catch (IOException | IllegalArgumentException | ArithmeticException ex) {
      error.println("flightdeck: " + Reports.visible(Objects.requireNonNullElse(ex.getMessage(), "analysis failed"))); return 2;
    }
  }
  static long positive(String text, long maximum) {
    if (!text.matches("[0-9]{1,10}")) throw new IllegalArgumentException("Expected a bounded positive decimal integer");
    long value = Long.parseLong(text); if (value < 1 || value > maximum) throw new IllegalArgumentException("Numeric option outside 1.." + maximum); return value;
  }
  static int exitStatus(Analysis before, Analysis after, boolean fail) {
    if (before.incomplete() || after != null && after.incomplete()) return 2;
    if (fail && (after != null ? Reports.warningIds(after).stream().anyMatch(id -> !Reports.warningIds(before).contains(id)) : !Reports.warningIds(before).isEmpty())) return 1;
    return 0;
  }
  private Main() {}
}
