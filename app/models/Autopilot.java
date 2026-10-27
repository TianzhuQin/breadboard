package models;

import akka.actor.ActorRef;
import akka.actor.Props;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import play.Configuration;
import play.Logger;
import play.Play;
import play.libs.Akka;
import play.libs.Json;
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
import java.util.function.Consumer;

/**
 * Runs an experiment without anyone at the admin console.
 *
 * <p>Normally nothing happens until a researcher logs into the admin, selects an experiment and
 * launches a session; until then participants have nowhere to go. With the autopilot on, the server
 * keeps a session of one experiment open by itself: at startup, right after the settings are saved,
 * again after each finished session if {@code continuous} is set, and whenever a periodic check finds
 * the engine running no session. It never ends another experiment's live session unless a save asks
 * it to ({@code takeOver}), and pauses when a researcher stops its session. The game starts when the
 * waiting room says so (groovy/autopilot.groovy; its values can be set in the console, see
 * {@link AutopilotHooks}). A researcher who logs in with the autopilot's account sees the session.
 *
 * <p>Participants enter through links that always lead to the currently open session:
 * {@code /play/{experimentId}/login} and {@code /play/{experimentId}/prolific?PROLIFIC_PID=...}.
 *
 * <p>The settings come from the console (GET/POST /autopilot, kept in the database as
 * {@link AutopilotSettings}). Until they are saved there, application.conf can turn it on:
 * <pre>
 * breadboard.autopilot.experiment = "My experiment"   # experiment name; unset = autopilot off
 * breadboard.autopilot.user = "researcher@lab.org"    # admin account it runs as (default: first user)
 * breadboard.autopilot.continuous = true              # open a new session when one finishes
 * breadboard.autopilot.nextSessionDelaySeconds = 10
 * </pre>
 */
public final class Autopilot {

  /** Where the settings in charge come from (GET /autopilot's {@code source}). */
  public static final String ADMIN = "admin";
  public static final String CONFIG = "config";
  public static final String OFF = "off";

  static final String PROLIFIC_QUERY = "PROLIFIC_PID={{%PROLIFIC_PID%}}&STUDY_ID={{%STUDY_ID%}}&SESSION_ID={{%SESSION_ID%}}";

  private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
    public Thread newThread(Runnable r) {
      Thread t = new Thread(r, "breadboard-autopilot");
      t.setDaemon(true);
      return t;
    }
  });

  // Why the last session could not be opened (or ran into an error while loading), for the console.
  private static volatile String lastProblem = null;

  // The last launch asked of an engine actor, and the session the engine ran then: until the engine
  // runs another one, that launch is under way, and asking again (a second save a moment later)
  // would open two sessions.
  private static volatile long launchRequestedAt = 0L;
  private static volatile Long sessionBeforeLaunch = null;
  private static final long LAUNCH_TIMEOUT_MS = 30000L;

  // How often the watchdog checks that a session is open (see watchdog()).
  static final long WATCHDOG_SECONDS = 30L;
  // Why no new session opens by itself until the settings are saved again, for the console: a
  // researcher stopped its session (paused), or its one session of a non-continuous run finished
  // (done). Not kept over a restart, which opens a session as before.
  private static volatile String pausedBecause = null;
  private static volatile String doneBecause = null;
  // When a finished session's pause (nextSessionDelaySeconds) ends and the next one opens; 0 when none is due.
  private static volatile long nextOpenAt = 0L;

  // Opens a session (openSession); a field only so tests can see when one would be opened.
  static volatile Consumer<String> opener = new Consumer<String>() {
    public void accept(String why) {
      openSession(why);
    }
  };

  private Autopilot() {}

  /** The settings in charge, and where they come from. */
  public static final class Effective {
    public final AutopilotSettings settings;
    public final String source;

    Effective(AutopilotSettings settings, String source) {
      this.settings = settings;
      this.source = source;
    }

    /** Switched on, with an experiment to run. */
    public boolean isOn() {
      return settings.enabled && settings.experimentId != null;
    }
  }

  /**
   * The settings saved in the console; else those in application.conf (breadboard.autopilot.*);
   * else the defaults, switched off.
   */
  public static Effective effective() {
    return effective(AutopilotSettings.load(), Play.application().configuration());
  }

  /** {@link #effective()} for the given saved settings (null: none) and configuration. */
  public static Effective effective(AutopilotSettings saved, Configuration conf) {
    if (saved != null) {
      return new Effective(saved, ADMIN);
    }
    String name = trimmedOrNull(conf.getString("breadboard.autopilot.experiment"));
    if (name != null) {
      return new Effective(fromConfig(name, conf), CONFIG);
    }
    return new Effective(new AutopilotSettings(), OFF);
  }

  /** breadboard.autopilot.experiment, or null when it is not set. */
  static String configuredExperimentName() {
    return trimmedOrNull(Play.application().configuration().getString("breadboard.autopilot.experiment"));
  }

  private static AutopilotSettings fromConfig(String experimentName, Configuration conf) {
    AutopilotSettings s = new AutopilotSettings();
    s.enabled = true;
    Experiment experiment = findExperimentByName(experimentName);
    s.experimentId = experiment == null ? null : experiment.id;
    s.continuous = conf.getBoolean("breadboard.autopilot.continuous", false);
    s.nextSessionDelaySeconds = Math.max(0, conf.getInt("breadboard.autopilot.nextSessionDelaySeconds", 10));
    // The experiment's own scripts set the waiting room up; the file doesn't override them.
    s.waitingRoomEnabled = false;
    s.replaceEnabled = false;
    s.operatorEmail = trimmedOrNull(conf.getString("breadboard.autopilot.user"));
    return s;
  }

  // Names are not unique; findByName threw for duplicates. The oldest one wins, as before.
  private static Experiment findExperimentByName(String name) {
    List<Experiment> found = Experiment.find.where().eq("name", name).orderBy("id asc").findList();
    return found.isEmpty() ? null : found.get(0);
  }

  /**
   * An experiment was deleted. Saved settings that run it are switched off and forget it, so the
   * console doesn't keep offering an experiment that is gone (and can save the settings again).
   */
  static void experimentDeleted(Long experimentId) {
    if (experimentId == null) {
      return;
    }
    try {
      AutopilotSettings saved = AutopilotSettings.load();
      if (saved != null && experimentId.equals(saved.experimentId)) {
        saved.enabled = false;
        saved.experimentId = null;
        saved.save();
        Logger.info("Autopilot: experiment " + experimentId + " was deleted; the autopilot is switched off");
      }
    } catch (Exception e) {
      Logger.error("Autopilot: could not switch off after experiment " + experimentId + " was deleted", e);
    }
  }

  public static boolean isEnabled() {
    return effective().isOn();
  }

  /** The experiment the autopilot runs, or null when it is off or the experiment doesn't exist. */
  public static Experiment experiment() {
    Effective e = effective();
    return e.isOn() ? Experiment.findById(e.settings.experimentId) : null;
  }

  // ---- when sessions open ------------------------------------------------------------------------

  /**
   * Called once from Global.onStart: with the autopilot on, a session opens shortly. From then on a
   * check every {@link #WATCHDOG_SECONDS} opens one whenever the autopilot is on and the engine runs
   * no session (see {@link #watchdog}).
   */
  public static void start() {
    // Not in tests: they call watchdog() themselves, and a check at a random moment would open
    // sessions in the middle of other tests.
    if (!Play.isTest()) {
      scheduler.scheduleWithFixedDelay(new Runnable() {
        public void run() {
          try {
            watchdog();
          } catch (Throwable t) {
            // Never let it escape: the executor would cancel every later check.
            Logger.error("Autopilot: the check for an open session failed", t);
          }
        }
      }, WATCHDOG_SECONDS, WATCHDOG_SECONDS, TimeUnit.SECONDS);
    }
    try {
      Effective e = effective();
      if (!e.isOn()) {
        return;
      }
      Logger.info("Autopilot: on (" + (ADMIN.equals(e.source) ? "settings saved in the console" : "application.conf")
          + "); opening a session of experiment " + e.settings.experimentId);
    } catch (Exception ex) {
      Logger.error("Autopilot: could not read its settings at startup", ex);
      return;
    }
    // Give the application (Akka, Ebean, routes) a moment to finish starting.
    scheduler.schedule(new Runnable() {
      public void run() {
        openWhenDue("the server started", true);
      }
    }, 3, TimeUnit.SECONDS);
  }

  /** Called by GameListener when a session finishes (played to the end, or given up for lack of people). */
  public static void sessionFinished(final ExperimentInstance instance) {
    if (instance == null || instance.experiment == null) {
      return;
    }
    Effective e;
    try {
      e = effective();
    } catch (Exception ex) {
      Logger.error("Autopilot: could not read its settings", ex);
      return;
    }
    if (!e.isOn()) {
      return;
    }
    boolean own = e.settings.experimentId.equals(instance.experiment.id);
    if (own && !e.settings.continuous) {
      // That was its one session; the watchdog leaves it at that.
      doneBecause = "Its session \"" + instance.name + "\" has finished, and opening the next session (continuous) "
          + "is off, so it opens no more. Save the settings to open another.";
      Logger.info("Autopilot: session " + instance.name + " finished; continuous is off, not opening another");
      return;
    }
    // Its own session finished (continuous), or another experiment's did and the engine is free again:
    // either way the autopilot's turn, after the pause it is set to keep between sessions.
    int delay = e.settings.nextSessionDelaySeconds;
    String why = own ? "session " + instance.name + " finished" : "session " + instance.name + " of another experiment finished";
    Logger.info("Autopilot: " + why + "; opening the next one in " + delay + " s");
    openLater(why, delay);
  }

  /**
   * openWhenDue(why, true) in {@code seconds}; the watchdog leaves it to that until then. A later
   * call replaces an earlier one that is still waiting.
   */
  private static void openLater(final String why, int seconds) {
    final long due = System.currentTimeMillis() + seconds * 1000L;
    nextOpenAt = due;
    scheduler.schedule(new Runnable() {
      public void run() {
        if (nextOpenAt != due) {
          return;
        }
        nextOpenAt = 0L;
        openWhenDue(why, true);
      }
    }, seconds, TimeUnit.SECONDS);
  }

  /**
   * Every {@link #WATCHDOG_SECONDS}: with the autopilot on, open a session if the engine runs none.
   * Sessions otherwise open only at startup, on a save and when one finishes, so one ended any other
   * way (another launch, a reload, a session taken up at startup) left it waiting for good. It leaves
   * a running session of another experiment alone, waits while a finished session's pause runs, and
   * does nothing while paused or after the one session of a non-continuous run.
   */
  static void watchdog() {
    if (nextOpenAt > System.currentTimeMillis()) {
      return;
    }
    openWhenDue("no session was open", true);
  }

  /**
   * A researcher stopped the session the engine ran (StopGame). If it was of the autopilot's
   * experiment, the autopilot pauses: it opens no new session by itself until the settings are saved
   * again, rather than opening one behind the researcher's back.
   */
  static void sessionStopped(ExperimentInstance instance, User by) {
    if (instance == null || instance.experiment == null) {
      return;
    }
    // Read outside the try: the Ebean enhancer breaks entity field reads inside one.
    Long experimentId = instance.experiment.id;
    String name = instance.name;
    String who = by == null ? "" : " by " + by.email;
    Effective e;
    try {
      e = effective();
    } catch (Exception ex) {
      Logger.error("Autopilot: could not read its settings", ex);
      return;
    }
    if (e.isOn() && e.settings.experimentId.equals(experimentId)) {
      pausedBecause = "It is paused: its session \"" + name + "\" was stopped" + who
          + ". It opens no new session until the settings are saved again.";
      Logger.info("Autopilot: session " + name + " was stopped" + who + "; pausing");
    }
  }

  /**
   * New settings were saved in the console. A session of the experiment that is running now gets the
   * new waiting-room and takeover values at once; with the autopilot on, a session opens now if
   * none of this experiment is open and the engine runs no other session. A session of another
   * experiment is ended only with {@code takeOver} (the console asks first): a plain edit used to end
   * another researcher's live session.
   */
  public static void settingsSaved(final AutopilotSettings saved, final boolean takeOver) {
    // Saving is the researcher's go-ahead again.
    pausedBecause = null;
    doneBecause = null;
    scheduler.execute(new Runnable() {
      public void run() {
        try {
          Experiment running = ScriptBoard.engineExperiment();
          if (running != null && running.id.equals(saved.experimentId)) {
            ScriptBoard.afterStepsLoaded(running);
          }
        } catch (Exception e) {
          Logger.error("Autopilot: could not apply the new settings to the running session", e);
        }
        openWhenDue(takeOver ? "its settings were saved, taking over the engine" : "its settings were saved", !takeOver);
      }
    });
  }

  /**
   * Open a session of the autopilot's experiment, unless it is off, paused or done, or the engine runs
   * one of that experiment already. With {@code onlyIfIdle} the engine is also left alone while it
   * runs a session of another experiment: opening would end that session.
   */
  static void openWhenDue(String why, boolean onlyIfIdle) {
    try {
      Effective e = effective();
      if (!e.isOn()) {
        return;
      }
      if (pausedBecause != null || doneBecause != null) {
        Logger.debug("Autopilot: " + why + ", but it is " + (pausedBecause != null ? "paused" : "done") + "; not opening one");
        return;
      }
      if (launchUnderWay()) {
        Logger.debug("Autopilot: " + why + "; a session is being opened already");
        return;
      }
      ExperimentInstance live = liveEngineSession();
      if (live != null && live.experiment != null && e.settings.experimentId.equals(live.experiment.id)) {
        Logger.debug("Autopilot: " + why + "; session " + live.name + " is open already");
        return;
      }
      if (live != null && onlyIfIdle) {
        Logger.info("Autopilot: " + why + ", but the engine is running session \"" + live.name
            + "\" of another experiment; not opening one");
        return;
      }
      opener.accept(why);
    } catch (Exception ex) {
      Logger.error("Autopilot: could not decide whether to open a session", ex);
    }
  }

  /** Open a new session: what a researcher does by selecting the experiment and pressing Launch. */
  static synchronized void openSession(String why) {
    lastProblem = null;
    try {
      Effective e = effective();
      if (!e.isOn()) {
        return;
      }
      Experiment experiment = Experiment.findById(e.settings.experimentId);
      if (experiment == null) {
        problem("There is no experiment with id " + e.settings.experimentId + " any more.");
        return;
      }
      User user = operator(e.settings);
      if (user == null) {
        problem("There is no admin account to open sessions as; create one first.");
        return;
      }
      if (user.selectedExperiment == null || !experiment.id.equals(user.selectedExperiment.id)) {
        // Through the setter, as Breadboard.SelectExperiment does: a plain field write wasn't saved.
        user.setSelectedExperiment(experiment);
        user.update();
      }
      // Hand the actor a freshly loaded copy: Ebean checks every column on update, and the launch
      // updates the user again from the actor's thread, which failed with OptimisticLockException
      // when it was given this already-updated bean.
      user = User.findByEmail(user.email);

      ActorRef scriptBoard = Breadboard.instances.get(user.email);
      if (scriptBoard == null) {
        // The account's engine actor, as its first console login would create it (that login then
        // finds it and leaves the session opened here alone). No Admin: OnJoinStep and OnLeaveStep
        // run without one now, and the output goes to the account's open consoles (output()).
        ActorRef created = Akka.system().actorOf(new Props(ScriptBoard.class));
        ActorRef raced = Breadboard.instances.putIfAbsent(user.email, created);
        if (raced != null) {
          Akka.system().stop(created);
          scriptBoard = raced;
        } else {
          scriptBoard = created;
        }
      }
      ExperimentInstance before = ScriptBoard.engineSession();
      sessionBeforeLaunch = before == null ? null : before.id;
      launchRequestedAt = System.currentTimeMillis();
      // No parameters: LaunchGame gives each its default.
      scriptBoard.tell(new Breadboard.LaunchGame(user, sessionName(), new LinkedHashMap(), output(user.email)), null);
      Logger.info("Autopilot: opening a new session of \"" + experiment.name + "\" as " + user.email + " (" + why + ")");
    } catch (Exception ex) {
      problem("The session could not be opened: " + ScriptBoardSupport.describeError(ex));
      Logger.error("Autopilot: could not open a session", ex);
    }
  }

  /** Whether the last launch asked for hasn't been carried out yet (see launchRequestedAt). */
  static boolean launchUnderWay() {
    if (System.currentTimeMillis() - launchRequestedAt > LAUNCH_TIMEOUT_MS) {
      return false;
    }
    ExperimentInstance now = ScriptBoard.engineSession();
    Long current = now == null ? null : now.id;
    return current == null ? sessionBeforeLaunch == null : current.equals(sessionBeforeLaunch);
  }

  private static void problem(String message) {
    lastProblem = message;
    Logger.error("Autopilot: " + message);
  }

  /**
   * The account the autopilot opens sessions as: the one that saved the settings, or the one
   * breadboard.autopilot.user names; failing that an owner of the experiment, or the first account.
   */
  static User operator(AutopilotSettings settings) {
    String[] candidates = {settings.operatorEmail,
        trimmedOrNull(Play.application().configuration().getString("breadboard.autopilot.user"))};
    for (String email : candidates) {
      if (email != null) {
        User user = User.findByEmail(email);
        if (user != null) {
          return user;
        }
      }
    }
    if (settings.experimentId != null) {
      for (User user : User.findAll()) {
        for (Experiment owned : user.ownedExperiments) {
          if (settings.experimentId.equals(owned.id)) {
            return user;
          }
        }
      }
    }
    List<User> users = User.findAll();
    return users.isEmpty() ? null : users.get(0);
  }

  private static String sessionName() {
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    return "auto " + format.format(new Date()) + " UTC";
  }

  // ---- what the engine is doing --------------------------------------------------------------------

  /**
   * The session of this experiment participants can join now: the engine's own session, while it is
   * running and its game hasn't started; else null. (It used to be the newest running session in the
   * database, which could be one the engine no longer ran, e.g. from before a restart.)
   */
  public static ExperimentInstance openInstance(Long experimentId) {
    ExperimentInstance live = liveEngineSession();
    if (experimentId == null || live == null || live.isTestInstance() || live.experiment == null
        || !experimentId.equals(live.experiment.id)) {
      return null;
    }
    if (live.status != ExperimentInstance.Status.RUNNING || Boolean.TRUE.equals(live.hasStarted)) {
      return null;
    }
    return live;
  }

  /** The session the engine runs right now, if it is still going (RUNNING or TESTING); else null. */
  static ExperimentInstance liveEngineSession() {
    ExperimentInstance session = ScriptBoard.engineSession();
    if (session == null || session.id == null) {
      return null;
    }
    if (session.isTestInstance()) {
      return session.status == ExperimentInstance.Status.TESTING ? session : null;
    }
    // The engine's copy can be stale (e.g. stopped through a freshly loaded copy).
    ExperimentInstance fresh = ExperimentInstance.findById(session.id);
    if (fresh == null || fresh.status == null) {
      return null;
    }
    boolean live = fresh.status == ExperimentInstance.Status.RUNNING || fresh.status == ExperimentInstance.Status.TESTING;
    return live ? fresh : null;
  }

  /** The engine's session if it belongs to this experiment, whatever its state (it may have ended). */
  static ExperimentInstance engineSessionOf(Long experimentId) {
    ExperimentInstance session = ScriptBoard.engineSession();
    if (experimentId == null || session == null || session.id == null || session.isTestInstance()) {
      return null;
    }
    ExperimentInstance fresh = ExperimentInstance.findById(session.id);
    if (fresh == null || fresh.experiment == null || !experimentId.equals(fresh.experiment.id)) {
      return null;
    }
    return fresh;
  }

  // ---- GET /autopilot --------------------------------------------------------------------------------

  /** The console's AutopilotState: {settings, source, status}. */
  public static ObjectNode stateJson() {
    Effective e = effective();
    ObjectNode json = Json.newObject();
    json.put("settings", e.settings.toJson());
    json.put("source", e.source);
    json.put("status", statusJson(e));
    return json;
  }

  static ObjectNode statusJson(Effective e) {
    ObjectNode status = Json.newObject();
    Long id = e.settings.experimentId;
    Experiment experiment = id == null ? null : Experiment.findById(id);
    status.put("on", e.isOn() && experiment != null);
    if (experiment == null) {
      status.putNull("experimentId");
      status.putNull("experimentName");
      status.putNull("instance");
      status.putNull("links");
    } else {
      status.put("experimentId", experiment.id);
      status.put("experimentName", experiment.name);
      ExperimentInstance session = engineSessionOf(experiment.id);
      if (session == null) {
        status.putNull("instance");
      } else {
        ObjectNode instance = status.putObject("instance");
        instance.put("id", session.id);
        instance.put("name", session.name);
        instance.put("status", session.status == null ? "" : session.status.toString());
        instance.put("gameStarted", Boolean.TRUE.equals(session.hasStarted));
      }
      ObjectNode links = status.putObject("links");
      links.put("login", "/play/" + experiment.id + "/login");
      links.put("prolific", "/play/" + experiment.id + "/prolific?" + PROLIFIC_QUERY);
    }
    status.put("paused", e.isOn() && pausedBecause != null);
    long due = nextOpenAt;
    if (e.isOn() && due > System.currentTimeMillis()) {
      status.put("nextOpenAt", Timestamps.iso(new Date(due)));
    } else {
      status.putNull("nextOpenAt");
    }
    // A live session of another experiment holds the engine: the autopilot waits for it to end (a
    // save with takeOver ends it). The console names it when it asks before taking over.
    ExperimentInstance other = otherLiveSession(id);
    if (other == null) {
      status.putNull("engineSession");
    } else {
      ObjectNode session = status.putObject("engineSession");
      session.put("id", other.id);
      session.put("name", other.name);
      Experiment of = other.experiment == null ? null : Experiment.findById(other.experiment.id);
      if (of == null) {
        session.putNull("experimentId");
        session.putNull("experimentName");
      } else {
        session.put("experimentId", of.id);
        session.put("experimentName", of.name);
      }
      String owner = ScriptBoard.engineOwnerEmail();
      if (owner == null) {
        session.putNull("owner");
      } else {
        session.put("owner", owner);
      }
    }
    String problem = problem(e, experiment, other);
    if (problem == null) {
      status.putNull("problem");
    } else {
      status.put("problem", problem);
    }
    return status;
  }

  /** The engine's live session when it is not of this experiment (or the autopilot has none), else null. */
  private static ExperimentInstance otherLiveSession(Long experimentId) {
    ExperimentInstance live = liveEngineSession();
    if (live == null || live.isTestInstance()) {
      return null;
    }
    boolean ours = experimentId != null && live.experiment != null && experimentId.equals(live.experiment.id);
    return ours ? null : live;
  }

  /** Why the autopilot, switched on, can't keep a session open; null when nothing is wrong. */
  private static String problem(Effective e, Experiment experiment, ExperimentInstance otherSession) {
    if (!e.settings.enabled) {
      return null;
    }
    if (e.settings.experimentId == null) {
      return CONFIG.equals(e.source)
          ? "application.conf names the experiment \"" + configuredExperimentName()
              + "\" (breadboard.autopilot.experiment), but there is no experiment with that name."
          : "No experiment is chosen.";
    }
    if (experiment == null) {
      return "The experiment it was set to run (id " + e.settings.experimentId + ") no longer exists. Choose another one.";
    }
    if (operator(e.settings) == null) {
      return "There is no admin account to open sessions as.";
    }
    if (pausedBecause != null) {
      return pausedBecause;
    }
    if (doneBecause != null) {
      return doneBecause;
    }
    if (otherSession != null) {
      String owner = ScriptBoard.engineOwnerEmail();
      return "Another session is running: \"" + otherSession.name + "\"" + (owner == null ? "" : " (" + owner + ")")
          + ". The autopilot opens its own session when that one ends.";
    }
    return lastProblem;
  }

  // ---- the autopilot's console output ----------------------------------------------------------------

  private static final AccountOut accountOut = new AccountOut();
  private static volatile ThrottledWebSocketOut output;

  /** Whether this is the autopilot's output, which writes to every console tab of its account. */
  static boolean isOutput(ThrottledWebSocketOut out) {
    return out != null && out == output;
  }

  /** One shared output (each ThrottledWebSocketOut starts a timer thread), pointed at the operator. */
  private static synchronized ThrottledWebSocketOut output(String email) {
    accountOut.email = email;
    if (output == null) {
      output = new ThrottledWebSocketOut(accountOut, 0L);
    }
    return output;
  }

  /**
   * Stands in for the console socket of the sessions the autopilot opens: what the engine says goes to
   * the operator's open console tabs (the session's User JSON, step output), and to the log when none
   * is open. Errors are kept for GET /autopilot's {@code status.problem}.
   */
  static final class AccountOut implements WebSocket.Out<JsonNode> {
    volatile String email;

    public void write(JsonNode frame) {
      JsonNode error = frame == null ? null : frame.get("error");
      if (error != null && error.isTextual() && !error.textValue().trim().isEmpty()) {
        String text = error.textValue().trim();
        lastProblem = "The last session it opened reported an error: " + (text.length() > 500 ? text.substring(0, 500) + "…" : text);
        Logger.warn("Autopilot: " + text);
        if (frame.has("request") && "LaunchGame".equals(frame.get("request").asText())) {
          launchRequestedAt = 0L;   // refused: nothing is under way any more
        }
      }
      String to = email;
      boolean shown = to != null && ScriptBoard.toAccount(to, frame);
      if (!shown && Logger.isDebugEnabled()) {
        Logger.debug("Autopilot output: " + frame);
      }
    }

    public void close() {}
  }

  private static String trimmedOrNull(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }
}
