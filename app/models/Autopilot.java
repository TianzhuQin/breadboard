package models;

import akka.actor.ActorRef;
import akka.actor.Props;
import com.fasterxml.jackson.databind.JsonNode;
import play.Logger;
import play.Play;
import play.libs.Akka;
import play.mvc.WebSocket;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Runs an experiment without anyone at the admin console.
 *
 * <p>Normally nothing happens until a researcher logs into the admin, selects an experiment and
 * launches an instance; until then participants have nowhere to go. With autopilot on, the server
 * does that itself at startup, and again after each finished session if {@code continuous} is set.
 * When the game starts, who waits, and what happens to people who leave is up to the experiment's
 * scripts (see groovy/autopilot.groovy). A researcher who logs in with the autopilot's account
 * simply sees the running session.
 *
 * <p>Participants enter through links that always lead to the currently open session:
 * {@code /play/{experimentId}/login} and {@code /play/{experimentId}/prolific?PROLIFIC_PID=...}.
 *
 * <pre>
 * breadboard.autopilot.experiment = "My experiment"   # experiment name; unset = autopilot off
 * breadboard.autopilot.user = "researcher@lab.org"    # admin account it runs as (default: first user)
 * breadboard.autopilot.continuous = true              # open a new session when one finishes
 * breadboard.autopilot.nextSessionDelaySeconds = 10
 * </pre>
 */
public final class Autopilot {

  private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
    public Thread newThread(Runnable r) {
      Thread t = new Thread(r, "breadboard-autopilot");
      t.setDaemon(true);
      return t;
    }
  });

  private static volatile Long experimentId = null;

  private Autopilot() {}

  public static boolean isEnabled() {
    String name = Play.application().configuration().getString("breadboard.autopilot.experiment");
    return name != null && !name.trim().isEmpty();
  }

  /** The experiment the autopilot runs, or null when it is off or the experiment doesn't exist. */
  public static Experiment experiment() {
    String name = Play.application().configuration().getString("breadboard.autopilot.experiment");
    if (name == null || name.trim().isEmpty()) {
      return null;
    }
    return Experiment.findByName(name.trim());
  }

  /** Called once from Global.onStart. */
  public static void start() {
    if (!isEnabled()) {
      return;
    }
    // Give the application (Akka, Ebean, routes) a moment to finish starting.
    scheduler.schedule(new Runnable() {
      public void run() {
        openSession();
      }
    }, 3, TimeUnit.SECONDS);
  }

  /** Called by GameListener when an instance finishes. */
  public static void sessionFinished(ExperimentInstance instance) {
    if (!isEnabled() || instance == null || instance.experiment == null) {
      return;
    }
    Long id = experimentId;
    if (id == null || !id.equals(instance.experiment.id)) {
      return;
    }
    if (!Play.application().configuration().getBoolean("breadboard.autopilot.continuous", false)) {
      Logger.info("Autopilot: session " + instance.name + " finished; continuous is off, not opening another");
      return;
    }
    int delay = Play.application().configuration().getInt("breadboard.autopilot.nextSessionDelaySeconds", 10);
    Logger.info("Autopilot: session " + instance.name + " finished; opening the next one in " + delay + " s");
    scheduler.schedule(new Runnable() {
      public void run() {
        openSession();
      }
    }, delay, TimeUnit.SECONDS);
  }

  /** Open a new session: what a researcher does by selecting the experiment and pressing Launch. */
  static synchronized void openSession() {
    try {
      Experiment experiment = experiment();
      if (experiment == null) {
        Logger.error("Autopilot: experiment \"" + Play.application().configuration().getString("breadboard.autopilot.experiment") + "\" not found");
        return;
      }
      User user = operator();
      if (user == null) {
        Logger.error("Autopilot: no admin account to run as; create one first");
        return;
      }
      experimentId = experiment.id;
      // Through the setter, as Breadboard.SelectExperiment does: a plain field write wasn't saved.
      user.setSelectedExperiment(experiment);
      user.update();
      // Hand the actor a freshly loaded copy: Ebean checks every column on update, and the launch
      // updates the user again from the actor's thread, which failed with OptimisticLockException
      // when it was given this already-updated bean.
      user = User.findByEmail(user.email);

      ThrottledWebSocketOut out = new ThrottledWebSocketOut(new QuietOut(), 0L);
      ActorRef scriptBoard = Breadboard.instances.get(user.email);
      if (scriptBoard == null) {
        // The same as a first admin login, minus the experiment reload that LaunchGame does anyway.
        // The admin listener also matters: OnJoinStep only runs when one is registered.
        scriptBoard = Akka.system().actorOf(new Props(ScriptBoard.class));
        Breadboard.instances.put(user.email, scriptBoard);
        scriptBoard.tell(new Breadboard.AddAdmin(user, scriptBoard, out), null);
      }
      scriptBoard.tell(new Breadboard.LaunchGame(user, sessionName(), defaultParameters(experiment), out), null);
      Logger.info("Autopilot: opening a new session of \"" + experiment.name + "\" as " + user.email);
    } catch (Exception e) {
      Logger.error("Autopilot: could not open a session", e);
    }
  }

  /** The newest instance of an experiment that is running and hasn't started its game yet. */
  public static ExperimentInstance openInstance(Long experimentId) {
    Experiment experiment = Experiment.findById(experimentId);
    if (experiment == null) {
      return null;
    }
    ExperimentInstance open = null;
    for (ExperimentInstance instance : experiment.instances) {
      if (instance.status == ExperimentInstance.Status.RUNNING && !Boolean.TRUE.equals(instance.hasStarted)) {
        if (open == null || instance.id > open.id) {
          open = instance;
        }
      }
    }
    return open;
  }

  private static User operator() {
    String email = Play.application().configuration().getString("breadboard.autopilot.user");
    if (email != null && !email.trim().isEmpty()) {
      return User.findByEmail(email.trim());
    }
    List<User> users = User.findAll();
    return users.isEmpty() ? null : users.get(0);
  }

  private static LinkedHashMap<String, String> defaultParameters(Experiment experiment) {
    LinkedHashMap<String, String> params = new LinkedHashMap<String, String>();
    for (Parameter p : experiment.getParameters()) {
      if (p.defaultVal != null) {
        params.put(p.name, p.defaultVal);
      }
    }
    return params;
  }

  private static String sessionName() {
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    return "auto " + format.format(new Date()) + " UTC";
  }

  /** Stands in for the admin's websocket: nothing is listening, so output just goes to the debug log. */
  static class QuietOut implements WebSocket.Out<JsonNode> {
    public void write(JsonNode frame) {
      if (Logger.isDebugEnabled()) {
        Logger.debug("Autopilot output: " + frame);
      }
    }

    public void close() {}
  }
}
