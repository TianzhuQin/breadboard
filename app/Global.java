import logging.LogCapture;
import logging.LogStore;
import play.Application;
import play.Configuration;
import play.GlobalSettings;
import play.mvc.Action;
import play.mvc.Http;

import java.io.File;
import java.lang.reflect.Method;

public class Global extends GlobalSettings {

  static Process process = null;

  @Override
  public void onStart(Application app) {
    // Capture server logs so admins can read them in the Logs dialog (see LogsController).
    Configuration conf = app.configuration();
    Boolean enabled = conf.getBoolean("breadboard.logs.enabled");
    if (enabled == null || enabled) {
      String dir = conf.getString("breadboard.logs.dir");
      Integer maxFileMB = conf.getInt("breadboard.logs.maxFileMB");
      Integer maxFiles = conf.getInt("breadboard.logs.maxFiles");
      File logDir = new File(dir == null ? "logs/admin" : dir);
      if (!logDir.isAbsolute()) {
        logDir = new File(app.path(), logDir.getPath());
      }
      LogStore store = new LogStore(
          logDir,
          (maxFileMB == null ? 5 : maxFileMB) * 1024L * 1024L,
          maxFiles == null ? 20 : maxFiles,
          5000,
          16L * 1024L * 1024L);
      LogCapture.install(store);
      store.append(System.currentTimeMillis(), "INFO", "breadboard",
          "Breadboard " + conf.getString("application.version", "") + " started");
    }
  }

  @Override
  public void onStop(Application app) {
    if (process != null) {
      process.destroy();
    }
    LogCapture.uninstall();
  }

  @Override
  public Action onRequest(Http.Request request, Method actionMethod) {
    return super.onRequest(request, actionMethod);
  }
}
