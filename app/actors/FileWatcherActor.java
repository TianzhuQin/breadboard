package actors;

import actors.FileWatcherActorProtocol.FileWatch;
import akka.actor.UntypedActor;
import models.Experiment;
import models.FileWatcher;
import play.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

public class FileWatcherActor extends UntypedActor {
  private static DateFormat dateFormat;

  public FileWatcherActor() {
    dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
  }


  @Override
  public void onReceive(Object message) {
    if (message instanceof FileWatch) {
      FileWatcher fileWatcher = ((FileWatch) message).fileWatcher;

      fileWatcher.incrementUpdateIteration();
      Long updateIteration = fileWatcher.getUpdateIteration();
      if (updateIteration % 100 == 0) {
        Logger.debug(dateFormat.format(new Date()) + " - FileWatch:" + updateIteration);
      }

      WatchKey watchKey;
      watchKey = fileWatcher.getWatcher().poll();
      if (watchKey == null) {
        return;
      }

      Path dir = fileWatcher.getWatchKeys().get(watchKey);
      if (dir == null) {
        Logger.error("Event triggered from watchKey not registered properly.");
        return;
      }

      // The experiments in file mode whose files changed.
      Set<Long> changed = new HashSet<>();
      for (WatchEvent<?> event: watchKey.pollEvents()) {
        WatchEvent<Path> ev = (WatchEvent<Path>) event;
        Path name = ev.context();
        Path child = dir.resolve(name);
        Logger.debug(child.toString());

        if (ev.kind() == ENTRY_CREATE && child.toFile().isDirectory()) {
          // new directory, need to watch it
          try {
            fileWatcher.registerRecursive(child);
          } catch (IOException ioe) {
            Logger.error("Unable to watch directory " + child.toFile().getPath() + " check the permissions.");
          }
          Logger.debug("DIRECTORY ENTRY_CREATE");
        } else if ((ev.kind() == ENTRY_CREATE || ev.kind() == ENTRY_MODIFY) && child.toFile().isFile()) {
          // A file of an experiment in file mode: a step file goes to the engine, which runs it only
          // while it runs that experiment (it used to run there whatever the engine ran).
          try {
            Experiment experiment = FileWatcher.fileSaved(fileWatcher.getDevPath(), child);
            if (experiment != null) {
              changed.add(experiment.id);
            }
          } catch (Exception e) {
            // (the key below is reset all the same, or this folder would not be watched any more)
            Logger.error("Could not take up the change of " + child, e);
          }
          Logger.debug("FILE ENTRY_MODIFY || ENTRY_CREATE");
        } else if (ev.kind() == ENTRY_DELETE) {
          Logger.debug("ENTRY_DELETE");
        }
      }

      if (!changed.isEmpty()) {
        // The consoles working on it show its files as they are now.
        try {
          fileWatcher.showFilesOf(changed);
        } catch (Exception e) {
          Logger.error("Could not send the consoles the changed files", e);
        }
      }

      boolean valid = watchKey.reset();
      if (!valid) {
        fileWatcher.getWatchKeys().remove(watchKey);
        Logger.debug("Watched directory was deleted.");
      }
    }
  }
}

