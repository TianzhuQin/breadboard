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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Runs an experiment without anyone at the admin console.
 *
 * <p>Normally nothing happens until a researcher logs into the admin, selects an experiment and
 * launches a session; until then participants have nowhere to go. With the autopilot on, the server
 * keeps a session of one experiment open by itself: at startup, right after the settings are saved
 * or Resume is pressed, again after each finished session if {@code continuous} is set, and whenever
 * a periodic check finds the engine running no session. It never ends another experiment's live
 * session unless a save asks it to ({@code takeOver}), doesn't take the engine by itself from a
 * researcher who has another experiment loaded in it and a console open, and pauses when a
 * researcher's action ends its session (Stop, or launching, resuming or reloading something else;
 * selecting another experiment, to edit it, leaves its session running). The game starts when the
 * waiting room says so (groovy/autopilot.groovy; its values can be set in the console, see
 * {@link AutopilotHooks}). A researcher's console that has its experiment selected (selected then,
 * or when signing in) shows its session; one with another experiment selected keeps that selection
 * (the engine info in the User JSON names what the engine runs).
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

  private static volatile ScheduledExecutorService scheduler = newScheduler();

  private static ScheduledExecutorService newScheduler() {
    return Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
      public Thread newThread(Runnable r) {
        Thread t = new Thread(r, "breadboard-autopilot");
        t.setDaemon(true);
        return t;
      }
    });
  }

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
  // The watchdog's repeating task, cancelled by stop(); and whether stop() has run (an application
  // that has stopped, e.g. before a reload in dev mode, must not open sessions any more).
  private static volatile ScheduledFuture<?> watchdogTask = null;
  private static volatile boolean stopped = false;
  // Why no new session opens by itself until someone presses Resume or saves the settings, for the
  // console: a researcher's action ended its session (paused), or its one session of a non-continuous
  // run finished (done). Each is the reason alone; problem() adds what to do about it. Kept in the
  // database too (AutopilotSettings.saveHalt), so a restart doesn't quietly start recruiting again.
  private static volatile String pausedBecause = null;
  private static volatile String doneBecause = null;
  // When a finished session's pause (nextSessionDelaySeconds) ends and the next one opens; 0 when none is due.
  private static volatile long nextOpenAt = 0L;

  /** Opens a session (see openSession for the arguments). */
  public interface Opener {
    void open(String why, boolean onlyIfIdle, boolean automatic);
  }

  // Opens a session (openSession); a field only so tests can see when one would be opened.
  static volatile Opener opener = new Opener() {
    public void open(String why, boolean onlyIfIdle, boolean automatic) {
      openSession(why, onlyIfIdle, automatic);
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
        setHalt(null, null, null);
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
   * Called once from Global.onStart: with the autopilot on, a session opens shortly, unless it was
   * paused or done before the restart. From then on a check every {@link #WATCHDOG_SECONDS} opens one
   * whenever the autopilot is on and the engine runs no session (see {@link #watchdog}).
   */
  public static void start() {
    if (stopped) {
      // Started again in the same class after stop() (a reload normally brings a fresh copy).
      scheduler = newScheduler();
      stopped = false;
    }
    restoreHalt();
    // Not in tests: they call watchdog() themselves, and a check at a random moment would open
    // sessions in the middle of other tests.
    if (!Play.isTest()) {
      watchdogTask = scheduler.scheduleWithFixedDelay(new Runnable() {
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
          + "); " + (pausedBecause != null || doneBecause != null
              ? "it was " + (pausedBecause != null ? "paused" : "done") + " before the restart, so it opens no session until Resume is pressed"
              : "opening a session of experiment " + e.settings.experimentId));
    } catch (Exception ex) {
      Logger.error("Autopilot: could not read its settings at startup", ex);
      return;
    }
    // Give the application (Akka, Ebean, routes) a moment to finish starting.
    scheduler.schedule(new Runnable() {
      public void run() {
        openWhenDue("the server started", true, true);
      }
    }, 3, TimeUnit.SECONDS);
  }

  /**
   * Called from Global.onStop. The application is going away (in dev mode a reload starts a new one,
   * with its own copy of this class): its periodic check and any session it was about to open would
   * otherwise keep running against the old application, beside the new one's.
   */
  public static void stop() {
    stopped = true;
    ScheduledFuture<?> task = watchdogTask;
    if (task != null) {
      task.cancel(false);
    }
    scheduler.shutdownNow();
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
      setHalt(e.settings.experimentId, pausedBecause, "Its session \"" + instance.name
          + "\" has finished, and opening the next session (continuous) is off");
      Logger.info("Autopilot: session " + instance.name + " finished; continuous is off, not opening another");
      return;
    }
    if (pausedBecause != null || doneBecause != null) {
      // Nothing opens by itself until Resume (or a save); a next session counted down to here would
      // be shown as due, and opened the moment the pause is lifted.
      Logger.info("Autopilot: session " + instance.name + " finished; it is " + (pausedBecause != null ? "paused" : "done")
          + ", so no next session is scheduled");
      return;
    }
    // Its own session finished (continuous), or another experiment's did and the engine is free again:
    // either way the autopilot's turn, after the pause it is set to keep between sessions (unless the
    // researcher whose session it was has their experiment still loaded: see openWhenDue).
    int delay = e.settings.nextSessionDelaySeconds;
    String why = own ? "session " + instance.name + " finished" : "session " + instance.name + " of another experiment finished";
    Logger.info("Autopilot: " + why + "; opening the next one in " + delay + " s");
    openLater(why, delay);
  }

  /**
   * openWhenDue(why, true, true) in {@code seconds}; the watchdog leaves it to that until then. A
   * later call replaces an earlier one that is still waiting.
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
        openWhenDue(why, true, true);
      }
    }, seconds, TimeUnit.SECONDS);
  }

  /**
   * Every {@link #WATCHDOG_SECONDS}: with the autopilot on, open a session if the engine runs none.
   * Sessions otherwise open only at startup, on a save and when one finishes, so one ended any other
   * way (a session taken up at startup, a researcher's console closed after loading another
   * experiment) left it waiting for good. It leaves a running session of another experiment alone,
   * and an engine a researcher has loaded with another experiment while their console is open; it
   * waits while a finished session's pause runs, and does nothing while paused or after the one
   * session of a non-continuous run.
   */
  static void watchdog() {
    if (stopped || nextOpenAt > System.currentTimeMillis()) {
      return;
    }
    openWhenDue("no session was open", true, true);
  }

  /**
   * A researcher stopped the session the engine ran (StopGame). See
   * {@link #sessionStopped(ExperimentInstance, User, String)}.
   */
  static void sessionStopped(ExperimentInstance instance, User by) {
    sessionStopped(instance, by, "was stopped by %s");
  }

  /**
   * A researcher's action ends the session the engine runs: Stop, or selecting another session,
   * launching another session or reloading the engine (selecting another experiment leaves it
   * running). If it is of the autopilot's experiment, the autopilot pauses: it opens no new session
   * by itself until someone presses Resume (or saves the settings), rather than taking the engine
   * back behind the researcher's back. Nothing for a session that had finished: no researcher ended
   * it (Stop pressed on it paused the autopilot, which also called off the next session it was about
   * to open). {@code how} says what happened, with %s for who did it.
   */
  static void sessionStopped(ExperimentInstance instance, User by, String how) {
    if (instance == null || instance.experiment == null || instance.id == null) {
      return;
    }
    // Read outside the try: the Ebean enhancer breaks entity field reads inside one.
    Long experimentId = instance.experiment.id;
    String name = instance.name;
    ExperimentInstance now = ExperimentInstance.findById(instance.id);
    if (now != null && now.status == ExperimentInstance.Status.FINISHED) {
      return;
    }
    String who = by == null || by.email == null ? "a researcher" : by.email;
    Effective e;
    try {
      e = effective();
    } catch (Exception ex) {
      Logger.error("Autopilot: could not read its settings", ex);
      return;
    }
    if (e.isOn() && e.settings.experimentId.equals(experimentId)) {
      String what = String.format(how, who);
      setHalt(experimentId, "its session \"" + name + "\" " + what, doneBecause);
      Logger.info("Autopilot: session " + name + " " + what + "; pausing");
    }
  }

  /**
   * The steps of the session it just opened didn't load (a mistake in a step): ScriptBoard stopped
   * that session, since its waiting room and game come from the steps, and the autopilot pauses until
   * someone fixes them and presses Resume, instead of sending participants into a game that never
   * starts. {@code failed} is "<step>: <what went wrong>".
   */
  static void stepsFailed(ExperimentInstance instance, String failed) {
    if (instance == null || instance.experiment == null) {
      return;
    }
    // Read outside the try: the Ebean enhancer breaks entity field reads inside one.
    Long experimentId = instance.experiment.id;
    String name = instance.name;
    setHalt(experimentId, "the steps of its session \"" + name + "\" didn't load (" + failed
        + "), so that session was stopped before anyone could join. Fix the step on the Steps page first", doneBecause);
    lastProblem = null;
    Logger.warn("Autopilot: the steps of session " + name + " didn't load (" + failed + "); the session was stopped and the autopilot paused");
  }

  /**
   * An experiment's steps loaded without an error (a launch, a reload, a session selected): an error
   * the autopilot kept from an earlier load of its experiment is out of date.
   */
  static void stepsLoaded(Experiment experiment) {
    Long id = experiment == null ? null : experiment.id;
    if (id == null || lastProblem == null) {
      return;
    }
    try {
      Effective e = effective();
      if (e.isOn() && id.equals(e.settings.experimentId)) {
        lastProblem = null;
      }
    } catch (Exception ex) {
      Logger.debug("Autopilot: could not read its settings: " + ex);
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
    setHalt(null, null, null);
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
        openWhenDue(takeOver ? "its settings were saved, taking over the engine" : "its settings were saved", !takeOver, false);
      }
    });
  }

  /**
   * Resume was pressed (POST /autopilot/resume). It changes no settings, so it also works while
   * application.conf is in charge, where saving is refused: the pause or the done state ends, and a
   * session opens now unless one of the experiment is open or another experiment's session is live
   * (that one is left to end). A researcher who only has another experiment loaded gives it up.
   */
  public static void resume(User by) {
    setHalt(null, null, null);
    final String why = "it was resumed" + (by == null ? "" : " by " + by.email);
    Logger.info("Autopilot: " + why);
    scheduler.execute(new Runnable() {
      public void run() {
        openWhenDue(why, true, false);
      }
    });
  }

  /**
   * Open a session of the autopilot's experiment, unless it is off, paused or done, or the engine runs
   * one of that experiment already. With {@code onlyIfIdle} the engine is also left alone while it
   * runs a session of another experiment: opening would end that session. An {@code automatic} open
   * (the periodic check, startup, after a finished session) also leaves the engine to a researcher
   * who has another experiment loaded in it and a console open: opening would rebuild it under them.
   */
  static void openWhenDue(String why, boolean onlyIfIdle, boolean automatic) {
    if (stopped) {
      return;
    }
    try {
      Effective e = effective();
      if (!e.isOn()) {
        return;
      }
      if (launchUnderWay()) {
        Logger.debug("Autopilot: " + why + "; a session is being opened already");
        return;
      }
      String notNow = whyNotOpen(e, onlyIfIdle, automatic);
      if (notNow != null) {
        Logger.debug("Autopilot: " + why + ", but " + notNow + "; not opening one");
        return;
      }
      opener.open(why, onlyIfIdle, automatic);
    } catch (Exception ex) {
      Logger.error("Autopilot: could not decide whether to open a session", ex);
    }
  }

  /** Why a session shouldn't open now (for the log), or null when it should (see openWhenDue). */
  private static String whyNotOpen(Effective e, boolean onlyIfIdle, boolean automatic) {
    if (pausedBecause != null) {
      return "it is paused";
    }
    if (doneBecause != null) {
      return "it is done";
    }
    ExperimentInstance live = liveEngineSession();
    if (live != null && live.experiment != null && e.settings.experimentId.equals(live.experiment.id)) {
      return "session " + live.name + " is open already";
    }
    if (live != null && onlyIfIdle) {
      return "the engine is running session \"" + live.name + "\" of another experiment";
    }
    if (live == null && automatic) {
      String holder = ScriptBoard.researcherHolding(e.settings.experimentId);
      if (holder != null) {
        return holder + " has another experiment loaded in the engine";
      }
    }
    return null;
  }

  /**
   * Open a new session: what a researcher does by selecting the experiment and pressing Launch, except
   * that the operator's selection is left alone (the launch names the experiment itself). The engine
   * actor checks again, under the engine lock, that it is still wanted (see launchStillWanted).
   */
  static synchronized void openSession(String why, boolean onlyIfIdle, boolean automatic) {
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
      // Hand the actor a freshly loaded copy: Ebean checks every column on update, and the launch
      // updates the user from the actor's thread.
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
      scriptBoard.tell(new Breadboard.LaunchGame(user, experiment, sessionName(), new LinkedHashMap(), output(user.email),
          onlyIfIdle, automatic), null);
      Logger.info("Autopilot: opening a new session of \"" + experiment.name + "\" as " + user.email + " (" + why + ")");
    } catch (Exception ex) {
      problem("The session could not be opened: " + ScriptBoardSupport.describeError(ex));
      Logger.error("Autopilot: could not open a session", ex);
    }
  }

  /**
   * Whether a launch the autopilot asked for should still go ahead, now that the engine actor carries
   * it out under the engine lock: since it was asked, a researcher may have paused it, launched a
   * session or loaded another experiment, or the settings may have changed. Dropped when not.
   */
  static boolean launchStillWanted(Long experimentId, boolean onlyIfIdle, boolean automatic) {
    String notNow;
    if (stopped) {
      notNow = "the application is stopping";
    } else {
      Effective e = effective();
      notNow = !e.isOn() || !e.settings.experimentId.equals(experimentId)
          ? "its settings changed" : whyNotOpen(e, onlyIfIdle, automatic);
    }
    if (notNow == null) {
      return true;
    }
    launchRequestedAt = 0L;   // nothing is under way any more
    Logger.info("Autopilot: not opening the session after all: " + notNow);
    return false;
  }

  /**
   * The engine actor has carried out the launch it was asked for. Until then launchUnderWay says so;
   * after it, the engine's session tells (judged by the session alone, a session that had since
   * ended looked like a launch still under way for half a minute).
   */
  static void launchCarriedOut() {
    launchRequestedAt = 0L;
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

  // ---- paused and done, kept over a restart --------------------------------------------------------

  /** Set why it opens nothing by itself (null: not paused, not done), and keep it in the database. */
  private static void setHalt(Long experimentId, String paused, String done) {
    pausedBecause = paused;
    doneBecause = done;
    if (paused != null || done != null) {
      // A next session counting down after a finished one doesn't open (its task then does nothing).
      nextOpenAt = 0L;
    }
    try {
      AutopilotSettings.saveHalt(paused == null && done == null ? null : experimentId, paused, done);
    } catch (Exception ex) {
      Logger.error("Autopilot: could not record whether it is paused", ex);
    }
  }

  /**
   * Take up a pause or done state kept from before a restart, when it is for the experiment the
   * autopilot runs now; a stale one (another experiment, or the autopilot is off) is forgotten.
   */
  static void restoreHalt() {
    try {
      AutopilotSettings.Halt halt = AutopilotSettings.loadHalt();
      if (halt == null) {
        return;
      }
      Effective e = effective();
      if (e.isOn() && e.settings.experimentId.equals(halt.experimentId)) {
        pausedBecause = halt.pausedBecause;
        doneBecause = halt.doneBecause;
        Logger.info("Autopilot: it was " + (pausedBecause != null ? "paused" : "done") + " before the restart, and stays so");
      } else {
        AutopilotSettings.saveHalt(null, null, null);
      }
    } catch (Exception ex) {
      Logger.error("Autopilot: could not read whether it was paused before the restart", ex);
    }
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

  /** e.g. "Autopilot · Oct 28, 13:16:05 UTC" (the seconds keep sessions opened a minute apart distinct). */
  private static String sessionName() {
    SimpleDateFormat format = new SimpleDateFormat("MMM d, HH:mm:ss", java.util.Locale.ENGLISH);
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    return "Autopilot · " + format.format(new Date()) + " UTC";
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
    status.put("done", e.isOn() && pausedBecause == null && doneBecause != null);
    long due = nextOpenAt;
    // Nothing is due while paused or done: it opens nothing by itself then.
    if (e.isOn() && pausedBecause == null && doneBecause == null && due > System.currentTimeMillis()) {
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
    Problem problem = problem(e, experiment, other);
    if (problem == null) {
      status.putNull("problem");
      status.putNull("problemKind");
    } else {
      status.put("problem", problem.text);
      status.put("problemKind", problem.kind);
    }
    return status;
  }

  /**
   * The engine's live session when it is not of this experiment, else null. Null too when the
   * autopilot has no experiment: then nothing waits for the engine, so no session is in its way.
   */
  private static ExperimentInstance otherLiveSession(Long experimentId) {
    if (experimentId == null) {
      return null;
    }
    ExperimentInstance live = liveEngineSession();
    if (live == null || live.isTestInstance()) {
      return null;
    }
    boolean ours = live.experiment != null && experimentId.equals(live.experiment.id);
    return ours ? null : live;
  }

  /**
   * Why the autopilot, switched on, keeps no session open ({@code status.problem}), and what kind of
   * reason that is ({@code status.problemKind}): "paused" or "done" (until Resume is pressed),
   * "waiting" (for the engine to be free) or "error" (something to fix).
   */
  static final class Problem {
    final String kind;
    final String text;

    Problem(String kind, String text) {
      this.kind = kind;
      this.text = text;
    }
  }

  /** Why the autopilot, switched on, can't keep a session open; null when nothing is wrong. */
  private static Problem problem(Effective e, Experiment experiment, ExperimentInstance otherSession) {
    if (!e.settings.enabled) {
      return null;
    }
    if (e.settings.experimentId == null) {
      return new Problem("error", CONFIG.equals(e.source)
          ? "The server's settings file (conf/application-prod.conf) names the experiment \"" + configuredExperimentName()
              + "\" (breadboard.autopilot.experiment), but there is no experiment with that name."
          : "No experiment is chosen.");
    }
    if (experiment == null) {
      return new Problem("error", "The experiment it was set to run (id " + e.settings.experimentId + ") no longer exists. Choose another one.");
    }
    if (operator(e.settings) == null) {
      return new Problem("error", "There is no admin account to open sessions as.");
    }
    // Resume (POST /autopilot/resume) works whoever is in charge; saving only when it is the console.
    boolean console = ADMIN.equals(e.source);
    if (pausedBecause != null) {
      return new Problem("paused", "It is paused: " + pausedBecause + ". It opens no new session until someone presses Resume"
          + (console ? " (or saves the settings)" : "") + " on the Autopilot page.");
    }
    if (doneBecause != null) {
      // The page's button for this state is "Open another session", whoever is in charge.
      return new Problem("done", doneBecause + ", so it opens no more until someone presses Open another session"
          + (console ? " (or saves the settings)" : "") + " on the Autopilot page.");
    }
    if (otherSession != null) {
      String owner = ScriptBoard.engineOwnerEmail();
      return new Problem("waiting", "Another session is running: \"" + otherSession.name + "\"" + (owner == null ? "" : " (" + owner + ")")
          + ". The autopilot opens its own session once that one has ended and the engine is free.");
    }
    String holder = ScriptBoard.researcherHolding(e.settings.experimentId);
    if (holder != null && liveEngineSession() == null) {
      Experiment loaded = ScriptBoard.engineExperiment();
      return new Problem("waiting", (loaded == null ? "Another experiment" : "\"" + loaded.name + "\"") + " is loaded in the engine for "
          + holder + ", who has a console open, so the autopilot leaves the engine to them. It opens its own session once they "
          + "close the console" + (console ? ", or when someone saves these settings (which loads it over theirs)." : "."));
    }
    return lastProblem == null ? null : new Problem("error", lastProblem);
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
