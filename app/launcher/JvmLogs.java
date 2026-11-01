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
 * Short out-of-memory diagnostics from the native JVM hook, logs/jvm-pid&lt;N&gt;.log. HotSpot's
 * LogVMOutput also grows internal dependency XML at every engine replacement, so the launchers
 * disable it. Old XML logs remain readable here while earlier releases' empty ones are removed.
 * Main keeps two earlier diagnostics, leaving room for this process's (at most three altogether).
 */
final class JvmLogs {

  /** How many of these files are kept at most, this start's among them. */
  static final int KEEP = 3;

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
      boolean xml = false;
      String line;
      while ((line = in.readLine()) != null) {
        if (line.startsWith("<?xml") || line.startsWith("<hotspot_log")) {
          xml = true;
        }
        if (!xml && !line.trim().isEmpty()) {
          return true; // the short native OOM hook's plain text (older releases used XML)
        }
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
