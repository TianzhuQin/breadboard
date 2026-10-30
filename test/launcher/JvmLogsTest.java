package launcher;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.TreeSet;

import static org.junit.Assert.*;

/**
 * logs/jvm-pid&lt;N&gt;.log, what Java itself says (launcher.JvmLogs): each start of Java makes one;
 * at the next start, those in which Java said nothing go, the newest of the others stay, and this
 * start's always does.
 */
public class JvmLogsTest {

  /** What Java writes into such a file before what it says, with text of its own (its version, its options). */
  private static final String HEADER = "<?xml version='1.0' encoding='UTF-8'?>\n"
      + "<hotspot_log version='160 1' process='101' time_ms='1793384455103'>\n"
      + "<vm_version>\n<name>\nOpenJDK 64-Bit Server VM\n</name>\n</vm_version>\n"
      + "<vm_arguments>\n<args>\n-Xmx1g -XX:+ExitOnOutOfMemoryError -XX:-DisplayVMOutput\n</args>\n"
      + "<properties>\njava.vm.name=OpenJDK 64-Bit Server VM\n</properties>\n</vm_arguments>\n"
      + "<tty>\n<blob name='MethodHandlesAdapterBlob' size='32000'>\n<sect index='1' size='32000' free='31560'/>\n</blob>\n"
      + "<writer thread='7171'/>\n"
      + "<dependency_failed type='leaf_type' ctxk='java/io/File' witness='org/codehaus/groovy/runtime/WritableFile' stamp='0.798'/>\n";

  /** A start in which Java said nothing (it stopped normally) */
  private static final String NOTHING = HEADER + "</tty>\n</hotspot_log>\n";

  /** A start in which Java ran out of memory (it ends there) */
  private static final String OUT_OF_MEMORY = HEADER + "<writer thread='22275'/>\n"
      + "java.lang.OutOfMemoryError: Java heap space\nDumping heap to logs/out-of-memory.hprof ...\n"
      + "Heap dump file created [126431037 bytes in 0.077 secs]\n"
      + "Terminating due to java.lang.OutOfMemoryError: Java heap space\n";

  private static void file(File logs, String name, String text, long modified) throws Exception {
    File file = new File(logs, name);
    Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    assertTrue(file.setLastModified(modified));
  }

  private static TreeSet<String> names(File logs) {
    return new TreeSet<>(Arrays.asList(logs.list()));
  }

  @Test
  public void thoseThatSayNothingGoTheNewestOthersStayAndThisStartsAlways() throws Exception {
    File logs = Files.createTempDirectory("bb-logs").toFile();
    long now = System.currentTimeMillis();
    // 14 earlier starts that ran out of memory, a minute apart, the newest last, and between them
    // 14 that said nothing
    for (int i = 1; i <= 14; i++) {
      file(logs, "jvm-pid" + (100 + i) + ".log", OUT_OF_MEMORY, now - (15 - i) * 60000L);
      file(logs, "jvm-pid" + (200 + i) + ".log", NOTHING, now - (15 - i) * 60000L + 1000);
    }
    // this start's, which says nothing yet, with a time older than all of them (a clock set back)
    file(logs, "jvm-pid999.log", HEADER, now - 3600000L);
    // other files, older still
    for (String other : new String[] {"application.log", "jvm-pid12.log.bak", "jvm.log", "start-errors.log", "out-of-memory.hprof"}) {
      file(logs, other, NOTHING, now - 7200000L);
    }

    JvmLogs.prune(logs, 10, "jvm-pid999.log");

    TreeSet<String> expected = new TreeSet<>(Arrays.asList("jvm-pid999.log",
        "application.log", "jvm-pid12.log.bak", "jvm.log", "start-errors.log", "out-of-memory.hprof"));
    // the 9 newest of those that say something
    for (int i = 6; i <= 14; i++) {
      expected.add("jvm-pid" + (100 + i) + ".log");
    }
    assertEquals(expected, names(logs));
  }

  @Test
  public void withoutThisStartsTheNewestTenThatSaySomethingStay() throws Exception {
    File logs = Files.createTempDirectory("bb-logs").toFile();
    long now = System.currentTimeMillis();
    for (int i = 1; i <= 12; i++) {
      file(logs, "jvm-pid" + (100 + i) + ".log", OUT_OF_MEMORY, now - (13 - i) * 60000L);
    }

    JvmLogs.prune(logs, 10, "jvm-pid999.log");

    TreeSet<String> expected = new TreeSet<>();
    for (int i = 3; i <= 12; i++) {
      expected.add("jvm-pid" + (100 + i) + ".log");
    }
    assertEquals(expected, names(logs));
  }

  @Test
  public void onlyWhatFollowsTheTtyLineAndIsntMarkupCounts() throws Exception {
    File logs = Files.createTempDirectory("bb-logs").toFile();
    long now = System.currentTimeMillis();
    // cut short (a crash, kill -9) before or after <tty>: nothing said
    file(logs, "jvm-pid301.log", "", now);
    file(logs, "jvm-pid302.log", HEADER.substring(0, HEADER.indexOf("<tty>")), now);
    file(logs, "jvm-pid303.log", HEADER, now);
    // a thread dump (kill -3), whose quotes Java writes as &quot;
    file(logs, "jvm-pid304.log", HEADER + "Full thread dump OpenJDK 64-Bit Server VM (25.504-b01 mixed mode):\n\n"
        + "&quot;main&quot; #1 prio=5 os_prio=31 tid=0x0 nid=0x1 waiting on condition\n", now);
    assertFalse(JvmLogs.saysSomething(new File(logs, "jvm-pid301.log")));
    assertFalse(JvmLogs.saysSomething(new File(logs, "jvm-pid302.log")));
    assertFalse(JvmLogs.saysSomething(new File(logs, "jvm-pid303.log")));
    assertTrue(JvmLogs.saysSomething(new File(logs, "jvm-pid304.log")));

    JvmLogs.prune(logs, 10, "jvm-pid999.log");

    assertEquals(new TreeSet<>(Arrays.asList("jvm-pid304.log")), names(logs));
  }

  @Test
  public void aMissingFolderIsNoProblem() {
    JvmLogs.prune(new File("no-such-folder-" + System.nanoTime()), 10, "jvm-pid1.log");
  }

  @Test
  public void thisProcesssFileIsNamedAfterItsProcessId() {
    String name = JvmLogs.ownName();
    assertTrue(name, name.matches("jvm-pid[0-9]+\\.log"));
    String pid = java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
    assertEquals("jvm-pid" + pid + ".log", name);
  }
}
