package dev.rudy.flightdeck;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static dev.rudy.flightdeck.Core.*;

/** Regression checks with deterministic measurements and real JDK-produced JFR fixtures. */
public final class Tests {
  private static int checks;
  private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");
  @FunctionalInterface interface Action { void run() throws Exception; }
  static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
  static void rejects(Action action, String label) throws Exception {
    try { action.run(); } catch (IOException | IllegalArgumentException | ArithmeticException expected) { check(true, label); return; }
    throw new AssertionError("Did not reject: " + label);
  }
  static Analysis analysis() { return new Analysis(Limits.defaults(), "synthetic.jfr"); }
  static void add(Analysis a, Kind kind, long millis, long duration, String thread, String resource, List<String> stack, long bytes, String owner, boolean truncated) throws IOException {
    Instant start = T.plusMillis(millis); a.observe("fixture." + kind, start, start.plusNanos(duration));
    a.accept(new Datum(kind, start, duration, thread, resource, stack, bytes, owner, truncated));
  }
  static void statistics() throws Exception {
    for (long value : new long[]{0,1,2,3,4,7,8,15,16,1024,Long.MAX_VALUE}) {
      var s = new Stats(); s.add(value, 17);
      check(s.count == 1 && s.nanos == value && s.maximum == value && s.bytes == 17, "measurement retained " + value);
      check(s.percentileUpper(.95) >= value, "quantile upper bounds observation " + value);
      if(value > 0 && value < Long.MAX_VALUE / 2) check(s.percentileUpper(.95) < 2 * value, "histogram bound within factor two " + value);
    }
    var s = new Stats(); for (int i=1;i<=100;i++) s.add(i,0);
    check(s.percentileUpper(.95) == 127, "p95 uses upper edge of 64..127 bin");
    check(s.count == 100 && s.nanos == 5050, "repeated aggregation exact");
    var overflow = new Stats(); overflow.add(Long.MAX_VALUE,0); rejects(()->overflow.add(1,0),"aggregate overflow");
    check(new Stats().percentileUpper(.95)==0,"empty histogram");
  }
  static Interval interval(long start, long end) { return new Interval(T.plusNanos(start), T.plusNanos(end)); }
  static void intervals() {
    check(union(List.of())==0,"empty GC intervals");
    check(union(List.of(interval(0,10)))==10,"single GC interval");
    check(union(List.of(interval(0,10),interval(5,20)))==20,"overlapping pauses are unioned");
    check(union(List.of(interval(10,20),interval(0,10)))==20,"touching unordered intervals");
    check(union(List.of(interval(0,100),interval(20,30),interval(0,100)))==100,"nested and duplicate pauses");
    check(union(List.of(interval(0,10),interval(20,30)))==20,"disjoint pauses");
  }
  static void limits() throws Exception {
    rejects(()->new Limits(0,1,1,1,1),"zero file budget");
    rejects(()->new Limits(1,20_000_001,1,1,1),"event hard ceiling");
    rejects(()->new Limits(1,1,1_000_001,1,1),"group hard ceiling");
    rejects(()->new Limits(1,1,1,513,1),"depth hard ceiling");
    rejects(()->new Limits(1,1,1,1,0),"interval budget positive");
    var b=new Budget(2); String one=b.symbol(new String("same")); check(one==b.symbol(new String("same")),"symbols interned without extra group");
    b.symbol("second"); rejects(()->b.symbol("third"),"distinct symbol cap");
    rejects(()->new Budget(4).symbol("a".repeat(4097)),"symbol size cap");
    var a=new Analysis(new Limits(1000,1,100,10,1),"fixture"); a.observe("x",T,T);
    rejects(()->a.observe("x",T,T),"all events, including unknown kinds, consume budget");
    rejects(()->analysis().observe("x",T,T.minusNanos(1)),"negative event extent");
    rejects(()->analysis().observe("x",T,T.plus(Duration.ofDays(367))),"observed span cap");
    var intervals=new Analysis(new Limits(1000,100,100,10,1),"fixture");
    add(intervals,Kind.GC_PAUSE,0,1,"vm","gc",List.of(),0,null,false);
    rejects(()->add(intervals,Kind.GC_PAUSE,1,1,"vm","gc",List.of(),0,null,false),"GC interval retention cap");
    rejects(()->new Datum(Kind.CPU,T,-1,"t","r",List.of(),0,null,false),"negative measurement rejected");
  }
  static void aggregation() throws Exception {
    Analysis a=analysis();
    add(a,Kind.CPU,0,0,"t","cpu",List.of("Root","Leaf"),0,null,false);
    add(a,Kind.CPU,1,0,"t","cpu",List.of("Root","Leaf"),0,null,true);
    add(a,Kind.ALLOCATION,2,0,"t","byte[]",List.of("Root","Allocate"),4096,null,false);
    check(a.stacks.get(new StackKey(Kind.CPU,List.of("Root","Leaf")))==2,"CPU samples aggregate by root-first stack");
    check(a.stacks.get(new StackKey(Kind.ALLOCATION,List.of("Root","Allocate")))==4096,"allocation uses sampling weight");
    check(a.stacksTruncated==1 && a.stacksRecorded==3,"sampled stack coverage tracked");
    check(a.findings().stream().anyMatch(f->f.id().equals("FD.COVERAGE.TRUNCATED_STACKS")),"truncated-stack coverage warning");
    add(a,Kind.MONITOR,3,120_000_000,"waiter","Lock",List.of(),0,"owner",false);
    add(a,Kind.MONITOR,4,2_000_000,"waiter","Lock",List.of(),0,"owner",false);
    check(a.edges.size()==1 && a.edges.values().iterator().next().count==2,"owner edge aggregated");
    add(a,Kind.MONITOR,5,1,"waiter","Lock",List.of(),0,"unknown",false);
    add(a,Kind.MONITOR,6,1,"waiter","Lock",List.of(),0,"waiter",false);
    check(a.edges.size()==1,"unknown/self owners do not form edges");
    add(a,Kind.PARK,7,200_000_000,"parked","park",List.of(),0,null,false);
    check(a.edges.size()==1,"park is not monitor contention");
    check(a.findings().stream().noneMatch(f->f.id().contains("PARK")),"ordinary parks do not trigger stall warnings");
    add(a,Kind.GC_PAUSE,10,200_000_000,"vm","gc",List.of(),0,null,false);
    add(a,Kind.GC_PAUSE,20,200_000_000,"vm","gc",List.of(),0,null,false);
    check(a.pauseUnion()==210_000_000,"overlapping top-level pause union exact");
    check(a.metric(Kind.GC_PAUSE).nanos==400_000_000,"raw summed duration retained separately");
    check(a.threads.get("t").nanos==0,"CPU sample time is not counted as wait time");
    check(Main.exitStatus(a,null,true)==1,"warning gate");
    check(Main.exitStatus(a,a,true)==0,"comparison gates only newly introduced warning IDs");
    a.loss(10); check(a.incomplete() && Main.exitStatus(a,null,true)==2,"data loss takes precedence over warning gate");
    rejects(()->a.loss(-1),"negative data loss rejected");
  }
  @SuppressWarnings("unchecked")
  static void reports() throws Exception {
    Analysis a=analysis(); add(a,Kind.CPU,0,0,"t","cpu",List.of("Root","Leaf"),0,null,false);
    add(a,Kind.CPU,1000,0,"t","cpu",List.of("Root","Leaf"),0,null,false);
    var export=Reports.speedscope(a);
    var profiles=(List<Map<String,Object>>)export.get("profiles");
    check(profiles.size()==1,"one execution profile");
    check(profiles.getFirst().get("endValue").equals(2L),"sample profile bounds equal weights");
    check(profiles.getFirst().get("weights").equals(List.of(2L)),"collapsed identical samples keep weight");
    check(profiles.getFirst().get("samples").equals(List.of(List.of(0,1))),"Speedscope frames root-first");
    check(Json.write(export).contains("https://www.speedscope.app/file-format-schema.json"),"export declares canonical schema");
    Analysis b=analysis(); add(b,Kind.CPU,0,0,"t","cpu",List.of("Other"),0,null,false);add(b,Kind.CPU,2000,0,"t","cpu",List.of("Other"),0,null,false);
    var comparison=Reports.comparison(a,b,20); var metrics=(List<Map<String,Object>>)comparison.get("metrics");
    check(metrics.getFirst().get("baseline").equals(2.0),"baseline rate normalized to observed span");
    check(metrics.getFirst().get("candidate").equals(1.0),"candidate rate normalized to observed span");
    check(metrics.stream().filter(m->m.get("kind").equals("PARK")).allMatch(m->m.get("baseline")==null),"unobserved kinds are incomparable, not zero");
    var changes=(List<Map<String,Object>>)comparison.get("cpuStackChanges");
    check(changes.size()==2 && Math.abs((Double)changes.getFirst().get("deltaPercentagePoints"))==100,"CPU sample-mix changes in percentage points");
    check(!Reports.comparison(a,analysis(),20).get("cpuComparable").equals(true),"missing CPU samples are incomparable");
    String hostile="</script><script>alert('fixture')</script>\n\u001b[31m";
    String json=Json.write(map("key",hostile,"supplementary","\uD83D\uDE80"));
    check(!json.contains("</script>") && json.contains("\\u003c"),"JSON safe inside inert HTML script");
    check(json.contains("\\u001b") && json.contains("\\ud83d\\ude80"),"controls and supplementary Unicode escaped");
    rejects(()->Json.write(map("n",Double.NaN)),"non-finite JSON rejected");
    rejects(()->Json.write("x".repeat(Json.MAX_CHARS)),"output character cap");
    Analysis hostileAnalysis=new Analysis(Limits.defaults(),hostile);
    String html=Html.render(Reports.data(hostileAnalysis,20,false),false);
    check(!html.contains(hostile) && html.contains("Content-Security-Policy"),"HTML output prevents direct field markup injection");
    check(!Reports.visible(hostile).contains("\u001b"),"terminal control escape");
    check(JfrInput.token("/private/example",false).matches("sha256:[0-9a-f]{64}"),"default resource hashing");
    check(JfrInput.token("/private/example",false).equals(JfrInput.token("/private/example",false)),"redaction stable across recordings");
    check(JfrInput.token("/private/example",true).equals("/private/example"),"sensitive mode explicit");
  }
  static int command(String... args) { return Main.run(args,new PrintStream(new ByteArrayOutputStream(),true,StandardCharsets.UTF_8),new PrintStream(new ByteArrayOutputStream(),true,StandardCharsets.UTF_8)); }
  static void standaloneExample(Path directory) throws Exception {
    Path fixture=directory.resolve("standalone.jfr"), log=directory.resolve("source-launch.log");
    String javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
    var process=new ProcessBuilder(javaExecutable,"-Xmx256m","examples/dev/rudy/flightdeck/CaptureDemo.java",fixture.toString())
        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    if(!process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Standalone example timed out");}
    check(process.exitValue()==0,"packaged-layout source launcher: "+Files.readString(log));
    check(Files.isRegularFile(fixture)&&Files.size(fixture)>0,"standalone example creates a recording");
    check(JfrInput.read(fixture,Limits.defaults(),false).recognized>0,"standalone example recording analyzes");
  }
  static void integration(Path dir) throws Exception {
    Path fixture=dir.resolve("demo.jfr"); CaptureDemo.capture(fixture,130);
    var a=JfrInput.read(fixture,Limits.defaults(),false);
    check(a.read>0 && a.recognized>0 && a.seconds()>0,"real JDK recording streamed");
    check(a.metric(Kind.FILE_WRITE).bytes>=32768,"real file write bytes decoded");
    check(a.metric(Kind.FILE_READ).bytes>=32768,"real file read bytes decoded");
    check(a.metric(Kind.MONITOR).count>0 && a.edges.size()>0,"real monitor and previous owner decoded");
    check(a.metric(Kind.PARK).count>0,"real park decoded");
    check(a.metric(Kind.SOCKET_READ).count>0 && a.metric(Kind.SOCKET_WRITE).count>0,"loopback socket events decoded");
    check(a.sha256.matches("[a-f0-9]{64}"),"input digest present");
    check(a.resources.keySet().stream().filter(k->k.startsWith("FILE_")).noneMatch(k->k.contains("flightdeck-synthetic-")),"resource paths redacted in recording reader");
    var raw=JfrInput.read(fixture,Limits.defaults(),true);
    check(raw.resources.keySet().stream().anyMatch(k->k.contains("flightdeck-synthetic-")),"explicit sensitive input reader mode");
    check(a.metric(Kind.CPU).count==raw.metric(Kind.CPU).count,"redaction does not change sample count");
    rejects(()->JfrInput.read(fixture,new Limits(1,100,100,10,10),false),"oversized JFR refused");
    rejects(()->JfrInput.read(fixture,new Limits(10_000_000,1,10000,10,10),false),"real stream event budget enforced");
    rejects(()->JfrInput.read(fixture,new Limits(10_000_000,100000,1,10,10),false),"real stream group budget enforced");
    Path invalid=dir.resolve("invalid.jfr");Files.write(invalid,new byte[32]); rejects(()->JfrInput.read(invalid,Limits.defaults(),false),"invalid magic rejected by JDK");
    Path truncated=dir.resolve("truncated.jfr"); Files.write(truncated,Arrays.copyOf(Files.readAllBytes(fixture),40)); rejects(()->JfrInput.read(truncated,Limits.defaults(),false),"truncated JFR rejected");
    check(command("--help")==0 && command("--version")==0,"CLI metadata");
    check(command("analyze",fixture.toString(),"--format","json")==0,"CLI analysis complete");
    check(command("analyze",fixture.toString(),"--fail-on","warning")==1,"actual long monitor triggers requested gate");
    check(command("compare",fixture.toString(),fixture.toString(),"--fail-on","warning")==0,"identical recordings do not introduce warnings");
    Path output=dir.resolve("report.json");
    check(command("analyze",fixture.toString(),"--format","json","--output",output.toString())==0,"new output written");
    check(Files.readString(output).startsWith("{\"schema\":\"rudy-flightdeck/v1\""),"versioned JSON report");
    byte[] original=Files.readAllBytes(output);
    check(command("analyze",fixture.toString(),"--output",output.toString())==2,"existing report refused");
    check(Arrays.equals(original,Files.readAllBytes(output)),"existing output preserved byte for byte");
    String hash=a.sha256;
    check(command("analyze",fixture.toString(),"--output",fixture.toString())==2,"input overwrite refused");
    check(JfrInput.digest(fixture,Limits.defaults().fileBytes()).equals(hash),"input recording unchanged");
    for(String[] args : List.of(new String[]{"analyze",fixture.toString(),"--top","0"},new String[]{"analyze",fixture.toString(),"--max-events","-1"},new String[]{"analyze",fixture.toString(),"--format","unknown"},new String[]{"compare",fixture.toString(),fixture.toString(),"--format","speedscope"},new String[]{"analyze",fixture.toString(),"--unknown","x"}))
      check(command(args)==2,"invalid CLI option rejected: "+Arrays.toString(args));
    Path exampleRoot=Path.of("build/examples");Files.createDirectories(exampleRoot);
    Files.writeString(exampleRoot.resolve("report.json"),Json.write(Reports.data(a,20,false)),StandardCharsets.UTF_8);
    Files.writeString(exampleRoot.resolve("report.html"),Html.render(Reports.data(a,20,false),false),StandardCharsets.UTF_8);
    Files.writeString(exampleRoot.resolve("speedscope.json"),Json.write(Reports.speedscope(a)),StandardCharsets.UTF_8);
    Files.writeString(exampleRoot.resolve("comparison.html"),Html.render(Reports.comparison(a,a,20),true),StandardCharsets.UTF_8);
  }
  public static void main(String[] args) throws Exception {
    Path directory=Files.createTempDirectory("flightdeck-tests-");
    try { statistics(); intervals(); limits(); aggregation(); reports(); integration(directory); standaloneExample(directory); }
    finally { try(var files=Files.walk(directory)) { for(Path path:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    System.out.println("Rudy Flightdeck: "+checks+" checks passed (including real JFR and loopback fixtures)");
  }
  private Tests() {}
}
