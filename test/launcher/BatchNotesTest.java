package launcher;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * What release/breadboard.bat says when the server stops by itself, read from the file (no Windows
 * here to run it on; CI's Windows job runs it, through bin/smoke-dist.ps1).
 */
public class BatchNotesTest {

  private static List<String> lines() throws Exception {
    return Files.readAllLines(new File("release/breadboard.bat").toPath(), StandardCharsets.ISO_8859_1);
  }

  /** The lines of the routine at {@code label} (":note"), up to its GOTO :EOF. */
  private static List<String> routine(String label) throws Exception {
    List<String> all = lines();
    int start = all.indexOf(label);
    assertTrue("breadboard.bat has " + label, start >= 0);
    List<String> body = new ArrayList<String>();
    for (int i = start + 1; i < all.size() && !all.get(i).trim().equalsIgnoreCase("GOTO :EOF"); i++) {
      if (!all.get(i).trim().toUpperCase().startsWith("REM")) {
        body.add(all.get(i).trim());
      }
    }
    return body;
  }

  private static int firstLog(List<String> body) {
    for (int i = 0; i < body.size(); i++) {
      if (body.get(i).startsWith(">>\"logs\\application.log\"")) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Why the launcher leaves the server stopped (it couldn't start, it gave up after 5 restarts,
   * Breadboard was started from the same folder meanwhile) reaches logs\application.log before the
   * window: a window that doesn't take output (a click into it starts selecting text, which pauses it
   * until Esc) holds ECHO up, and the log's line with it, as it did for :note.
   */
  @Test
  public void theReasonGoesIntoTheLogBeforeTheWindow() throws Exception {
    List<String> note = routine(":note");
    int log = firstLog(note);
    int window = note.indexOf("ECHO %~1");
    assertTrue(":note writes logs\\application.log: " + note, log >= 0);
    assertTrue(":note shows the message in the window: " + note, window >= 0);
    assertTrue(":note shows the message before it writes the log: " + note, log < window);

    List<String> goingOn = routine(":note_going_on");
    int shown = goingOn.indexOf("CALL :show %1");
    assertTrue(goingOn.toString(), firstLog(goingOn) >= 0 && shown > firstLog(goingOn));
  }

  /**
   * Exit code 1 names every cause it can have on Windows: the server's own stop after an error (which
   * logs\application.log shows), a crash of Java (HotSpot ends with 1 there, and leaves
   * hs_err_pid*.log), and an end from outside (Task Manager, taskkill /F), of which the log says
   * nothing. It used to name the first alone, as if the log always said why.
   */
  @Test
  public void exitCodeOneNamesACrashAndAnEndFromOutsideToo() throws Exception {
    String why = null;
    for (String line : lines()) {
      if (line.startsWith("IF \"%EXIT_CODE%\"==\"1\" SET \"WHY=")) {
        why = line;
      }
    }
    assertNotNull("breadboard.bat sets WHY for exit code 1", why);
    assertTrue(why, why.contains("logs\\application.log"));
    assertTrue(why, why.contains("Java crashed") && why.contains("hs_err_pid*.log in this folder"));
    assertTrue(why, why.contains("ended from outside"));
    // (it goes into a message in brackets, and into a CALL: no brackets of its own)
    assertFalse(why, why.substring(why.indexOf("WHY=")).contains("(") || why.substring(why.indexOf("WHY=")).contains(")"));
  }
}
