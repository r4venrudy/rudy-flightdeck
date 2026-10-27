import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.*;

/** Build with the standard JDK, including strict compiler diagnostics. */
class Build {
  static void resetBuildDirectory(Path directory) throws IOException {
    Path root = Path.of("").toAbsolutePath().normalize();
    Path target = directory.toAbsolutePath().normalize();
    if (!target.startsWith(root.resolve("build")) || target.equals(root.resolve("build"))) throw new IOException("Refusing to clear path outside a build subdirectory");
    if (Files.exists(target)) try (var files = Files.walk(target)) {
      for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }
  static void compile(Path source, Path output, String classpath) throws IOException {
    Files.createDirectories(output);
    var args = new ArrayList<>(List.of("--release", "21", "-encoding", "UTF-8", "-Xlint:all", "-Werror", "-d", output.toString()));
    if (classpath != null) args.addAll(List.of("-classpath", classpath));
    try (var files = Files.walk(source)) { files.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> args.add(p.toString())); }
    var compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null || compiler.run(null, System.out, System.err, args.toArray(String[]::new)) != 0) throw new IOException("Compilation failed; use a Java 21+ JDK.");
  }
  public static void main(String[] args) throws Exception {
    if (!Files.isRegularFile(Path.of("src/dev/rudy/flightdeck/Main.java"))) throw new IOException("Run from project root");
    if (args.length != 1 || !Set.of("test", "package").contains(args[0])) throw new IOException("usage: java tools/Build.java test|package");
    resetBuildDirectory(Path.of("build/classes"));
    compile(Path.of("src"), Path.of("build/classes"), null);
    if (args[0].equals("test")) {
      resetBuildDirectory(Path.of("build/test-classes"));
      compile(Path.of("examples"), Path.of("build/test-classes"), "build/classes");
      compile(Path.of("tests"), Path.of("build/test-classes"), "build/classes" + File.pathSeparator + "build/test-classes");
      String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
      var process = new ProcessBuilder(java, "-ea", "-Xmx512m", "-cp", "build/classes" + File.pathSeparator + "build/test-classes", "dev.rudy.flightdeck.Tests").inheritIO().start();
      if (process.waitFor() != 0) throw new IOException("Test suite failed");
    } else {
      Files.createDirectories(Path.of("dist"));
      Path target = Path.of("dist/rudy-flightdeck-0.1.0-preview.jar");
      Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0"); manifest.getMainAttributes().putValue("Main-Class", "dev.rudy.flightdeck.Main");
      try (var jar = new JarOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)); var files = Files.walk(Path.of("build/classes"))) {
        var manifestEntry = new JarEntry("META-INF/MANIFEST.MF"); manifestEntry.setTime(0);
        jar.putNextEntry(manifestEntry); manifest.write(jar); jar.closeEntry();
        for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
          var entry = new JarEntry(Path.of("build/classes").relativize(file).toString().replace(File.separatorChar, '/')); entry.setTime(0);
          jar.putNextEntry(entry); Files.copy(file, jar); jar.closeEntry();
        }
      }
      System.out.println("Built " + target);
    }
  }
}
