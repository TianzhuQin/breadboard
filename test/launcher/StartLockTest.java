package launcher;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class StartLockTest {
  @Test
  public void onlyTheLauncherThatOwnsTheLockCanReleaseIt() throws Exception {
    File home = Files.createTempDirectory("bb-start-lock").toFile();
    File lock = new File(home, "logs/start.lock");
    assertTrue(lock.mkdirs());
    File pid = new File(lock, "pid");
    Files.write(pid.toPath(), "12345\n".getBytes(StandardCharsets.US_ASCII));
    StartLock.release(home, null);
    StartLock.release(home, "99999");
    assertTrue(pid.exists());
    StartLock.release(home, "12345");
    assertFalse(lock.exists());
    StartLock.release(home, "12345");
    new File(home, "logs").delete();
    home.delete();
  }
}
