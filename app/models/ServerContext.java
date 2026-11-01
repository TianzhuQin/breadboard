package models;

import java.security.AccessController;
import java.security.PrivilegedAction;

/**
 * Server work called by an experiment must not give its script protection domains to a long-lived
 * server thread. Ebean creates pool workers lazily on a save; without this boundary, a worker
 * inherits the script timer's access-control context and keeps that engine's classes loaded until
 * it dies, even after its task has returned. The action itself is server code, not a script closure.
 */
final class ServerContext {
  private ServerContext() {}

  static <T> T call(PrivilegedAction<T> action) {
    return AccessController.doPrivileged(action);
  }

  static void run(Runnable action) {
    call(() -> { action.run(); return null; });
  }
}
