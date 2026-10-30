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

import java.io.File;
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
 * selecting another experiment, to edit it, leaves its session running), or when a step failure
 * stalls the game of its own session (one it opened, or the one of its experiment that was open
 * when Resume was pressed or its settings saved), which then ends (ScriptBoard.gameFailed; a
 * session a researcher runs by hand, even of its experiment, goes on). Its periodic check also guards
 * its own session (guardOwnSession): a game that runs longer than the longest session the settings
 * allow is ended, and one that has had nothing left to do for a minute after a step failed in it is
 * ended as a failure that stalls it is. It opens none while the disk
 * that holds the database is almost full (diskFull). The game starts when the waiting room says so
 * (groovy/autopilot.groovy; its values can be set in the console, see {@link AutopilotHooks}). A
 * researcher's console that has its experiment selected (selected then, or when signing in) shows
 * its session; one with another experiment selected keeps that selection (the engine info in the
 * User JSON names what the engine runs).
 *
 * <p>Participants enter through links that always lead to the currently open session:
 * {@code /play/{experimentId}/login} and {@code /play/{experimentId}/prolific?PROLIFIC_PID=...}.
 *
 * <p>The settings come from the console (GET/POST /autopilot, kept in the database as
 * {@link AutopilotSettings}). Until they are saved there, the server's settings file (conf/local.conf
 * in a release) can turn it on:
 * <pre>
 * breadboard.autopilot.experiment = "My experiment"   # experiment name; unset = autopilot off
 * breadboard.autopilot.user = "researcher@lab.org"    # admin account it runs as (default: first user)
 * breadboard.autopilot.continuous = true              # open a new session when one finishes
 * breadboard.autopilot.nextSessionDelaySeconds = 10
 * breadboard.autopilot.maxSessionMinutes = 120        # longest a session's game may run; 0 or unset = no limit
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
  // The session it opened last (launchCarriedOut), or the one of its experiment Resume or a save
  // handed it (adoptLiveSession), until it pauses or is done (setHalt): the one a failing step ends
  // (gameFailed). Any other session of its experiment, e.g. one a researcher launched by hand to take
  // over, or its own resumed by hand later, is the researcher's until they hand it over that way.
  private static volatile Long ownSession = null;

  // ---- the guards of its own session (see guardOwnSession) ----
  // After this many of its own sessions in a row were ended for running too long, it pauses instead
  // of opening the next: a design whose game never ends would use up participants. A game that comes
  // to its end starts the count again (sessionFinished), and so do Resume and a save.
  static final int TOO_LONG_IN_A_ROW = 3;
  private static volatile int tooLongInARow = 0;
  // The session the limit is ending: its finish is not a game that came to its end (sessionFinished).
  private static volatile Long endingTooLong = null;
  // The last session ended for running too long, in words, and when, for the console
  // (status.notice); kept until the settings are saved or Resume is pressed.
  private static volatile String notice = null;
  private static volatile long noticeAt = 0L;
  // How long its own session's game may have nothing to wait for, after a step failure was recorded
  // in it, before it is ended (watchForStall). A field so tests can shorten it.
  static volatile long stallMs = 60000L;
  // The session whose game the last check found with nothing to wait for, since when, and how many
  // steps had got going then (ScriptBoard.stepsGotGoing): a step that gets going in between counts as
  // the game moving on. quietSession is null when the last check found something to wait for.
  private static volatile Long quietSession = null;
  private static volatile long quietSince = 0L;
  private static volatile long quietSteps = 0L;
  // The time the guards go by; a field so tests can move it on.
  static volatile java.util.function.LongSupplier clock = new java.util.function.LongSupplier() {
    public long getAsLong() {
      return System.currentTimeMillis();
    }
  };
  // A guard ends a session on a thread of its own (endOwnSession), under the engine lock, which an
  // engine change can hold for long (a script that takes its time): the check waits for it this long
  // at most, and asks for no other end while it is under way.
  static volatile long guardWaitMs = 5000L;
  private static volatile Thread guardEnd = null;

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
    // (left out, or 0 or less: no limit. A study set up in the file before there was a limit goes on as
    // it was: its games may run longer than the 2 hours new settings start with.)
    int minutes = conf.getInt("breadboard.autopilot.maxSessionMinutes", 0);
    s.maxSessionMinutes = minutes <= 0 ? null : Math.min(minutes, AutopilotSettings.MAX_SESSION_MINUTES);
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
        guardsSeen();
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
    boolean played = Boolean.TRUE.equals(instance.hasStarted) || ScriptBoard.gameStartedAt(instance.id) > 0L;
    if (own && played && !instance.id.equals(endingTooLong)) {
      // A game of its experiment came to its end: the count of sessions in a row that ran too long
      // starts again. (Not a session called off before its game started.)
      tooLongInARow = 0;
    }
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
   * session of a non-continuous run. First it guards its own session (guardOwnSession).
   */
  static void watchdog() {
    if (stopped) {
      return;
    }
    guardOwnSession();
    if (nextOpenAt > System.currentTimeMillis()) {
      return;
    }
    openWhenDue("no session was open", true, true);
  }

  /**
   * The guards of its own session (isOwnSession: one it opened, or was handed on Resume or a save; a
   * session a researcher runs by hand is left to them), which nothing else ends when its game never
   * does (a script waiting for something that never comes): the engine can't tell such a game from a
   * long one. Its game may run maxSessionMinutes at most, counted from its start (endTooLong). And once
   * a step failure has been recorded in it, a game that has had nothing to wait for during stallMs is
   * ended as a failure that stalls it is (watchForStall): the step-failure rule (ScriptBoard.gameFailed)
   * can't see every way a failure leaves a game with nothing to do. Never waits long.
   */
  static void guardOwnSession() {
    Effective e;
    ExperimentInstance live;
    try {
      e = effective();
      live = e.isOn() ? liveEngineSession() : null;
    } catch (Exception ex) {
      Logger.error("Autopilot: could not check on its session", ex);
      return;
    }
    // Read outside the try: the Ebean enhancer breaks entity field reads inside one.
    Long id = live == null ? null : live.id;
    Long of = live == null || live.experiment == null ? null : live.experiment.id;
    if (id == null || !isOwnSession(id) || !e.settings.experimentId.equals(of)) {
      quietSession = null;
      return;
    }
    Thread ending = guardEnd;
    if (ending != null && ending.isAlive()) {
      // (an end asked for at an earlier check still waits for the engine lock)
      return;
    }
    long started = ScriptBoard.gameStartedAt(id);
    if (started <= 0L) {
      // its game hasn't started: the waiting room has limits of its own
      quietSession = null;
      return;
    }
    long now = clock.getAsLong();
    Integer limit = e.settings.maxSessionMinutes;
    if (limit != null && now - started > limit * 60000L) {
      endTooLong(live, e.settings, (now - started) / 60000L, limit);
      return;
    }
    watchForStall(live, of, now);
  }

  /**
   * The game of its own session has run longer than the longest session allowed: it is ended
   * (ScriptBoard.endForGuard). Its participants are told the session ran out of time (someone whose
   * screen shows their own end, e.g. a Prolific completion code, keeps it), the session is recorded as
   * finished, with a sessionTooLong event (minutes, limit), and the next one opens after the usual
   * pause (sessionFinished). The Autopilot page says so (status.notice). The third in a row pauses
   * it instead, rather than letting a game that never ends use up participants.
   */
  private static void endTooLong(ExperimentInstance live, final AutopilotSettings settings, final long ran, final int limit) {
    final Long id = live.id;
    final String name = live.name;
    final Long experimentId = settings.experimentId;
    LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
    data.put("minutes", ran);
    data.put("limit", limit);
    // (how many in a row this one makes, worked out under the engine lock as it ends)
    final int[] inARow = {0};
    java.util.concurrent.Callable<Boolean> stillWanted = new java.util.concurrent.Callable<Boolean>() {
      public Boolean call() {
        // (still its own, and still too long now that nothing can change the engine: the limit may
        // have been raised, or the game started again, since)
        Integer allowed = effective().settings.maxSessionMinutes;
        long started = ScriptBoard.gameStartedAt(id);
        if (stopped || !isOwnSession(id) || allowed == null || started <= 0L || clock.getAsLong() - started <= allowed * 60000L) {
          return false;
        }
        inARow[0] = tooLongInARow + 1;
        endingTooLong = id;
        if (inARow[0] >= TOO_LONG_IN_A_ROW) {
          // Before its finish, so no next session is scheduled (sessionFinished).
          setHalt(experimentId, inARow[0] + " sessions in a row ran longer than " + minutes(limit) + " (the longest a session may run) "
              + "and were ended, the last one \"" + name + "\". Check that its game comes to an end, or allow longer sessions "
              + "under Longest session", doneBecause);
        }
        return true;
      }
    };
    final Runnable after = new Runnable() {
      public void run() {
        noticeAt = System.currentTimeMillis();
        notice = "Session \"" + name + "\" was ended after " + minutes(ran)
            + (ran == limit ? ", the longest a session may run." : "; the longest a session may run is " + minutes(limit) + ".")
            + " Its participants were told that the session ran out of time.";
        tooLongInARow = inARow[0] >= TOO_LONG_IN_A_ROW ? 0 : inARow[0];
        Logger.warn("Autopilot: session " + name + " ran longer than " + minutes(limit) + " and was ended"
            + (inARow[0] >= TOO_LONG_IN_A_ROW ? "; " + inARow[0] + " in a row, so the autopilot paused" : ""));
      }
    };
    endOwnSession(id, "sessionTooLong", data, ScriptBoard.outOfTimeScreen(), true, stillWanted, after);
  }

  /** "1 minute", "90 minutes". */
  private static String minutes(long n) {
    return n + (n == 1 ? " minute" : " minutes");
  }

  /**
   * The stall backstop for its own session (see guardOwnSession). Once a step failure has been
   * recorded in its game (ScriptBoard.failureRecorded), the game counts as stalled when it has had
   * nothing to wait for (ScriptBoard.somethingPending: no choice offered to anyone, no task on the
   * engine's timers, such as a timed step waiting for its time or a computer player's pick, and no
   * input from a participant it listens for, such as a form) at every check since one that first found
   * it so, stallMs ago or more, and no step has got going since. It is then ended as a failure that
   * stalls a game is (endStalled). A game between two rounds' timers has a timer waiting, and a session
   * whose game recorded no failure is never ended this way.
   */
  private static void watchForStall(ExperimentInstance live, Long experimentId, long now) {
    Long id = live.id;
    ScriptBoard.RecordedFailure failure = ScriptBoard.failureRecorded(id);
    long steps = ScriptBoard.stepsGotGoing();
    if (failure == null || ScriptBoard.somethingPending()) {
      quietSession = null;
      return;
    }
    if (!id.equals(quietSession) || steps != quietSteps) {
      // nothing to wait for, as far as this check can tell: counted from here
      quietSession = id;
      quietSince = now;
      quietSteps = steps;
      return;
    }
    if (now - quietSince >= stallMs) {
      endStalled(live, experimentId, failure, (now - quietSince) / 1000L);
    }
  }

  /**
   * Its own session's game stalled after a step failed (watchForStall): it ends as a failure that
   * stalls a game ends it (ScriptBoard.gameFailed): stopped, its participants told it had to stop
   * because of a problem with the study (ScriptBoard.problemScreen; a completion code on screen stays),
   * a sessionStalled event (step, error), and the autopilot pauses, naming the step and what went wrong.
   */
  private static void endStalled(ExperimentInstance live, final Long experimentId, final ScriptBoard.RecordedFailure failure,
                                 final long quietSeconds) {
    final Long id = live.id;
    final String name = live.name;
    LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
    data.put("step", failure.step);
    data.put("error", failure.error);
    java.util.concurrent.Callable<Boolean> stillWanted = new java.util.concurrent.Callable<Boolean>() {
      public Boolean call() {
        // (as found, now that nothing can change the engine: something may have come up since)
        if (stopped || !isOwnSession(id) || ScriptBoard.failureRecorded(id) == null || ScriptBoard.somethingPending()) {
          return false;
        }
        setHalt(experimentId, "the step \"" + failure.step + "\" failed during its session \"" + name + "\" (" + failure.error
            + "), and then the game had nothing left to do, so that session was stopped. Fix the step on the Steps page first",
            doneBecause);
        return true;
      }
    };
    Runnable after = new Runnable() {
      public void run() {
        quietSession = null;
        Logger.warn("Autopilot: the game of session " + name + " had nothing left to do for " + quietSeconds
            + " s after the step " + failure.step + " failed (" + failure.error + "); the session was stopped and the autopilot paused");
      }
    };
    endOwnSession(id, "sessionStalled", data, ScriptBoard.problemScreen(), false, stillWanted, after);
  }

  /**
   * End its own session for a guard (ScriptBoard.endForGuard, under the engine lock), then run
   * {@code after} if it was ended. On a thread of its own: the engine lock can be held for long (an
   * engine change running a script that takes its time), and the periodic check waits guardWaitMs at
   * most. An end that goes on waiting still happens once the lock is free, if it is still wanted then
   * ({@code stillWanted}, asked under the lock); no other is asked for meanwhile.
   */
  private static void endOwnSession(final Long id, final String event, final LinkedHashMap<Object, Object> data,
                                    final ObjectNode screen, final boolean finished,
                                    final java.util.concurrent.Callable<Boolean> stillWanted, final Runnable after) {
    Thread thread = new Thread(new Runnable() {
      public void run() {
        try {
          if (ScriptBoard.endForGuard(id, event, data, screen, finished, stillWanted)) {
            after.run();
          }
        } catch (Throwable t) {
          Logger.error("Autopilot: could not end session " + id, t);
        } finally {
          endingTooLong = null;
        }
      }
    }, "breadboard-autopilot-guard");
    thread.setDaemon(true);
    guardEnd = thread;
    thread.start();
    try {
      thread.join(guardWaitMs);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    if (thread.isAlive()) {
      Logger.warn("Autopilot: ending session " + id + " waits for the script engine, which is busy; it ends once the engine is free");
    }
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
   * A step failure stalled the game of the engine's session: it kept the next step from getting
   * going (ScriptBoard.gameFailed, from stepFailed, gameStartFailed or joinOrLeaveStep). {@code error}
   * is "line N: what went wrong". When it is the autopilot's own session (isOwnSession, and it is
   * still on), the autopilot pauses until someone fixes the step and presses Resume, and true:
   * ScriptBoard ends the session, whose participants would otherwise wait for good. Any other
   * session, even of its experiment, is left to the researcher running it, and so is the reason it
   * is paused (false).
   */
  static boolean gameFailed(ExperimentInstance instance, String step, String error) {
    if (instance == null || instance.experiment == null) {
      return false;
    }
    // Read outside the try: the Ebean enhancer breaks entity field reads inside one.
    Long experimentId = instance.experiment.id;
    Long id = instance.id;
    String name = instance.name;
    if (!isOwnSession(id)) {
      return false;
    }
    Effective e;
    try {
      e = effective();
    } catch (Exception ex) {
      Logger.error("Autopilot: could not read its settings", ex);
      return false;
    }
    if (!e.isOn() || !e.settings.experimentId.equals(experimentId)) {
      return false;
    }
    setHalt(experimentId, "the step \"" + step + "\" failed during its session \"" + name + "\" (" + error
        + "), so that session was stopped. Fix the step on the Steps page first", doneBecause);
    Logger.warn("Autopilot: the step " + step + " failed during session " + name + " (" + error
        + "); the session was stopped and the autopilot paused");
    return true;
  }

  /**
   * Whether this is the session the autopilot opened (or was handed, see adoptLiveSession), and it has
   * been neither paused nor done since. A researcher's Launch while its session runs pauses it first,
   * so the session they take over with is not its own, nor is one they launch to test after a pause,
   * one after the single session of a run that isn't continuous, or its own session resumed by hand,
   * until Resume is pressed or the settings are saved while it is open.
   */
  static boolean isOwnSession(Long sessionId) {
    return sessionId != null && sessionId.equals(ownSession) && pausedBecause == null && doneBecause == null;
  }

  /** Run {@code task} soon on the autopilot's own thread; nothing once the application is stopping. */
  static void later(final Runnable task) {
    if (stopped) {
      return;
    }
    try {
      scheduler.execute(new Runnable() {
        public void run() {
          try {
            task.run();
          } catch (Throwable t) {
            Logger.error("Autopilot: a task failed", t);
          }
        }
      });
    } catch (java.util.concurrent.RejectedExecutionException stopping) {
      Logger.debug("Autopilot: the application is stopping; a task was not run");
    }
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
   * another researcher's live session. A live session of its experiment is its own from now on
   * (adoptLiveSession).
   */
  public static void settingsSaved(final AutopilotSettings saved, final boolean takeOver) {
    // Saving is the researcher's go-ahead again.
    setHalt(null, null, null);
    guardsSeen();
    adoptLiveSession();
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
   * (that one is left to end). A researcher who only has another experiment loaded gives it up. A
   * live session of its experiment is its own from now on (adoptLiveSession).
   */
  public static void resume(User by) {
    setHalt(null, null, null);
    guardsSeen();
    adoptLiveSession();
    final String why = "it was resumed" + (by == null ? "" : " by " + by.email);
    Logger.info("Autopilot: " + why);
    scheduler.execute(new Runnable() {
      public void run() {
        openWhenDue(why, true, false);
      }
    });
  }

  /**
   * A researcher saved the settings or pressed Resume, on the Autopilot page, which showed what the
   * guards did: the notice of a session ended for running too long goes, and the count of those in a
   * row starts again.
   */
  private static void guardsSeen() {
    notice = null;
    noticeAt = 0L;
    tooLongInARow = 0;
  }

  /**
   * Resume was pressed or the settings saved, and a session of its experiment is live (a researcher
   * launched or resumed it by hand, or the settings now name its experiment): the autopilot opens none
   * beside it ("open already"), and from now on keeps that one open as its own. Its links lead there,
   * its waiting room starts the game, and nobody need be watching, so it counts as a session it opened
   * (ownSession): a step that fails during its game ends it and pauses the autopilot, rather than
   * leaving its participants waiting for good. Only on Resume or a save, the researcher handing it
   * over: the periodic check leaves a session run by hand to whoever runs it.
   */
  private static void adoptLiveSession() {
    Effective e;
    ExperimentInstance live;
    try {
      e = effective();
      live = liveEngineSession();
    } catch (Exception ex) {
      Logger.error("Autopilot: could not tell which session is open", ex);
      return;
    }
    // Read outside the try: the Ebean enhancer breaks entity field reads inside one.
    if (!e.isOn() || live == null || live.isTestInstance() || live.experiment == null
        || !e.settings.experimentId.equals(live.experiment.id) || live.id.equals(ownSession)) {
      return;
    }
    ownSession = live.id;
    Logger.info("Autopilot: session " + live.name + " of its experiment is open; it keeps that one open as its own");
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
      String full = diskFull();
      if (full != null) {
        if (!full.equals(lastProblem)) {
          Logger.warn("Autopilot: " + full);
        }
        lastProblem = full;
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

  // ---- room on the disk ----------------------------------------------------------------------------

  // A session records its data as it goes, and with the disk full the database stops in the middle
  // of one. No new session opens while less than this is free where the database is kept.
  static final long MIN_FREE_BYTES = 100L * 1024 * 1024;

  // The bytes free on the disk that holds the database, or null when that can't be told (a database
  // in memory or on a database server). A field only so tests can pretend the disk is full.
  static volatile java.util.function.Supplier<Long> freeDiskBytes = new java.util.function.Supplier<Long>() {
    public Long get() {
      Configuration conf = Play.application().configuration();
      File folder = conf.getString("db.default.driver", "").contains("h2")
          ? databaseFolder(conf.getString("db.default.url", "")) : null;
      return folder == null ? null : folder.getUsableSpace();
    }
  };

  /** Why no session may open for lack of room on the database's disk, or null (also when it can't be told). */
  static String diskFull() {
    Long free;
    try {
      free = freeDiskBytes.get();
    } catch (Exception ex) {
      Logger.debug("Autopilot: could not tell how much room the disk has: " + ex);
      return null;
    }
    if (free == null || free >= MIN_FREE_BYTES) {
      return null;
    }
    return "The disk is almost full (" + free / (1024 * 1024) + " MB free where the database is kept): sessions record "
        + "their data there, so no new session opens until there is room. Free up some space on that disk.";
  }

  /**
   * The folder of the H2 database file a JDBC url names (jdbc:h2:db/breadboard: db/ in the folder the
   * server runs in, as H2 reads it), or the nearest one above it that exists; null for a database in
   * memory or on a server, and for anything but H2.
   */
  static File databaseFolder(String url) {
    if (url == null || !url.startsWith("jdbc:h2:")) {
      return null;
    }
    String path = url.substring("jdbc:h2:".length());
    int options = path.indexOf(';');
    if (options >= 0) {
      path = path.substring(0, options);
    }
    if (path.startsWith("file:")) {
      path = path.substring("file:".length());
    }
    // mem:, tcp://, ssl://, zip: and the like (one letter and a colon is a Windows drive)
    if (path.isEmpty() || path.matches("[A-Za-z][A-Za-z0-9]+:.*")) {
      return null;
    }
    if (path.startsWith("~")) {
      path = System.getProperty("user.home") + path.substring(1);
    }
    File folder = new File(path).getAbsoluteFile().getParentFile();
    while (folder != null && !folder.isDirectory()) {
      folder = folder.getParentFile();
    }
    return folder;
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
   * The engine actor has carried out the launch it was asked for, opening session {@code sessionId}.
   * Until then launchUnderWay says so; after it, the engine's session tells (judged by the session
   * alone, a session that had since ended looked like a launch still under way for half a minute).
   */
  static void launchCarriedOut(Long sessionId) {
    launchRequestedAt = 0L;
    ownSession = sessionId;
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
      // A next session counting down after a finished one doesn't open (its task then does nothing),
      // and the session it opened is no longer its own: whoever resumes it does so by hand.
      nextOpenAt = 0L;
      ownSession = null;
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
   * running and its game hasn't started, unless its waiting room is full and counting down (it seats
   * nobody more: ScriptBoard.waitingRoomFull); else null. (It used to be the newest running session in
   * the database, which could be one the engine no longer ran, e.g. from before a restart.)
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
    if (ScriptBoard.waitingRoomFull()) {
      return null;
    }
    return live;
  }

  /**
   * Whether the autopilot opens this experiment's next session by itself: it is on for it, opens the
   * next one when one finishes (continuous), and is neither paused nor done. What participants who
   * find no session open are told depends on it (ClientLogin.toOpenSession, ScriptBoard.sessionFullScreen).
   */
  public static boolean opensNextBySelf(Long experimentId) {
    if (experimentId == null) {
      return false;
    }
    try {
      Effective e = effective();
      return e.isOn() && experimentId.equals(e.settings.experimentId) && e.settings.continuous
          && pausedBecause == null && doneBecause == null;
    } catch (Exception ex) {
      Logger.debug("Autopilot: could not read its settings: " + ex);
      return false;
    }
  }

  /** Whether the engine runs a live session of this experiment now (its game, or its waiting room). */
  public static boolean sessionUnderWay(Long experimentId) {
    ExperimentInstance live = liveEngineSession();
    return experimentId != null && live != null && !live.isTestInstance() && live.experiment != null
        && experimentId.equals(live.experiment.id);
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
    // The last session it ended for running too long (endTooLong), until a save or Resume.
    String said = notice;
    long saidAt = noticeAt;
    if (said == null) {
      status.putNull("notice");
      status.putNull("noticeAt");
    } else {
      status.put("notice", said);
      status.put("noticeAt", Timestamps.iso(new Date(saidAt)));
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
          ? "The server's settings file (conf/local.conf) names the experiment \"" + configuredExperimentName()
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
