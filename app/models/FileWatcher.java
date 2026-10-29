package models;

import actors.FileWatcherActor;
import actors.FileWatcherActorProtocol;
import akka.actor.ActorRef;
import akka.actor.Props;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.nio.file.SensitivityWatchEventModifier;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.FalseFileFilter;
import play.Logger;
import play.libs.Akka;
import scala.concurrent.duration.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

public class FileWatcher {
  private Long updateIteration = 0L;
  // ScriptBoard's live list of open admin sockets (thread-safe; it shrinks when sockets close).
  private final List<Admin> adminListeners;
  static ActorRef fileWatcherActor;
  private WatchService watcher;
  private Map<WatchKey, Path> watchKeys;
  private final Path devPath;

  public FileWatcher(List<Admin> adminListeners) {
    this.adminListeners = adminListeners;

    // Resolve 'dev' against the Play application root (matching Experiment/ImagesController),
    // not the process working directory, so the watcher always targets the same directory the
    // rest of the app reads from. Create it if missing — 'dev' is gitignored and won't exist on
    // a fresh checkout, and listing a non-existent directory would crash this actor on startup.
    File devDir = new File(play.Play.application().path(), "dev");
    if (!devDir.isDirectory()) {
      devDir.mkdirs();
    }
    devPath = devDir.toPath();
    this.watchKeys = new HashMap<>();
    try {
      this.watcher = FileSystems.getDefault().newWatchService();
      // Recursively watch all directories inside of the 'dev' directory
      registerRecursive(devPath);
    } catch (IOException ioe) {
      Logger.error("Can no longer watch the dev directory, was it deleted?");
    }

    fileWatcherActor = Akka.system().actorOf(new Props(FileWatcherActor.class));
    Long fileWatchRate = play.Play.application().configuration().getMilliseconds("breadboard.fileWatchRate", 100L);
    Akka.system().scheduler().schedule(
        Duration.create(0, TimeUnit.MILLISECONDS),
        Duration.create(fileWatchRate, TimeUnit.MILLISECONDS),
        fileWatcherActor,
        new FileWatcherActorProtocol.FileWatch(this),
        Akka.system().dispatcher(),
        null
    );
  }

  public void registerRecursive(Path parent) throws IOException {
    if (!parent.toFile().isDirectory()) {
      Logger.warn("Not watching '" + parent + "' because it is not a directory.");
      return;
    }
    for (File f : FileUtils.listFilesAndDirs(parent.toFile(), FalseFileFilter.INSTANCE, DirectoryFileFilter.DIRECTORY)) {
      WatchKey watchKey = f.toPath().register(watcher, new WatchEvent.Kind[]{ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY}, SensitivityWatchEventModifier.HIGH);
      watchKeys.put(watchKey, f.toPath());
    }
  }

  public List<Admin> getAdminListeners() {
    return this.adminListeners;
  }

  /** The dev/ folder the experiments in file mode are read from. */
  public Path getDevPath() {
    return devPath;
  }

  public void incrementUpdateIteration() {
    this.updateIteration++;
  }

  public Long getUpdateIteration() {
    return this.updateIteration;
  }

  public WatchService getWatcher() {
    return this.watcher;
  }

  public Map<WatchKey, Path> getWatchKeys() {
    return this.watchKeys;
  }

  /**
   * A file was created or saved under {@code devDir}. When it is in the folder of an experiment in
   * file mode, that experiment is returned (its consoles then show its files again), and a step file
   * goes to the engine (ScriptBoard.stepFileSaved), which runs it only while it runs that experiment.
   * Null for any other file. It used to run any step file of the experiment the first open console
   * had selected, in whatever the engine ran: with that experiment edited while another one's session
   * runs, in that session's game.
   */
  public static Experiment fileSaved(Path devDir, Path file) {
    Experiment experiment = experimentOf(devDir, file);
    if (experiment == null) {
      return null;
    }
    // (the folder the experiment's steps are read from: "Steps", or "steps" made by hand)
    Path steps = Experiment.fileModeStepsDirectory(devDir.resolve(experiment.getDirectoryName()).toFile()).toPath();
    Path folder = file.getParent();
    try {
      if (folder != null && Files.isDirectory(steps) && Files.isSameFile(folder, steps)) {
        String source = FileUtils.readFileToString(file.toFile());
        ScriptBoard.stepFileSaved(experiment, source, file.getFileName().toString());
      }
    } catch (IOException ioe) {
      Logger.error("Unable to read contents of Step " + file.getFileName() + " check your permissions.");
    }
    return experiment;
  }

  /**
   * The experiment in file mode whose folder (dev/&lt;name&gt;_&lt;id&gt;/) holds this file, or null. The
   * whole folder name must match: matching part of the path took dev/Big-Trust_12/ for dev/Trust_1/.
   */
  static Experiment experimentOf(Path devDir, Path file) {
    Path relative;
    try {
      relative = devDir.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
    } catch (IllegalArgumentException elsewhere) {
      return null;
    }
    if (relative.getNameCount() < 2 || relative.startsWith("..")) {
      return null;
    }
    String folder = relative.getName(0).toString();
    for (Experiment experiment : Experiment.find.where().eq("fileMode", true).findList()) {
      if (folder.equals(experiment.getDirectoryName())) {
        return experiment;
      }
    }
    return null;
  }

  /** The consoles working on one of these experiments get their User JSON again, to show its files. */
  public void showFilesOf(Collection<Long> experimentIds) {
    Map<String, ObjectNode> userJson = new HashMap<>();
    for (Admin admin : adminListeners) {
      User user = admin.getUser();
      if (user == null || user.email == null) {
        continue;
      }
      String email = user.email.toLowerCase();
      if (!userJson.containsKey(email)) {
        User fresh = User.findByEmail(user.email);
        boolean workingOnIt = fresh != null && fresh.selectedExperiment != null && experimentIds.contains(fresh.selectedExperiment.id);
        userJson.put(email, workingOnIt ? fresh.toJson() : null);
      }
      ObjectNode json = userJson.get(email);
      if (json != null) {
        admin.write(json);
      }
    }
  }
}
