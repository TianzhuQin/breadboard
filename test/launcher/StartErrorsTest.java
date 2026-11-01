package launcher;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * logs/start-errors.log (launcher.StartErrors): what Java prints as errors until the server listens
 * is written into it when the process ends before that; once the server listens, the file goes, so
 * that a launcher that finds it knows the server never started.
 */
public class StartErrorsTest {

  private static File launcherMadeFile() throws Exception {
    File logs = Files.createTempDirectory("bb-logs").toFile();
    File file = new File(logs, "start-errors.log");
    Files.write(file.toPath(), "Breadboard's Java was started at ..., and the server hasn't listened yet.\n".getBytes(StandardCharsets.UTF_8));
    return file;
  }

  private static String read(File file) throws Exception {
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }

  @Test(timeout = 60000)
  public void whatJavaPrintedAsErrorsGoesIntoTheFileWhenTheServerNeverListened() throws Exception {
    File file = launcherMadeFile();
    StartErrors startErrors = new StartErrors(file);
    ByteArrayOutputStream window = new ByteArrayOutputStream();
    QueuedOutput queued = new QueuedOutput(window, "test-stderr", startErrors);
    PrintStream err = Main.printStream(queued, "UTF-8");
    err.println("Configuration error: Configuration error[conf/local.conf: 9: Expecting end of input or a comma, got ':']");
    new IllegalStateException("could not start").printStackTrace(err);
    err.flush();

    startErrors.writeIfTheServerNeverListened();

    String said = read(file);
    assertTrue(said, said.startsWith("Breadboard stopped before the server started ("));
    assertTrue(said, said.contains("What Java printed as errors:\n\nConfiguration error: Configuration error[conf/local.conf: 9:"));
    assertTrue(said, said.contains("java.lang.IllegalStateException: could not start\n\tat launcher.StartErrorsTest."));
    assertFalse("in place of what the launcher wrote", said.contains("hasn't listened yet"));
    assertTrue("the window has them too", queued.drainWithin(20000));
    assertTrue(new String(window.toByteArray(), StandardCharsets.UTF_8).startsWith("Configuration error"));
  }

  @Test
  public void withNothingPrintedTheFileSaysSo() throws Exception {
    File file = launcherMadeFile();
    new StartErrors(file).writeIfTheServerNeverListened();
    String said = read(file);
    assertTrue(said, said.startsWith("Breadboard stopped before the server started ("));
    assertTrue(said, said.contains("without printing an error"));
  }

  @Test(timeout = 60000)
  public void onceTheServerListensTheFileGoesAndNothingMoreIsWritten() throws Exception {
    File file = launcherMadeFile();
    StartErrors startErrors = new StartErrors(file);
    PrintStream err = Main.printStream(new QueuedOutput(new ByteArrayOutputStream(), "test-stderr", startErrors), "UTF-8");
    err.println("a warning while it started");
    err.flush();

    startErrors.serverListens();
    assertFalse("the launcher would take the server for one that never started", file.exists());
    err.println("an error long after the start");
    err.flush();
    startErrors.writeIfTheServerNeverListened();
    assertFalse("a server that listened, then stopped, writes nothing", file.exists());
  }

  @Test(timeout = 60000)
  public void onlyTheLastLinesAreKept() throws Exception {
    File file = launcherMadeFile();
    StartErrors startErrors = new StartErrors(file);
    PrintStream err = Main.printStream(new QueuedOutput(new ByteArrayOutputStream(), "test-stderr", startErrors), "UTF-8");
    int lines = StartErrors.MAX_LINES + 50;
    for (int i = 0; i < lines; i++) {
      err.println("error line " + i);
    }
    err.flush();
    startErrors.writeIfTheServerNeverListened();
    String said = read(file);
    assertFalse(said, said.contains("error line 49\n"));
    assertTrue(said, said.contains("\nerror line 50\n"));
    assertTrue(said, said.endsWith("error line " + (lines - 1) + "\n"));
  }
}
