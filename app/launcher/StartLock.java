package launcher;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Releases only this launcher's atomic start lock, after Play listens or Java exits. */
final class StartLock {
  private StartLock() {}

  static void release(File home, String owner) {
    if (owner == null || !owner.matches("[0-9]+")) {
      return;
    }
    File lock = new File(new File(home, "logs"), "start.lock");
    File pid = new File(lock, "pid");
    try {
      String heldBy = new String(Files.readAllBytes(pid.toPath()), StandardCharsets.US_ASCII).trim();
      if (owner.equals(heldBy) && pid.delete()) {
        lock.delete();
      }
    } catch (Exception e) {
      // It was released already, or stays for stale-lock recovery on the next start.
    }
  }
}
