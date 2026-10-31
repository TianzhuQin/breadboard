package launcher;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * The options for out of memory that the launchers give Java (MEMORY_OPTS in release/breadboard.sh
 * and release/breadboard.bat): a server that runs out of memory ends, with exit code 3, so that
 * keep-running starts it again, also when nobody reads its window. Java says that it ran out of
 * memory straight to the window, where one that takes no output (paused, a pipe nobody reads) held
 * it up for good; the launchers send that into logs/jvm-pid&lt;N&gt;.log instead.
 */
public class LauncherJavaOptionsTest {

  private static final Pattern OPTION = Pattern.compile("-XX:[^\\s\"]+");

  /**
   * The -XX options on the lines of {@code launcher} that set MEMORY_OPTS ({@code assignment}). In
   * the batch file, %% is a %, and \ separates folders.
   */
  static List<String> memoryOptions(String launcher, String assignment, boolean batch) throws IOException {
    List<String> options = new ArrayList<>();
    for (String line : Files.readAllLines(new File(launcher).toPath(), StandardCharsets.UTF_8)) {
      if (!line.contains(assignment)) {
        continue;
      }
      Matcher option = OPTION.matcher(line);
      while (option.find()) {
        String found = option.group();
        options.add(batch ? found.replace("%%", "%").replace('\\', File.separatorChar) : found);
      }
    }
    return options;
  }

  static List<String> shOptions() throws IOException {
    return memoryOptions("release/breadboard.sh", "MEMORY_OPTS=\"", false);
  }

  static List<String> batOptions() throws IOException {
    return memoryOptions("release/breadboard.bat", "SET \"MEMORY_OPTS=", true);
  }

  private static String java() {
    return new File(new File(System.getProperty("java.home"), "bin"), "java").getPath();
  }

  private static void delete(File file) {
    File[] inside = file.listFiles();
    if (inside != null) {
      for (File f : inside) {
        delete(f);
      }
    }
    file.delete();
  }

  /**
   * Java, with {@code options}, runs out of memory once its window (standard output, a pipe this
   * never reads) takes no more output: it must end, with exit code 3, and say why in
   * logs/jvm-pid&lt;N&gt;.log.
   */
  private static void endsThoughNobodyReadsTheWindow(List<String> options) throws Exception {
    File home = Files.createTempDirectory("bb-out-of-memory").toFile();
    File logs = new File(home, "logs");
    assertTrue(logs.mkdir());
    try {
      // (as the launchers check: an older Java 8 runs without them; and, as there, without the
      // file, which Java would make)
      List<String> check = new ArrayList<>(Arrays.asList(java()));
      for (String option : options) {
        if (!option.startsWith("-XX:+LogVMOutput") && !option.startsWith("-XX:LogFile=")) {
          check.add(option);
        }
      }
      check.add("-version");
      Process version = new ProcessBuilder(check).directory(home).redirectErrorStream(true)
          .redirectOutput(new File(home, "version.txt")).start();
      Assume.assumeTrue("this Java doesn't know the options " + options, version.waitFor() == 0);

      List<String> command = new ArrayList<>(Arrays.asList(java(), "-Xmx32m"));
      command.addAll(options);
      command.add("-cp");
      command.add(new File(OutOfMemoryChild.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath());
      command.add(OutOfMemoryChild.class.getName());
      Process child = new ProcessBuilder(command).directory(home)
          .redirectError(new File(home, "errors.txt")).start();
      try {
        assertTrue("Java ran out of memory while nobody read its window, and hadn't ended after 60 s: "
            + "it waits for the window to take what it says (options " + options + ")",
            child.waitFor(60, TimeUnit.SECONDS));
        assertEquals("exit code (3: out of memory)", 3, child.exitValue());
      } finally {
        child.destroyForcibly();
        child.waitFor();
      }
      File[] said = logs.listFiles((folder, name) -> name.matches("jvm-pid[0-9]+\\.log"));
      assertNotNull(said);
      assertEquals("logs/jvm-pid<N>.log: " + Arrays.toString(logs.list()), 1, said.length);
      String text = new String(Files.readAllBytes(said[0].toPath()), StandardCharsets.UTF_8);
      assertTrue(text, text.contains("Terminating due to java.lang.OutOfMemoryError: Java heap space"));
      assertTrue("the heap dump", new File(logs, "out-of-memory.hprof").isFile());
    } finally {
      delete(home);
    }
  }

  @Test(timeout = 300000)
  public void withBreadboardShsOptionsJavaThatRunsOutOfMemoryEndsThoughNobodyReadsItsWindow() throws Exception {
    endsThoughNobodyReadsTheWindow(shOptions());
  }

  @Test(timeout = 300000)
  public void withBreadboardBatsOptionsJavaThatRunsOutOfMemoryEndsThoughNobodyReadsItsWindow() throws Exception {
    endsThoughNobodyReadsTheWindow(batOptions());
  }

  @Test
  public void bothLaunchersGiveTheSameOptions() throws Exception {
    List<String> sh = shOptions();
    assertFalse(sh.isEmpty());
    assertTrue(sh.toString(), sh.contains("-XX:-DisplayVMOutput"));
    assertEquals(sh, batOptions());
  }
}
