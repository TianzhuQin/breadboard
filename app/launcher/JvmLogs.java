package launcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What Java itself says, in logs/jvm-pid&lt;its process id&gt;.log: that the server ran out of memory,
 * and of what; the heap dump; a thread dump (kill -3). Java writes that straight to the window, past
 * System.out, so the launchers send it into this file instead (-XX:-DisplayVMOutput -XX:+LogVMOutput
 * -XX:LogFile=logs/jvm-%p.log): a window that took no output held Java up for good as it ran out of
 * memory, before it ended, so that keep-running never started the server again.
 *
 * Each start of Java makes a file of its own (one name for all would be emptied by the next start,
 * the one after the stop it tells of). Java also writes some markup of its own into each, so most
 * say nothing: those of earlier starts go, and of the others, the newest are kept.
 */
final class JvmLogs {

  /** How many of these files are kept at most, this start's among them. */
  static final int KEEP = 10;

  private static final Pattern NAME = Pattern.compile("jvm-pid[0-9]+\\.log");

  private JvmLogs() {}

  /** This process's file's name. */
  static String ownName() {
    String name = ManagementFactory.getRuntimeMXBean().getName();
    int at = name.indexOf('@');
    return "jvm-pid" + (at < 0 ? name : name.substring(0, at)) + ".log";
  }

  /**
   * Deletes the files in {@code logs} of earlier starts in which Java said nothing, and of the
   * others all but the newest, so that at most {@code keep} stay; {@code own}, this process's,
   * always stays. Nothing is said of a file that can't be deleted (still open, on Windows): the next
   * start tries again.
   */
  static void prune(File logs, int keep, String own) {
    File[] files = logs.listFiles();
    if (files == null) {
      return;
    }
    List<File> said = new ArrayList<>();
    boolean ownThere = false;
    for (File file : files) {
      if (!file.isFile() || !NAME.matcher(file.getName()).matches()) {
        continue;
      }
      if (file.getName().equals(own)) {
        ownThere = true;
      } else if (saysSomething(file)) {
        said.add(file);
      } else {
        file.delete();
      }
    }
    // newest first (by time, then by name for the same time)
    said.sort((a, b) -> {
      int byTime = Long.compare(b.lastModified(), a.lastModified());
      return byTime != 0 ? byTime : b.getName().compareTo(a.getName());
    });
    for (int i = Math.max(0, keep - (ownThere ? 1 : 0)); i < said.size(); i++) {
      said.get(i).delete();
    }
  }

  /**
   * Whether Java said something in {@code file}: a line of text after its "&lt;tty&gt;" line, where
   * what it says goes (with &lt; written as &amp;lt;), not only its markup. One that can't be read
   * is taken to say something, and stays.
   */
  static boolean saysSomething(File file) {
    try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
      boolean tty = false;
      String line;
      while ((line = in.readLine()) != null) {
        if (!tty) {
          tty = line.equals("<tty>");
        } else if (!line.trim().isEmpty() && !line.startsWith("<")) {
          return true;
        }
      }
      return false;
    } catch (IOException | RuntimeException e) {
      return true;
    }
  }
}
