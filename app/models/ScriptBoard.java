package models;

import akka.actor.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Graph;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.gremlin.groovy.GremlinGroovyPipeline;
import groovy.util.ObservableMap;
import groovy.lang.Closure;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.groovy.control.CompilationFailedException;
import play.Logger;
import play.Play;
import play.libs.F.Callback;
import play.libs.F.Callback0;
import play.libs.Json;
import play.mvc.WebSocket;

import javax.script.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class ScriptBoard extends UntypedActor {
  private static ObjectMapper mapper = new ObjectMapper();

  private static ScriptEngineManager manager = new ScriptEngineManager();
  private static ScriptEngine engine;

  private static Map results = new HashMap();
  private static PlayerActionsInterface playerActions;
  private static BreadboardGraphInterface graphInterface;
  private static BreadboardGraphChangedListener graphChangedListener;
  private static FileWatcher fileWatcher;
  private static EventTracker eventTracker = new EventTracker();
  private static final EventBus<Map> eventBus = EventBus.forScripts();

  private static Random rand = new Random();
  private static final Map<String, Client> clients = new java.util.concurrent.ConcurrentHashMap<>();
  // Final screens of the participants of the last few sessions, by instance id and client id, for
  // pages that reconnect after their session has ended (see addClient).
  private static final int ENDED_SESSIONS_KEPT = 10;
  private static final Map<Long, Map<String, ObjectNode>> endedSessionScreens = Collections.synchronizedMap(
      new LinkedHashMap<Long, Map<String, ObjectNode>>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Map<String, ObjectNode>> eldest) {
          return size() > ENDED_SESSIONS_KEPT;
        }
      });

  // AMT lifecycle timers scheduled by the HitCreated handler. Tracked so they can be
  // cancelled on reload / on re-submit instead of being orphaned (MEMORY_LEAKS.md L6).
  // CopyOnWriteArrayList because the static state is shared across ScriptBoard actors.
  static final List<Timer> amtTimers = new CopyOnWriteArrayList<Timer>();

  // Every open admin console socket, one Admin each (registered on LogIn, removed when the socket
  // closes). Sockets open and close on Play's threads while the engine's threads send changes, hence
  // the copy-on-write list; ADMIN_LOCK keeps it and the graph listener's copy in step.
  private static final List<Admin> admins = new CopyOnWriteArrayList<>();
  private static final Object ADMIN_LOCK = new Object();
  // The open admin sockets as one output: engine reloads, OnJoinStep / OnLeaveStep output and live
  // events go to everyone watching.
  static final ThrottledWebSocketOut allAdmins = new ThrottledWebSocketOut(new AllAdminsOut(), 0L);
  // Runs OnJoinStep / OnLeaveStep once per player added or removed, however many consoles are open
  // (none, when the autopilot runs alone). It used to be each Admin's job, so it ran once per tab.
  private static final StepTrigger stepTrigger = new StepTrigger(new java.util.function.BiConsumer<Boolean, Vertex>() {
    public void accept(Boolean joined, Vertex player) {
      runJoinOrLeaveStep(joined, player);
    }
  });
  // Who built the engine that is running now: that actor runs OnJoinStep / OnLeaveStep (after the
  // steps it is still loading), and the experiment whose steps it loaded.
  private static volatile ActorRef engineOwner;
  private static volatile Long engineExperimentId;
  private static volatile GameListener engineGameListener;
  // The researcher whose request built the running engine (selecting an experiment or a session,
  // launching, reloading); null when the autopilot's launch built it, or LogIn loaded saved work
  // after a restart. The autopilot doesn't take the engine from them by itself (researcherHolding).
  private static volatile String engineBuiltFor;
  // Counts engine builds, so a message queued for one engine can tell it has been replaced.
  private static volatile long engineGeneration = 0L;
  // The engine whose game has started (its init step started, and didn't throw), for the console's
  // engine.gameStarted; null when none has. Tied to the engine rather than a flag that a rebuild
  // clears, so a start still running in an engine being replaced never counts for the new one.
  private static volatile ScriptEngine gameStartedIn = null;

  /**
   * The script or step a researcher's action runs on this thread (SendScript, SendStep, RunGame),
   * and the start it has marked itself, if any (see stepStarting).
   */
  private static final class ResearcherRun {
    ScriptEngine startPending;
  }
  private static final ThreadLocal<ResearcherRun> RESEARCHER_RUN = new ThreadLocal<ResearcherRun>();

  /**
   * A script processScript runs on this thread (the innermost, when one script runs another). A step
   * that throws in it is left to processScript, which tells whoever ran the script (see stepFailed);
   * the first one is kept, to name it, with what the script then threw.
   */
  private static final class ScriptRun {
    String step;
    Throwable stepFailure;
    Throwable thrown;
  }
  private static final ThreadLocal<ScriptRun> SCRIPT_RUN = new ThreadLocal<ScriptRun>();
  // The last script processScript ran on this thread, when it failed (else nothing): for
  // gameStartFailed, which the autopilot calls right after its start, on the same thread.
  private static final ThreadLocal<ScriptRun> FAILED_RUN = new ThreadLocal<ScriptRun>();
  // True while resetEngine empties the old graph: those removals are the old session ending, not
  // players leaving, so OnLeaveStep doesn't run for them against the new engine.
  private static volatile boolean resetting = false;
  private static UserDataInterface instanceData;
  private GameListener gameListener = new GameListener();

  private Long experimentId;
  private Long instanceId;

  public static boolean checkPassword(String _password) {
    // If no password is set, always return true
    Object password = currentPassword();
    if (password == null)
      return true;
    return password.toString().equals(_password);
  }

  /** Whether the experiment set a participant password (the login form only asks for one then). */
  public static boolean hasPassword() {
    return currentPassword() != null;
  }

  // The experiment's "password" binding, or null when there is none or it is blank. Read once from
  // a local copy of the engine, which a reload can replace at any moment.
  private static Object currentPassword() {
    ScriptEngine current = engine;
    if (current == null)
      return null;
    Object password = current.get("password");
    if (password == null || password.toString().trim().isEmpty())
      return null;
    return password;
  }

  public ScriptBoard() {
    // The engine is shared by every account. Creating an account's actor (its first login, or the
    // autopilot's) used to rebuild it, which ended the session another account was running. It is
    // built when someone selects an experiment or a session, launches, or reloads; Breadboard's
    // LogIn still loads the saved experiment for the first account in after a start.
  }

  /** Whether a script engine has been built since the server started. */
  public static boolean hasEngine() {
    return engine != null;
  }

  private void init() {
    results = new HashMap();
    mapper = new ObjectMapper();
    rememberLastScreens(clients.values());
    clients.clear();
    eventTracker = new EventTracker();
    manager = new ScriptEngineManager();
    eventBus.clear();
  }

  private void disconnectClients () {
    // Disconnect all connected clients
    for (Client client : clients.values()) {
      client.disconnect();
      // Also drop the client from the graph-change dispatch registry. Otherwise the
      // listener keeps a reference to every client that ever connected (a leak) and
      // keeps dispatching graph updates to its now-closed socket until a same-id
      // client happens to reconnect and overwrite the entry.
      if (graphChangedListener != null) {
        graphChangedListener.removeClientListener(client);
      }
    }
    rememberLastScreens(clients.values());
    clients.clear();
  }

  static void rememberLastScreens(Collection<Client> sessionClients) {
    for (Client client : sessionClients) {
      if (client.lastFrame == null || client.experimentInstance == null || client.experimentInstance.id == null) {
        continue;
      }
      Long instanceId = client.experimentInstance.id;
      synchronized (endedSessionScreens) {
        Map<String, ObjectNode> screens = endedSessionScreens.get(instanceId);
        if (screens == null) {
          screens = new java.util.concurrent.ConcurrentHashMap<>();
          endedSessionScreens.put(instanceId, screens);
        }
        screens.put(client.id, client.lastFrame);
      }
    }
  }

  /** What a page that reconnects to a session that has ended is sent: its last screen, if known. */
  static ObjectNode endedSessionScreen(Long instanceId, String clientId) {
    Map<String, ObjectNode> screens = endedSessionScreens.get(instanceId);
    ObjectNode last = screens == null ? null : screens.get(clientId);
    if (last != null) {
      return last;
    }
    ObjectNode frame = Json.newObject();
    ObjectNode player = frame.putObject("player");
    player.putObject("_system");
    player.put("finished", true);
    player.put("text", "<h3>This session has ended</h3><p>Thank you for taking part. You can close this page.</p>");
    return frame;
  }

  /**
   * The researcher stopped this session: its participants' pages say so (instead of a game that
   * no longer answers), and keep saying so if they reconnect. Their sockets are closed, so the
   * engine, which still holds the game, sends them nothing more.
   */
  static void endParticipants(Long instanceId) {
    if (instanceId == null) {
      return;
    }
    ObjectNode frame = Json.newObject();
    ObjectNode player = frame.putObject("player");
    player.putObject("_system");
    player.put("finished", true);
    player.put("ended", "stopped");
    player.put("text", "<h3>This session has been stopped</h3><p>The researcher stopped this session. "
        + "If they start it again, open the link you were given once more. Otherwise, thank you for "
        + "taking part: you can close this page.</p>");
    List<Client> ended = new ArrayList<>();
    for (Client client : clients.values()) {
      if (client.experimentInstance != null && instanceId.equals(client.experimentInstance.id)) {
        ended.add(client);
      }
    }
    for (Client client : ended) {
      client.lastFrame = frame;
      try {
        client.out.write(frame);
        client.disconnect();
      } catch (Exception e) {
        Logger.debug("Could not tell " + client.id + " that the session stopped: " + e);
      }
      clients.remove(client.id, client);
      if (graphChangedListener != null) {
        graphChangedListener.removeClientListener(client);
      }
    }
    rememberLastScreens(ended);
  }

  private static boolean hasPlayer(String clientId) {
    try {
      Object g = engine == null ? null : engine.get("g");
      return g instanceof Graph && clientId != null && ((Graph) g).getVertex(clientId) != null;
    } catch (Exception e) {
      return false;
    }
  }

  private static void cancelEngineTimers() {
    try {
      if (engine != null && engine.get("timers") != null) {
        engine.eval("timers.cancel(); null");
      }
    } catch (Exception e) {
      Logger.warn("Could not cancel the game's timers: " + e);
    }
  }

  /** What someone new is sent when the game started before they arrived. */
  static ObjectNode sessionStartedScreen() {
    ObjectNode frame = Json.newObject();
    ObjectNode player = frame.putObject("player");
    player.putObject("_system");
    player.put("finished", true);
    player.put("ended", "notRunning");
    player.put("text", "<h3>The session has already started</h3><p>It began before you arrived, so it can't "
        + "take anyone new. Please contact the researcher.</p>");
    return frame;
  }

  /** What a page of a session the engine doesn't run (now) is sent instead of joining a game. */
  static ObjectNode sessionNotRunningScreen() {
    ObjectNode frame = Json.newObject();
    ObjectNode player = frame.putObject("player");
    player.putObject("_system");
    player.put("finished", true);
    player.put("ended", "notRunning");
    player.put("text", "<h3>This session isn't running</h3><p>It may have ended, or it hasn't been opened yet. "
        + "If you were given a link to the study, open it again; otherwise you can close this page.</p>");
    return frame;
  }

  /**
   * Whether the engine runs this session of this experiment now, so newcomers may join it: its own
   * session while that is RUNNING or TESTING, or the experiment's test session while the engine has
   * that experiment loaded and runs no real session. {@code instance} is a freshly loaded copy (its
   * status is current).
   */
  public static boolean runsSession(Long experimentId, ExperimentInstance instance) {
    if (experimentId == null || instance == null || instance.id == null) {
      return false;
    }
    if (instance.isTestInstance()) {
      // Never while a real session runs in the engine: a test page would join that session's game
      // (even after it started, the test session never does).
      ExperimentInstance live = Autopilot.liveEngineSession();
      return engine != null && experimentId.equals(engineExperimentId) && (live == null || live.isTestInstance());
    }
    ExperimentInstance session = engineSession();
    if (session == null || !instance.id.equals(session.id)) {
      return false;
    }
    if (instance.status != ExperimentInstance.Status.RUNNING && instance.status != ExperimentInstance.Status.TESTING) {
      return false;
    }
    return instance.experiment == null || experimentId.equals(instance.experiment.id);
  }

  /** Whether this is the id of the session the engine was last given (whatever its state). */
  static boolean isEngineSession(Long instanceId) {
    ExperimentInstance session = engineSession();
    return instanceId != null && session != null && instanceId.equals(session.id);
  }

  /** {@link #runsSession(Long, ExperimentInstance)} for the ids in a participant URL; false for ids that aren't numbers. */
  public static boolean runsSession(String experimentId, String instanceId) {
    try {
      Long experiment = Long.valueOf(experimentId);
      Long id = Long.valueOf(instanceId);
      ExperimentInstance instance;
      if (Experiment.TEST_INSTANCE_ID.equals(id)) {
        Experiment found = Experiment.findById(experiment);
        instance = found == null ? null : found.getTestInstance();
      } else {
        instance = ExperimentInstance.findById(id);
      }
      return runsSession(experiment, instance);
    } catch (NumberFormatException notAnId) {
      return false;
    }
  }

  /** Cancel and forget every tracked AMT lifecycle timer. Safe to call when none exist. */
  static void cancelAmtTimers() {
    for (Timer t : amtTimers) {
      t.cancel();
    }
    amtTimers.clear();
  }

  /**
   * Schedule the two AMT lifecycle timers, first cancelling any still pending from an
   * earlier HIT submission so they are never orphaned. Extracted from the HitCreated
   * handler so the scheduling/cancellation contract is unit-testable without the actor
   * or a live script engine (MEMORY_LEAKS.md L6).
   *
   * @param noNewConnectionsDelayMs delay after which no new client connections are allowed
   * @param startInitStepDelayMs    delay after which {@code initStep.start()} is sent
   * @param onNoNewConnections      action run by the first timer
   * @param onStartInitStep         action run by the second timer
   */
  static void scheduleAmtTimers(long noNewConnectionsDelayMs, long startInitStepDelayMs,
                                final Runnable onNoNewConnections, final Runnable onStartInitStep) {
    // Replace any timers still pending from an earlier submission.
    cancelAmtTimers();

    Timer noNewConnectionsTimer = new Timer();
    noNewConnectionsTimer.schedule(new TimerTask() {
      @Override
      public void run() {
        onNoNewConnections.run();
      }
    }, noNewConnectionsDelayMs);
    amtTimers.add(noNewConnectionsTimer);

    Timer startInitStepTimer = new Timer();
    startInitStepTimer.schedule(new TimerTask() {
      @Override
      public void run() {
        onStartInitStep.run();
      }
    }, startInitStepDelayMs);
    amtTimers.add(startInitStepTimer);
  }

  // One engine change at a time. Every account's actor (and the autopilot's) rebuilds the one shared
  // engine, and a rebuild that started while another was half-way through found the new engine
  // without its scripts (playerActions not set yet) and threw. Held for the whole change, not only
  // the rebuild: reading the session the engine runs, ending it, rebuilding, loading the steps and
  // starting the new session (ChangeExperiment, ReloadEngine, launchGame, selectInstance). Another
  // actor's change landing in between used to stop nothing (its "previous" was stale), leave a
  // session RUNNING with no engine, or run one experiment's steps in another's engine. Reentrant.
  private static final Object REBUILD_LOCK = new Object();

  /**
   * Rebuild the engine for {@code experiment}. {@code builtFor} is the researcher who asked for it,
   * or null for the autopilot's launch and for LogIn's loading of saved work after a restart.
   */
  private void resetEngine(Experiment experiment, String builtFor) throws IOException, ScriptException {
    try {
      synchronized (REBUILD_LOCK) {
        // First: whatever was queued for the old engine is stale even if this build fails.
        engineGeneration++;
        gameStartedIn = null;
        engineBuiltFor = builtFor;
        buildEngine(experiment);
      }
    } catch (Throwable failed) {
      // Every console saw "Reloading", so every console hears that it failed, not only the one
      // that asked (which also gets the error itself).
      toAllAdmins(reloadFailed(failed));
      throw failed;
    }
  }

  /** {@code {"notify": {"ScriptEngineReloadFailed": "<why, in words>"}}}. */
  static ObjectNode reloadFailed(Throwable failure) {
    ObjectNode failed = Json.newObject();
    failed.putObject("notify").put("ScriptEngineReloadFailed",
        "The script engine could not be rebuilt: " + ScriptBoardSupport.describeError(failure));
    return failed;
  }

  private void buildEngine(Experiment experiment) throws IOException, ScriptException {
    // When started, tell every open console
    ObjectNode reloading = Json.newObject();
    reloading.putObject("notify").put("ScriptEngineReloading", new Date().getTime());
    toAllAdmins(reloading);
    Logger.debug("ScriptEngine reload start");
    ClientView.reset();
    Recruitment.reset();
    if (engine != null) {
      resetting = true;
      try {
        // just in case
        playerActions.turnAIOff();
        // clean up the graph
        processScript("g.empty()", null, null);
        // Reset the timers
        processScript("timers.cancel()", null, null);
      } finally {
        resetting = false;
      }
      // Cancel any pending AMT lifecycle timers from a previous session so they don't
      // fire against the rebuilt engine or outlive it (MEMORY_LEAKS.md L6).
      cancelAmtTimers();
    }

    // Global events used to communicate via the groovy scripting
    eventBus.clear();
    eventBus.on("__send-event", new Closure(null) {
      public void doCall (String clientId, String eventName, Object ...data) {
        Logger.debug("client send " + clientId);
        Client client = clients.get(clientId);
        if (client == null) {
          Logger.error("Client with id, " + clientId + " has not connected yet");
          return;
        }
        client.send(eventName, data);
      }
    });
    eventBus.on("__broadcast-event", new Closure(null) {
      public void doCall (String clientId, String eventName, Object ...data) {
        Logger.debug("client broadcast " + clientId);
        for (Client client : clients.values()) {
          client.send(eventName, data);
        }
      }
    });

    engine = manager.getEngineByName("gremlin-groovy");
    engine.getBindings(ScriptContext.ENGINE_SCOPE).put("r", rand);
    engine.getBindings(ScriptContext.ENGINE_SCOPE).put("results", results);

    engine.getBindings(ScriptContext.ENGINE_SCOPE).put("eventTracker", eventTracker);
    engine.getBindings(ScriptContext.ENGINE_SCOPE).put("gameListener", gameListener);
    engine.getBindings(ScriptContext.ENGINE_SCOPE).put("events", eventBus);

    if (experiment != null) {
      engine.getBindings(ScriptContext.ENGINE_SCOPE).put("c", experiment.contentFetcher);
    }

    // Load the Groovy DSL scripts. ScriptLoader is the single source of truth for which
    // files load and in what order: the core scripts first, in dependency order, then every
    // other *.groovy file alphabetically, skipping *_test.groovy. Dropping a new script into
    // the groovy directory is enough to have it loaded -- no edit here required. A failure in
    // a non-core script is logged (naming the file) and skipped rather than aborting the boot.
    // Experimental scripts (ScriptLoader.EXPERIMENTAL_SCRIPTS) load only when the
    // breadboard.experimental flag is on, defaulting to off.
    boolean experimental = Play.application().configuration().getBoolean("breadboard.experimental", false);
    File groovyDir = new File(Play.application().path().toString(), "groovy");
    ScriptLoader.loadAll(engine, groovyDir, experimental);

    // get script object on which we want to implement the interface with
    Object a = engine.get("a");
    Invocable inv = (Invocable) engine;
    playerActions = inv.getInterface(a, PlayerActionsInterface.class);

    Object d = engine.get("d");
    instanceData = inv.getInterface(d, UserDataInterface.class);

    Object g = engine.get("g");
    graphInterface = inv.getInterface(g, BreadboardGraphInterface.class);

    if (graphChangedListener == null) {
      Logger.debug("graphChangedListener == null");
      Long clientUpdateRate = play.Play.application().configuration().getMilliseconds("breadboard.clientUpdateRate");
      BreadboardGraphChangedListener listener;
      if (clientUpdateRate == null || clientUpdateRate == 0) {
        Logger.debug("clientUpdateRate not found or 0, using event based updating");
        listener = new EventGraphChangedListener((Graph) g);

      } else {
        Logger.debug("clientUpdateRate found, using polling");
        listener = new IteratedBreadboardGraphChangedListener((Graph) g);
      }
      synchronized (ADMIN_LOCK) {
        // Consoles may have logged in before there was an engine.
        listener.addAdminListener(stepTrigger);
        for (Admin admin : admins) {
          listener.addAdminListener(admin);
        }
        graphChangedListener = listener;
      }
    } else {
      Logger.debug("graphChangedListener != null");
      graphChangedListener.setGraph((Graph) g);
    }

    graphInterface.addListener(graphChangedListener);

    // Watches dev/ for file-mode experiments. Not in tests, where it would create dev/ in the checkout.
    if (fileWatcher == null && !Play.isTest()) {
      fileWatcher = new FileWatcher(admins);
    }

    gameListener.engine = engine;
    engineOwner = getSelf();
    engineExperimentId = experiment == null ? null : experiment.id;
    engineGameListener = gameListener;

    // When done, tell every open console
    ObjectNode reloaded = Json.newObject();
    reloaded.putObject("notify").put("ScriptEngineReloaded", new Date().getTime());
    toAllAdmins(reloaded);
    this.disconnectClients();
    Logger.debug("ScriptEngine reload complete");
  }

  // The "source" of the frames an experiment's steps send while they load (on a rebuild or a launch):
  // no console request is waiting for them.
  static final String STEPS_SOURCE = "Steps";

  /**
   * Load the experiment's steps into the engine. Returns "<step>: <what went wrong>" for the first
   * step that didn't load, or null when they all did.
   */
  private String loadSteps(Experiment experiment, ThrottledWebSocketOut out) {
    if (experiment == null) {
      return null;
    }
    String failed = null;
    for (Step step : experiment.getSteps()) {
      String problem = processScript(step.source + ";null;", out, step.name, "source", STEPS_SOURCE, null, true);
      if (problem != null && failed == null) {
        failed = step.name + ": " + problem;
      }
    }
    afterStepsLoaded(experiment);
    if (failed == null) {
      Autopilot.stepsLoaded(experiment);
    }
    return failed;
  }

  /**
   * Run {@link AutopilotHooks#afterStepsLoaded} on the current engine, logging (never throwing) if it
   * fails. Called after every load of an experiment's steps into the engine.
   */
  static void afterStepsLoaded(Experiment experiment) {
    ScriptEngine current = engine;
    if (current == null) {
      return;
    }
    try {
      afterStepsLoadedHook.accept(experiment, current);
    } catch (Exception | AssertionError e) {
      ScriptLoader.humanizeStackTrace(e);
      Logger.error("Could not apply the autopilot settings after loading the steps of "
          + (experiment == null ? "the engine" : "\"" + experiment.name + "\""), e);
    }
  }

  // AutopilotHooks.afterStepsLoaded; a field only so tests can watch when it runs.
  static volatile java.util.function.BiConsumer<Experiment, ScriptEngine> afterStepsLoadedHook =
      new java.util.function.BiConsumer<Experiment, ScriptEngine>() {
        public void accept(Experiment experiment, ScriptEngine engine) {
          AutopilotHooks.afterStepsLoaded(experiment, engine);
        }
      };

  /** The experiment whose steps the running engine loaded, or null. */
  static Experiment engineExperiment() {
    Long id = engineExperimentId;
    return id == null ? null : Experiment.findById(id);
  }

  /** The session the running engine was last given (as the engine's copy; it may have ended), or null. */
  static ExperimentInstance engineSession() {
    GameListener listener = engineGameListener;
    return listener == null ? null : listener.experimentInstance;
  }

  private String makeUniqueClientId (String clientId) {
    return ScriptBoardSupport.makeUniqueClientId(this.experimentId, this.instanceId, clientId);
  }

  private void rebuildScriptBoard(Experiment experiment, String builtFor) throws IOException, ScriptException {
    //init();
    resetEngine(experiment, builtFor);
  }

  /** Who a researcher's request builds the engine for: them, except when LogIn loads saved work. */
  private static String builtFor(Breadboard.BreadboardMessage request) {
    return request.atSignIn || request.user == null ? null : request.user.email;
  }

  /**
   * The researcher who has the engine loaded with an experiment other than {@code experimentId} (by
   * selecting it, a session of it, launching or reloading) and still has a console open; else null.
   * The autopilot doesn't take the engine from them by itself: that would rebuild it under their
   * hands, flip their consoles and refuse their actions. A session running in it is another matter
   * (the autopilot leaves a live session alone anyway).
   */
  static String researcherHolding(Long experimentId) {
    String email = engineBuiltFor;
    if (engine == null || email == null || (experimentId != null && experimentId.equals(engineExperimentId))) {
      return null;
    }
    for (Admin admin : admins) {
      User user = admin.getUser();
      if (user != null && user.email != null && user.email.equalsIgnoreCase(email)) {
        return email;
      }
    }
    return null;
  }

  /**
   * A researcher's action is about to end the session a game listener runs (selecting another
   * experiment or session, launching, reloading). When that is the autopilot's live session, the
   * autopilot pauses first, as for Stop, so it doesn't take the engine back behind their back (and
   * its periodic check can't slip in between). {@code continuing} is a session that carries on
   * (re-selected), {@code how} says what the researcher did, with %s for who.
   */
  private static void pauseAutopilotIfItsSession(GameListener listener, User by, String how, Long continuing) {
    Long id = instanceIdOf(listener);
    if (id == null || id.equals(continuing) || Experiment.TEST_INSTANCE_ID.equals(id)) {
      return;
    }
    ExperimentInstance current = ExperimentInstance.findById(id);
    if (current != null && current.status == ExperimentInstance.Status.RUNNING) {
      Autopilot.sessionStopped(current, by, how);
    }
  }

  /** Start sending graph changes to this console socket. */
  public static void addAdmin(Admin admin) {
    synchronized (ADMIN_LOCK) {
      if (!admins.contains(admin)) {
        admins.add(admin);
      }
      if (graphChangedListener != null) {
        graphChangedListener.addAdminListener(admin);
      }
    }
  }

  /**
   * The Admin for this socket, registered if it is new. A socket logs in again after every reconnect
   * of the console (and may send LogIn twice); it keeps one Admin, with the account refreshed.
   */
  static Admin registerAdmin(User user, ActorRef scriptBoardController, ThrottledWebSocketOut out) {
    synchronized (ADMIN_LOCK) {
      for (Admin admin : admins) {
        if (admin.getOut() == out) {
          admin.setUser(user);
          return admin;
        }
      }
      Admin admin = new Admin(user, scriptBoardController, out);
      // The socket closed before this ran (e.g. a tab reloaded while the engine was busy): its
      // removeAdmin has come and gone, so registering it now would keep a dead Admin for good.
      // (removeAdmin takes the same lock, after the socket is marked closed.)
      if (!out.isClosed()) {
        addAdmin(admin);
      }
      return admin;
    }
  }

  /** A console socket closed: stop sending it anything. */
  public static void removeAdmin(ThrottledWebSocketOut out) {
    synchronized (ADMIN_LOCK) {
      for (Admin admin : admins) {
        if (admin.getOut() == out) {
          admins.remove(admin);
          if (graphChangedListener != null) {
            graphChangedListener.removeAdminListener(admin);
          }
        }
      }
    }
  }

  /**
   * The account signed out or changed its password, which replaced its token (uid). Its console
   * sockets that logged in with an older token are closed, so they stop receiving the live network,
   * participants' data, events and output (their frames were refused already, but pushes carried
   * on). A socket that logged in with {@code currentUid} is kept, as is {@code except} (the one that
   * asked). Each is told {"error", "status": "unauthorized"} first, so a console signs in again with
   * the current token, or shows the sign-in page.
   */
  public static void closeAdminsOf(String email, String currentUid, ThrottledWebSocketOut except) {
    closeAdminsOf(email, currentUid, except,
        "You are not signed in any more: this account signed out or its password was changed. Sign in again.");
  }

  /** closeAdminsOf, telling the closed consoles {@code why}. */
  public static void closeAdminsOf(String email, String currentUid, ThrottledWebSocketOut except, String why) {
    if (email == null) {
      return;
    }
    List<Admin> closing = new ArrayList<Admin>();
    synchronized (ADMIN_LOCK) {
      for (Admin admin : admins) {
        User user = admin.getUser();
        if (admin.getOut() == except || user == null || user.email == null || !user.email.equalsIgnoreCase(email)
            || (currentUid != null && currentUid.equals(user.uid))) {
          continue;
        }
        admins.remove(admin);
        if (graphChangedListener != null) {
          graphChangedListener.removeAdminListener(admin);
        }
        closing.add(admin);
      }
    }
    ObjectNode signedOut = Json.newObject();
    signedOut.put("error", why);
    signedOut.put("status", "unauthorized");
    for (Admin admin : closing) {
      ThrottledWebSocketOut out = admin.getOut();
      try {
        // Marked first, so a LogIn of this socket still queued registers nothing.
        out.markClosed();
        out.write(signedOut);
        out.close();
      } catch (Exception e) {
        Logger.debug("Could not close a signed-out console socket: " + e);
      }
    }
  }

  /** How many console sockets are open. */
  public static int adminCount() {
    return admins.size();
  }

  static boolean hasAdmins() {
    return !admins.isEmpty();
  }

  /**
   * Tell every console that the server's experiments changed (one was made, imported, renamed or
   * deleted, maybe by another researcher): {"experimentsChanged": true}, and they ask for the
   * newest list.
   */
  public static void experimentsChanged() {
    ObjectNode frame = Json.newObject();
    frame.put("experimentsChanged", true);
    toAllAdmins(frame);
  }

  /** Write a frame to every open console socket. */
  static void toAllAdmins(JsonNode frame) {
    for (Admin admin : admins) {
      admin.write(frame);
    }
  }

  /** Write a frame to every open console socket of one account; false if it has none open. */
  static boolean toAccount(String email, JsonNode frame) {
    boolean any = false;
    for (Admin admin : admins) {
      User user = admin.getUser();
      if (user != null && user.email != null && user.email.equalsIgnoreCase(email)) {
        admin.write(frame);
        any = true;
      }
    }
    return any;
  }

  /**
   * Write a frame to the account's console sockets other than {@code except}, which was answered
   * already. Nothing when {@code except} is the autopilot's output: that reaches them all itself.
   */
  static void toOtherTabs(String email, JsonNode frame, ThrottledWebSocketOut except) {
    if (email == null || Autopilot.isOutput(except)) {
      return;
    }
    for (Admin admin : admins) {
      User user = admin.getUser();
      if (admin.getOut() != except && user != null && user.email != null && user.email.equalsIgnoreCase(email)) {
        admin.write(frame);
      }
    }
  }

  /**
   * Send this socket the live network: {@code {"graphReplay": {"nodes": n, "links": m}}}, then
   * {@code addNode} + {@code nodePropertyChanged} per property for every player and {@code addLink} +
   * {@code linkPropertyChanged} for every tie. Only to this socket: a console that connects or
   * reconnects no longer makes every other console receive the graph again.
   */
  static void replayGraph(Admin admin) {
    ScriptEngine current = engine;
    if (current == null) {
      return;
    }
    try {
      Object g = current.get("g");
      if (!(g instanceof Graph)) {
        return;
      }
      Graph wholeGraph = (Graph) g;
      List<Vertex> vertices = new ArrayList<Vertex>();
      for (Vertex v : wholeGraph.getVertices()) {
        vertices.add(v);
      }
      List<Edge> edges = new ArrayList<Edge>();
      for (Edge e : wholeGraph.getEdges()) {
        edges.add(e);
      }
      // Lets the console drop what it had (e.g. players that left while it was disconnected).
      ObjectNode start = Json.newObject();
      ObjectNode counts = start.putObject("graphReplay");
      counts.put("nodes", vertices.size());
      counts.put("links", edges.size());
      admin.write(start);
      for (Vertex v : vertices) {
        admin.replayVertex(v);
      }
      for (Edge e : edges) {
        admin.edgeAdded(e);
      }
    } catch (Exception e) {
      // The engine may be rebuilding right now; its ScriptEngineReloaded and the new graph follow.
      Logger.warn("Could not send the network to a console: " + e);
    }
  }

  /** Every open console socket as one WebSocket.Out (see allAdmins). */
  private static final class AllAdminsOut implements WebSocket.Out<JsonNode> {
    public void write(JsonNode frame) {
      toAllAdmins(frame);
    }

    public void close() {}
  }

  /**
   * Graph listener that runs OnJoinStep when a player is added and OnLeaveStep when one is removed,
   * once each. Registered once on the engine's graph listener, alongside the console sockets.
   */
  static final class StepTrigger implements ClientListener {
    private final java.util.function.BiConsumer<Boolean, Vertex> run;

    StepTrigger(java.util.function.BiConsumer<Boolean, Vertex> run) {
      this.run = run;
    }

    public void vertexAdded(Vertex vertex) {
      fire(true, vertex);
    }

    public void vertexRemoved(Vertex vertex) {
      fire(false, vertex);
    }

    private void fire(boolean joined, Vertex vertex) {
      try {
        run.accept(joined, vertex);
      } catch (Exception e) {
        Logger.error("Could not run the " + (joined ? "OnJoinStep" : "OnLeaveStep") + " for " + vertex.getId(), e);
      }
    }

    public void graphChanged(Graph wholeGraph) {}
    public void vertexPropertyChanged(Vertex vertex, String key, Object oldValue, Object setValue) {}
    public void vertexPropertyRemoved(Vertex vertex, String key) {}
    public void edgeAdded(Edge edge) {}
    public void edgeRemoved(Edge edge) {}
    public void edgePropertyChanged(Edge edge, String key, Object setValue) {}
    public void edgePropertyRemoved(Edge edge, String key) {}
  }

  /**
   * Queue OnJoinStep / OnLeaveStep for a player on the actor that built the engine, so it runs after
   * any steps that actor is still loading, with its output going to every open console.
   */
  private static void runJoinOrLeaveStep(boolean joined, Vertex player) {
    ActorRef owner = engineOwner;
    if (owner == null || resetting) {
      return;
    }
    Breadboard.BreadboardMessage message = joined
        ? new Breadboard.RunOnJoinStep(null, player, allAdmins)
        : new Breadboard.RunOnLeaveStep(null, player, allAdmins);
    owner.tell(message, null);
  }

  /** A Groovy single-quoted string literal (no ${} interpolation) holding {@code value}. */
  static String groovyString(String value) {
    StringBuilder sb = new StringBuilder("'");
    for (char c : value.toCharArray()) {
      switch (c) {
        case '\\': sb.append("\\\\"); break;
        case '\'': sb.append("\\'"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        default: sb.append(c);
      }
    }
    return sb.append("'").toString();
  }

  /** Whether this participant's seat was taken in this session (of this experiment). */
  private static boolean sameSession(Client client, Long experimentId, ExperimentInstance instance) {
    ExperimentInstance seat = client.experimentInstance;
    if (seat == null || seat.id == null || !seat.id.equals(instance.id)) {
      return false;
    }
    return seat.experiment == null || seat.experiment.id == null || seat.experiment.id.equals(experimentId);
  }

  public static void addClient(String experimentIdString, String experimentInstanceIdString, String clientId, final WebSocket.In<JsonNode> in, final ThrottledWebSocketOut out) throws Exception {
    addClient(experimentIdString, experimentInstanceIdString, clientId, in, out, false);
  }

  /** @param byResearcher a researcher's preview of this player (its sign-in is recorded as such) */
  public static void addClient(String experimentIdString, String experimentInstanceIdString, String clientId, final WebSocket.In<JsonNode> in, final ThrottledWebSocketOut out, final boolean byResearcher) throws Exception {
    try {
      Long experimentId = Long.parseLong(experimentIdString);
      Long experimentInstanceId = Long.parseLong(experimentInstanceIdString);

      Experiment experiment = Experiment.findById(experimentId);
      if (experiment == null) {
        Logger.debug("addClient: experiment == null");
        return;
      }

      ExperimentInstance experimentInstance = null;
      if (experimentInstanceId.equals(Experiment.TEST_INSTANCE_ID)) {
        experimentInstance = experiment.getTestInstance();
      } else {
        experimentInstance = ExperimentInstance.findById(experimentInstanceId);
      }
      if (experimentInstance == null) {
        Logger.debug("addClient: experimentInstance == null");
        return;
      }

      Client client = clients.get(clientId);

      if (client != null && !sameSession(client, experimentId, experimentInstance)) {
        // The seat of that id in the game the engine runs belongs to another session's participant:
        // the token was for this (older, test or other) session, not for that seat. Taking it over
        // used to hand someone else's screen and choices to whoever held such a token. Not a newcomer
        // either: the id is taken in the game the engine runs.
        Logger.info("addClient: " + clientId + " of instance " + experimentInstanceId + " refused: that id is a participant of "
            + (client.experimentInstance == null ? "another session" : "instance " + client.experimentInstance.id));
        // (a stopped session counts as over here: its participants keep the "stopped" screen)
        boolean over = experimentInstance.status != null
            && (!experimentInstance.status.isRunnable() || experimentInstance.status == ExperimentInstance.Status.STOPPED);
        out.write(over ? endedSessionScreen(experimentInstance.id, clientId) : sessionNotRunningScreen());
        return;
      }

      if (client == null && experimentInstance.status != null
          && (!experimentInstance.status.isRunnable() || experimentInstance.status == ExperimentInstance.Status.STOPPED)) {
        // The session is over (finished, or given up by the autopilot), and the engine may already
        // be running the next one, which this participant must not be pulled into. Give the page
        // back its last screen (a completion code, a "could not start" notice) instead.
        Logger.debug("addClient: instance " + experimentInstanceId + " is no longer running");
        out.write(endedSessionScreen(experimentInstance.id, clientId));
        return;
      }

      if (client == null && !runsSession(experimentId, experimentInstance)) {
        // Not the session the engine runs (it moved on to another experiment or session, or nothing
        // has taken this one up since a restart). Its pages reconnect, and used to be put into
        // whatever game the engine was running.
        Logger.debug("addClient: instance " + experimentInstanceId + " is not the session the engine runs");
        out.write(sessionNotRunningScreen());
        return;
      }

      if (client == null && experimentInstance.hasStarted && !hasPlayer(clientId)) {
        // Someone new after the start (sign-in already turns them away, so e.g. an old page): say
        // so instead of leaving the page connecting for ever. Someone whose player is in the game
        // (a participant whose page is gone, a researcher's preview of a computer player) goes on.
        Logger.debug("New client trying to join after game has already started.");
        out.write(sessionStartedScreen());
        return;
      }

      final Client thisClient;
      if (client == null) {
        // New client, let's create a new Client object
        client = new Client(clientId, experimentInstance, in, out);
        clients.put(clientId, client);
      } else {
        // Reconnecting (the controller already checked this request holds the seat's token):
        // switch to the new socket and close the previous one, so an older tab can neither keep
        // receiving updates nor keep sending choices for this participant.
        ThrottledWebSocketOut previous = client.out;
        client.setIn(in);
        client.setOut(out);
        if (previous != null && previous != out) {
          try {
            previous.close();
          } catch (Exception ignored) {}
        }
      }
      thisClient = client;

      in.onClose(new Callback0() {
        public void invoke() {
          // Only the current socket counts; a replaced one closing is not a disconnect.
          if (thisClient.in == in) {
            setConnected(clientId, false);
          }
        }
      });

      in.onMessage(new Callback<JsonNode>() {
        public void invoke(JsonNode event) {
          if (thisClient.in != in) {
            return; // message from a socket that has since been replaced
          }
          try {
            ObjectMapper mapper = new ObjectMapper();
            // TODO: is event.toString() the correct method here?
            Map<String, Object> jsonInput = mapper.readValue(event.toString(), Map.class);
            Object actionValue = jsonInput.get("action");
            String action = actionValue == null ? "" : actionValue.toString();
            if (action.equals("LogIn")) {
              // The browser used to report its own IP address, user agent, referer (full URL,
              // including recruitment ids) and other headers here, which were stored with every
              // login. They were unverified and mostly personal data, so only the id is kept.
              if (eventTracker != null) {
                LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
                data.put("clientId", clientId);
                if (byResearcher) {
                  data.put("preview", "true");
                }
                eventTracker.track("clientLogIn", data);
              }
            } else if (action.equals("MakeChoice")) {
              Object uidValue = jsonInput.get("choiceUID");
              String choiceUID = uidValue == null ? null : uidValue.toString();
              String params = (jsonInput.containsKey("params") && jsonInput.get("params") != null) ? jsonInput.get("params").toString() : null;
              makeChoice(clientId, choiceUID, params, out);
            } else if (action.equals("CustomEvent")) {
              // TODO: Is is possible for this to be emitted before the event has been registered? 
              //       I think it is and I think it will cause exceptions to be thrown...
              eventBus.emit("CustomEvent", jsonInput, new HashMap<String, String>() {{
                put("clientId", clientId);
            }});
            }
          } catch (java.io.IOException ignored) {
            Logger.debug("java.io.IOException");
          } catch (Exception e) {
            ScriptLoader.humanizeStackTrace(e);
            Logger.error("Error handling client message", e);
          }
        }
      });

      if (graphChangedListener != null) {
        graphChangedListener.addClientListener(client);
      }

      if (graphInterface != null) {
        if (!experimentInstance.hasStarted) {
          graphInterface.addPlayer(clientId);
        }
        Graph wholeGraph = (Graph) engine.get("g");
        Vertex clientVertex = wholeGraph.getVertex(clientId);
        Recruitment.applyTo(clientVertex);
        // After addPlayer, so a new participant's vertex exists to record it on.
        setConnected(clientId, true);
        if (clientVertex != null) {
          if (graphChangedListener instanceof EventGraphChangedListener) {
            client.updateGraph(clientVertex);
          }
        }
      }

      // Update the client's state
      ObjectNode jsonOutput = Json.newObject();
      jsonOutput.put("style", experimentInstance.experiment.getStyle());
      Logger.debug("addClient, " + clientId);
      out.write(jsonOutput);
    } catch (NumberFormatException nfe) {
      Logger.debug("addClient: NumberFormatException");
    }
  }

  public void onReceive(Object message) throws Exception {
    Breadboard.BreadboardMessage request = message instanceof Breadboard.BreadboardMessage
        ? (Breadboard.BreadboardMessage) message : null;
    try {
      if (request != null) {
        handle(request);
      }
    } catch (Exception e) {
      ScriptLoader.humanizeStackTrace(e);
      Logger.error("Unhandled error in ScriptBoard.onReceive", e);
      // Tell the console that asked, instead of failing silently.
      if (request != null && request.user != null && request.out != null) {
        Breadboard.replyError(request, "Something went wrong: " + ScriptBoardSupport.describeError(e));
      }
    }
  }

  private void handle(Breadboard.BreadboardMessage breadboardMessage) throws Exception {
    Object message = breadboardMessage;
    // Internal messages (OnJoinStep / OnLeaveStep) carry no account and must not redirect the
    // game's "finished" notice to everyone's consoles.
    if (breadboardMessage.user != null) {
      gameListener.user = breadboardMessage.user;
      gameListener.out = breadboardMessage.out;
    }
    if (breadboardMessage.user != null && actsOnTheGame(breadboardMessage)) {
      // The engine is shared: an account's console shows its own selection, but these act on
      // whatever the engine runs, which may be another account's live session.
      String refusal = engineRefusal(breadboardMessage.user);
      if (refusal != null) {
        if (message instanceof Breadboard.EditTie) {
          Breadboard.EditTie editTie = (Breadboard.EditTie) message;
          Breadboard.tieEditRefused(breadboardMessage.out, editTie.op, editTie.a, editTie.b, refusal);
        } else {
          Breadboard.replyError(breadboardMessage, refusal);
        }
        return;
      }
    }
    // A script or step written for another experiment than the engine's is refused too. Checked
    // after the refusal above, which names what the engine runs and what to do: what is left is a
    // page out of date with the account's own selection (another tab switched), or a step saved
    // for another experiment.
    String stale = staleRequest(breadboardMessage);
    if (stale != null) {
      Breadboard.replyError(breadboardMessage, stale);
      return;
    }
    if (message instanceof Breadboard.AddAdmin) {
      Breadboard.AddAdmin addAdmin = (Breadboard.AddAdmin) message;
      // This socket's own Admin (one per socket), then this socket's own copy of the live network.
      Admin admin = registerAdmin(addAdmin.user, addAdmin.scriptBoardController, addAdmin.out);
      replayGraph(admin);
    } else if (message instanceof Breadboard.RunGame) {
      if (breadboardMessage.user != null) {
        // Run Game (every frame tagged, "started already" included, so the console knows it is the answer)
        processScript("initStep.start()", breadboardMessage.out, null, "request", "RunGame");
      }

    } else if (message instanceof Breadboard.HitCreated) {
      Breadboard.HitCreated hitCreated = (Breadboard.HitCreated) message;
      if (breadboardMessage.user != null) {
        Integer lifetimeInMs = hitCreated.lifetimeInSeconds * 1000;
        Integer tutorialTimeInMs = hitCreated.tutorialTime * 1000;
        Long timerTime = System.currentTimeMillis() + (lifetimeInMs + tutorialTimeInMs);
        // startAt will be used to add timer to clients for start of game
        processScript("startAt = " + timerTime, breadboardMessage.out, null);

        final ThrottledWebSocketOut breadboardOut = breadboardMessage.out;
        // Schedule the two AMT lifecycle timers via the tracked registry so they are
        // cancelled on reload / superseded on re-submit instead of leaking. Same delays
        // and actions as before: at lifetimeInMs no new connections are allowed; at
        // lifetimeInMs + tutorialTimeInMs initStep.start() is sent (MEMORY_LEAKS.md L6).
        scheduleAmtTimers(lifetimeInMs, lifetimeInMs + tutorialTimeInMs,
          new Runnable() {
            @Override
            public void run() {
              gameListener.hasStarted();
            }
          },
          new Runnable() {
            @Override
            public void run() {
              processScript("initStep.start()", breadboardOut, null);
              Logger.debug("initStep.start()");
            }
          });
      }
    } else if (message instanceof Breadboard.SendScript) {
      Breadboard.SendScript sendScript = (Breadboard.SendScript) message;

      if (breadboardMessage.user != null) {
        // Checked again and run under the engine lock, so no other experiment is loaded in between.
        synchronized (REBUILD_LOCK) {
          // (Another account may have loaded its experiment while this waited for the lock: that
          // refusal names it and says what to do.)
          stale = engineRefusal(breadboardMessage.user);
          if (stale == null) {
            stale = staleRequest(sendScript);
          }
          if (stale != null) {
            Breadboard.replyError(sendScript, stale);
            return;
          }
          // Save script in database
          breadboardMessage.user.currentScript = sendScript.script;
          breadboardMessage.user.update();

          // Process script
          processScript(sendScript.script, breadboardMessage.out, null, "request", "SendScript", sendScript.requestId);
        }
      }

    } else if (message instanceof Breadboard.MakeChoice) {
      Breadboard.MakeChoice makeChoice = (Breadboard.MakeChoice) message;
      makeChoice(null, makeChoice.uid, makeChoice.params, breadboardMessage.out); // admin acting for a player
    } else if (message instanceof Breadboard.EditTie) {
      Breadboard.EditTie editTie = (Breadboard.EditTie) message;
      editTie(editTie.op, editTie.a, editTie.b, breadboardMessage.out);
    } else if (message instanceof Breadboard.SendStep) {
      Breadboard.SendStep sendStep = (Breadboard.SendStep) message;

      if (breadboardMessage.user != null && breadboardMessage.user.selectedExperiment != null) {
        Logger.debug("SendStep: " + sendStep.id + ", " + sendStep.name + ", " + sendStep.source);
        // Now we only run the step, in this engine: if another account rebuilds it meanwhile, the
        // step is dropped rather than run in that experiment's engine. The engine checked against
        // here is the one it is queued for.
        Long generation;
        synchronized (REBUILD_LOCK) {
          stale = engineRefusal(breadboardMessage.user);
          if (stale == null) {
            stale = staleRequest(sendStep);
          }
          if (stale != null) {
            Breadboard.replyError(sendStep, stale);
            return;
          }
          generation = engineGeneration;
        }
        getSelf().tell(new Breadboard.RunStep(breadboardMessage.user, sendStep.source, breadboardMessage.out, "SendStep",
            generation, sendStep.requestId), null);
        // A re-run step may set the autopilot up itself; the console's settings apply again after it.
        getSelf().tell(new Breadboard.StepsLoaded(breadboardMessage.user, engineExperiment(), breadboardMessage.out, generation), null);
      } else {
        Breadboard.replyError(sendStep, "Select an experiment first.");
      }
    } else if (message instanceof Breadboard.RunStep) {
      // TODO: Compile step here and make available to scripting engine
      Breadboard.RunStep runStep = (Breadboard.RunStep) message;
      // Under the lock, so the engine can't be replaced between the check and the run.
      synchronized (REBUILD_LOCK) {
        if (runStep.generation != null && runStep.generation != engineGeneration) {
          Breadboard.replyError(breadboardMessage.out, runStep.request,
              "Nothing was run: the engine was reloaded (by you or someone else) before the step could run. Check what it runs now, then try again.",
              null, runStep.echo());
        } else if (runStep.request != null) {
          processScript(runStep.source, breadboardMessage.out, null, "request", runStep.request, runStep.requestId, true);
        } else {
          processScript(runStep.source, breadboardMessage.out, null, "source", STEPS_SOURCE, null, true);
        }
      }
    } else if (message instanceof Breadboard.StepsLoaded) {
      Breadboard.StepsLoaded stepsLoaded = (Breadboard.StepsLoaded) message;
      synchronized (REBUILD_LOCK) {
        if (stepsLoaded.generation == null || stepsLoaded.generation == engineGeneration) {
          afterStepsLoaded(stepsLoaded.experiment);
        }
      }
    } else if (message instanceof Breadboard.ChangeExperiment) {
      if (breadboardMessage.user != null) {
        Breadboard.ChangeExperiment changeExperiment = (Breadboard.ChangeExperiment) message;
        Experiment experiment = breadboardMessage.user.selectedExperiment != null
            ? breadboardMessage.user.selectedExperiment : changeExperiment.experiment;

        // The account's own running session of this experiment is taken up again after the rebuild.
        ExperimentInstance resumed = null;
        if (breadboardMessage.user.experimentInstanceId != -1) {
          ExperimentInstance runningInstance = ExperimentInstance.findById(breadboardMessage.user.experimentInstanceId);
          if (runningInstance != null) {
            if (runningInstance.status == ExperimentInstance.Status.RUNNING && runningInstance.experiment.id.equals(changeExperiment.experiment.id)) {
              resumed = runningInstance;
            }
          } else {
            Logger.error("runningInstance == null");
          }
        }
        synchronized (REBUILD_LOCK) {
          // Any other session the engine runs ends with the rebuild (its network is emptied). It is
          // marked stopped: left running, its pages reconnected into this experiment's game, and the
          // Sessions list and the /play links still showed it as open. (Read under the lock: another
          // actor may have changed the engine since this message was sent.)
          GameListener previous = engineGameListener;
          if (previous != null && (resumed == null || !resumed.id.equals(instanceIdOf(previous)))) {
            pauseAutopilotIfItsSession(previous, breadboardMessage.user, "ended when %s selected an experiment (which reloads the engine)", null);
            stopSession(previous);
          }

          rebuildScriptBoard(experiment, builtFor(breadboardMessage));
          loadSteps(experiment, breadboardMessage.out);

          //reset the instance
          if (resumed != null) {
            eventTracker.setExperimentInstance(resumed);
            gameListener.experimentInstance = resumed;
            reopenAfterRebuild(resumed);
            for (Data param : resumed.data) {
              initParam(param, changeExperiment.experiment);
            }
          } else {
            // The engine runs no session now; events no longer go to the one it ran before.
            gameListener.experimentInstance = null;
            eventTracker.setExperimentInstance(null);
          }
        }
      }
    } else if (message instanceof Breadboard.DeleteExperiment) {
      deleteExperiment((Breadboard.DeleteExperiment) message);
    } else if (message instanceof Breadboard.LaunchGame) {
      launchGame((Breadboard.LaunchGame) message);
    } // END else if(message instanceof Breadboard.LaunchGame)
    else if (message instanceof Breadboard.SelectInstance) {
      selectInstance((Breadboard.SelectInstance) message);
    } // END else if(message instanceof Breadboard.SelectInstance)
    else if (message instanceof Breadboard.StopGame) {
      Breadboard.StopGame stopGame = (Breadboard.StopGame) message;

      ExperimentInstance instance = ExperimentInstance.findById(stopGame.id);
      if (instance == null) {
        Breadboard.replyError(breadboardMessage.out, "StopGame", "There is no session with id " + stopGame.id + ".");
        return;
      }
      synchronized (REBUILD_LOCK) {
        // The session the engine is running, whichever account started it.
        GameListener running = engineGameListener != null ? engineGameListener : gameListener;
        if (instance.id.equals(instanceIdOf(running))) {
          // A researcher stopping the autopilot's session pauses it (no new one opens behind their
          // back); first, so its periodic check can't take the engine in between.
          Autopilot.sessionStopped(instance, breadboardMessage.user);
          Experiment experiment = engineExperiment();
          if (experiment == null) {
            experiment = breadboardMessage.user.getExperiment();
          }
          if (experiment != null && engine != null) {
            for (Parameter p : experiment.getParameters()) {
              if (engine.getBindings(ScriptContext.ENGINE_SCOPE).containsKey(p.name))
                engine.getBindings(ScriptContext.ENGINE_SCOPE).remove(p.name);
            }
          }
          LinkedHashMap<Object, Object> stopped = new LinkedHashMap<Object, Object>();
          stopped.put("by", breadboardMessage.user.email);
          trackEvent("sessionStopped", stopped);
          stopSession(running);
          endParticipants(instance.id);
          // The game's timers (a round's time limit, takeovers, the experiment's own) would go on
          // firing into a stopped session, without its launch parameters; and nothing more is
          // recorded in it until it is resumed (which turns recording back on).
          cancelEngineTimers();
          eventTracker.disable();
        } else {
          // (This used to change the status in memory only.)
          stopQuietly(instance.id);
        }
      }
      // Stopping an older session leaves the researcher's current one selected.
      if (instance.id.equals(breadboardMessage.user.experimentInstanceId)) {
        breadboardMessage.user.setExperimentInstanceId(-1L);
        breadboardMessage.user.update();
      }
      // Update User JSON (in every tab of the account)
      Breadboard.breadboardController.tell(new Breadboard.Update(breadboardMessage.user, breadboardMessage.out, true), null);
    } // END else if(message instanceof Breadboard.StopGame)
    else if (message instanceof Breadboard.RunOnJoinStep) {
      Breadboard.RunOnJoinStep runOnJoinStep = (Breadboard.RunOnJoinStep) message;
      Experiment experiment = joinLeaveExperiment(runOnJoinStep.user);
      //the script engine was throwing exception for ArbesmanRand because of missing onJoinStep
      if (experiment != null && experiment.hasOnJoinStep()) {
        Vertex player = runOnJoinStep.vertex;
        // A quoted literal, not "..." around the raw id: a client id is typed by the participant,
        // and "${...}" inside a double-quoted Groovy string would run as code.
        processScript("onJoinStep.start(" + groovyString(player.getId().toString()) + ")", runOnJoinStep.out, null,
            "source", Experiment.ON_JOIN_STEP_NAME);
      }

    } // END else if(message instanceof Breadboard.RunOnJoinStep)
    else if (message instanceof Breadboard.RunOnLeaveStep) {
      Breadboard.RunOnLeaveStep runOnLeaveStep = (Breadboard.RunOnLeaveStep) message;
      Experiment experiment = joinLeaveExperiment(runOnLeaveStep.user);
      //the script engine was throwing exception for ArbesmanRand because of missing runOnLeaveStep
      if (experiment != null && experiment.hasOnLeaveStep()) {
        Vertex player = runOnLeaveStep.vertex;
        // This used to test the binding "onLeaveStep.start()", which nothing ever sets, so the
        // OnLeaveStep never ran. The step itself is bound as "onLeaveStep".
        if (engine != null && engine.get("onLeaveStep") != null) {
          processScript("onLeaveStep.start(" + groovyString(player.getId().toString()) + ")", runOnLeaveStep.out, null,
              "source", Experiment.ON_LEAVE_STEP_NAME);
        }
      }

    } // END else if(message instanceof Breadboard.RunOnLeaveStep)
    else if (message instanceof Breadboard.Refresh) {
      // Replay the network to the socket that asked (it used to repoint every console to it).
      Admin admin = registerAdmin(breadboardMessage.user, getSelf(), breadboardMessage.out);
      replayGraph(admin);
    } else if (message instanceof Breadboard.GameFinish) {
      getSelf().tell(new Breadboard.ReloadEngine(breadboardMessage.user, breadboardMessage.out), null);
      Breadboard.breadboardController.tell(new Breadboard.Update(breadboardMessage.user, breadboardMessage.out), null);
    } else if (message instanceof Breadboard.ReloadEngine) {
      Experiment selectedExperiment = breadboardMessage.user.getExperiment();
      synchronized (REBUILD_LOCK) {
        // A session another account runs ends with the rebuild: marked stopped, as above. This
        // account's own session carries on in the new engine, as before, if it is of the experiment
        // reloaded (the autopilot launches through its account's actor, and leaves the account's
        // selection alone, so it can be another experiment's).
        GameListener previous = engineGameListener;
        ExperimentInstance own = previous == gameListener ? gameListener.experimentInstance : null;
        boolean carriesOn = own != null && own.experiment != null && selectedExperiment != null
            && own.experiment.id.equals(selectedExperiment.id);
        if (previous != null && !carriesOn) {
          pauseAutopilotIfItsSession(previous, breadboardMessage.user, "ended when %s reloaded the engine", null);
          stopSession(previous);
          if (previous == gameListener) {
            gameListener.experimentInstance = null;
          }
        }
        rebuildScriptBoard(selectedExperiment, builtFor(breadboardMessage));
        loadSteps(selectedExperiment, breadboardMessage.out);
        if (carriesOn) {
          reopenAfterRebuild(own);
        }
        Breadboard.ReloadEngine reloadEngine = (Breadboard.ReloadEngine) message;
        if (reloadEngine.user.experimentInstanceId != -1) {
          ExperimentInstance ei = ExperimentInstance.findById(reloadEngine.user.experimentInstanceId);
          if (ei != null) {
            eventTracker.enable();
            eventTracker.setExperimentInstance(ei);
            for (Data d : ei.data) {
              initParam(d, selectedExperiment);
            }
          }
        }
      }
    }
  }

  /**
   * A session carried into a rebuilt engine (resumed, reloaded, or kept when its experiment is
   * selected again): the rebuild forgot its game, so it takes people again, newcomers and those who
   * held a seat, until its game is started again. Left closed, it turned everyone away: the login
   * refused newcomers, and seat holders coming back were dropped.
   */
  private static void reopenAfterRebuild(ExperimentInstance session) {
    // Only a running one: a finished or stopped session keeps the record that its game ran.
    if (session == null || session.isTestInstance() || session.status != ExperimentInstance.Status.RUNNING
        || !Boolean.TRUE.equals(session.hasStarted)) {
      return;
    }
    try {
      // Through the setter, which saves: a plain field write here isn't seen by Ebean's change
      // tracking, so a save() after it wrote nothing.
      session.setHasStarted(Boolean.FALSE);
    } catch (Exception e) {
      // (e.g. another actor saved this session meanwhile) The rebuild itself has happened.
      Logger.error("Could not reopen session " + session.id + " to newcomers after the engine was rebuilt", e);
    }
  }

  // A researcher's action that runs in the engine's game (not RunStep, StepsLoaded or the join and
  // leave steps, which the engine's own actor queues).
  private static boolean actsOnTheGame(Breadboard.BreadboardMessage message) {
    return message instanceof Breadboard.RunGame || message instanceof Breadboard.SendStep
        || message instanceof Breadboard.SendScript || message instanceof Breadboard.MakeChoice
        || message instanceof Breadboard.EditTie || message instanceof Breadboard.HitCreated;
  }

  /** The answer to a script or step written for another experiment than the one the engine has loaded. */
  static final String ENGINE_CHANGED = "Nothing was run: the engine has another experiment loaded now. Reload the page and try again.";

  /**
   * Why a SendScript or SendStep must not run in the engine as it is now, or null (also for any other
   * message). The console says which experiment its page shows ({@code experimentId}); when the engine
   * has another one loaded (another tab or account loaded one since), what it sends was written for
   * another game. A saved step (id not -1) is checked the same way against the experiment it belongs
   * to; one that no longer exists is not.
   */
  static String staleRequest(Breadboard.BreadboardMessage message) {
    if (message instanceof Breadboard.SendScript) {
      return staleRequest(((Breadboard.SendScript) message).experimentId, null);
    }
    if (message instanceof Breadboard.SendStep) {
      Breadboard.SendStep sendStep = (Breadboard.SendStep) message;
      return staleRequest(sendStep.experimentId, sendStep.id);
    }
    return null;
  }

  static String staleRequest(Long experimentId, Long stepId) {
    if (engine == null) {
      // Nothing is loaded to differ from: the run itself answers that the engine isn't running.
      return null;
    }
    Long loaded = engineExperimentId;
    if (experimentId != null && !experimentId.equals(loaded)) {
      return ENGINE_CHANGED;
    }
    Long stepOf = experimentOfStep(stepId);
    if (stepOf != null && !stepOf.equals(loaded)) {
      return ENGINE_CHANGED;
    }
    return null;
  }

  /** The experiment a saved step belongs to; null for -1 (not saved) or a step that doesn't exist. */
  static Long experimentOfStep(Long stepId) {
    if (stepId == null || stepId < 0) {
      return null;
    }
    com.avaje.ebean.SqlRow row = com.avaje.ebean.Ebean.createSqlQuery("select experiment_id from steps where id = :id")
        .setParameter("id", stepId).findUnique();
    return row == null ? null : row.getLong("experiment_id");
  }

  /**
   * Why this account can't act on the engine's game now, in plain words; null when it can. It can
   * when the engine runs the experiment it has selected (or there is no engine yet: the action
   * says so itself). Its console shows that experiment; the engine may run another account's.
   */
  static String engineRefusal(User user) {
    if (engine == null) {
      return null;
    }
    Long selected = user == null || user.selectedExperiment == null ? null : user.selectedExperiment.id;
    Long running = engineExperimentId;
    if (selected == null ? running == null : selected.equals(running)) {
      return null;
    }
    Experiment experiment = engineExperiment();
    ExperimentInstance session = Autopilot.liveEngineSession();
    String owner = engineOwnerEmail();
    String yours = user == null || user.selectedExperiment == null ? null : user.selectedExperiment.name;
    StringBuilder text = new StringBuilder("Nothing was done: the engine is running ");
    text.append(experiment == null ? "no experiment" : "\"" + experiment.name + "\"");
    if (session != null) {
      text.append(" (session \"").append(session.name).append("\"");
      if (owner != null && (user == null || !owner.equalsIgnoreCase(user.email))) {
        text.append(" of ").append(owner);
      }
      text.append(")");
    }
    text.append(yours == null ? ", and you have no experiment selected." : ", not your selected experiment \"" + yours + "\".");
    if (session != null) {
      text.append(" Launch a session of your own experiment first (that ends the session running now).");
    } else {
      text.append(yours == null ? " Select one first." : " Select \"" + yours + "\" again to load it.");
    }
    return text.toString();
  }

  /** The account whose actor built the running engine (it runs the session in it), or null. */
  static String engineOwnerEmail() {
    GameListener listener = engineGameListener;
    return listener == null || listener.user == null ? null : listener.user.email;
  }

  /**
   * What the shared engine runs, for the console's User JSON ({@code engine}): {@code experimentId},
   * {@code experimentName}, {@code sessionId}, {@code sessionName} (its live session, if any),
   * {@code owner} (the account running it) and {@code gameStarted} (initStep.start() has run without
   * an error since the engine was last built); null values when unknown. An account's own selection
   * can differ, and its engine actions are refused then (see engineRefusal).
   */
  static ObjectNode engineJson() {
    ObjectNode json = Json.newObject();
    json.put("gameStarted", engine != null && gameStartedIn == engine);
    Experiment experiment = engine == null ? null : engineExperiment();
    ExperimentInstance session = engine == null ? null : Autopilot.liveEngineSession();
    String owner = engine == null ? null : engineOwnerEmail();
    if (experiment == null) {
      json.putNull("experimentId");
      json.putNull("experimentName");
    } else {
      json.put("experimentId", experiment.id);
      json.put("experimentName", experiment.name);
    }
    if (session == null) {
      json.putNull("sessionId");
      json.putNull("sessionName");
    } else {
      json.put("sessionId", session.id);
      json.put("sessionName", session.name);
    }
    if (owner == null) {
      json.putNull("owner");
    } else {
      json.put("owner", owner);
    }
    return json;
  }

  // The experiment OnJoinStep / OnLeaveStep belong to: the one whose steps the engine loaded.
  // Messages from an older caller that name an account fall back to that account's selection.
  private static Experiment joinLeaveExperiment(User user) {
    Experiment experiment = engineExperiment();
    if (experiment == null && user != null) {
      user.refresh();
      experiment = user.selectedExperiment;
    }
    return experiment;
  }

  /**
   * Mark a session stopped if it still can be, from a freshly loaded copy. Never throws: a session
   * that finished meanwhile (or was deleted) is simply left as it is. Stopping it through a stale
   * copy used to throw IllegalStateException half-way through selecting another session.
   */
  static void stopQuietly(Long instanceId) {
    if (instanceId == null || Experiment.TEST_INSTANCE_ID.equals(instanceId)) {
      return;
    }
    try {
      ExperimentInstance current = ExperimentInstance.findById(instanceId);
      if (current != null && current.status != null && current.status.isRunnable()) {
        current.stop();
        current.save();
      }
    } catch (Exception e) {
      Logger.warn("Could not stop session " + instanceId + ": " + e);
    }
  }

  /**
   * Stop the session a game listener runs, through its own copy as before (the engine's scripts
   * keep using that copy), if it can still be stopped; never throws. GameListener.stop() threw
   * IllegalStateException for a finished session, aborting the selection of another one half-way.
   */
  static void stopSession(GameListener listener) {
    if (listener == null || listener.experimentInstance == null || listener.experimentInstance.id == null) {
      return;
    }
    ExperimentInstance instance = listener.experimentInstance;
    try {
      if (instance.status != null && instance.status.isRunnable()) {
        listener.stop();
      }
    } catch (Exception e) {
      // Its copy was out of date (changed meanwhile): stop a fresh one, if it still can be.
      Logger.warn("Could not stop session " + instance.id + " through the game's copy: " + e);
      stopQuietly(instance.id);
    }
  }

  private static Long instanceIdOf(GameListener listener) {
    return (listener == null || listener.experimentInstance == null) ? null : listener.experimentInstance.id;
  }

  private void launchGame(Breadboard.LaunchGame launchGame) throws IOException, ScriptException {
    User user = launchGame.user;
    // The autopilot names its experiment; a researcher launches the one they have selected.
    boolean byAutopilot = launchGame.autopilotExperiment != null;
    Experiment experiment = byAutopilot ? launchGame.autopilotExperiment : user.selectedExperiment;
    if (experiment == null) {
      Breadboard.replyError(launchGame.out, "LaunchGame", "Select an experiment first.");
      return;
    }
    // The console says which experiment its page shows; the account's selection may have changed
    // since (in another tab), and the launch would start that one with this page's parameters.
    if (launchGame.experimentId != null && !launchGame.experimentId.equals(experiment.id)) {
      Breadboard.replyError(launchGame.out, "LaunchGame", "Nothing was started: \"" + experiment.name
          + "\" has been selected since this page was loaded (in another tab or window). Check the experiment shown here and try again.");
      return;
    }
    // Everything is checked before the engine is reset: a missing value used to throw only after the
    // running session had been ended.
    LinkedHashMap<String, String> parameters;
    try {
      parameters = launchParameters(launchGame.parameters, experiment.getParameters());
    } catch (IllegalArgumentException invalid) {
      Breadboard.replyError(launchGame.out, "LaunchGame", "The session was not started: " + invalid.getMessage());
      return;
    }

    // The autopilot leaves its account's selection alone. The account follows the session (its
    // consoles show it) when it has this experiment selected, or none, which then becomes this one.
    boolean onAccount = !byAutopilot || user.selectedExperiment == null || experiment.id.equals(user.selectedExperiment.id);
    if (byAutopilot && user.selectedExperiment == null) {
      user.setSelectedExperiment(experiment);
    }

    synchronized (REBUILD_LOCK) {
      // Asked a moment ago: still wanted now that it runs? (A researcher may have paused it,
      // launched a session or loaded another experiment meanwhile.)
      if (byAutopilot && !Autopilot.launchStillWanted(experiment.id, launchGame.onlyIfIdle, launchGame.automatic)) {
        return;
      }
      // The engine's current session ends (the account that started it may be another one, or this
      // one: this actor's own session is replaced too).
      GameListener previous = engineGameListener;
      if (!byAutopilot) {
        pauseAutopilotIfItsSession(previous, user, "ended when %s launched another session", null);
      }
      rebuildScriptBoard(experiment, byAutopilot ? null : user.email);
      stopSession(previous);
      ExperimentInstance instance = startGame(launchGame.name, user.experimentInstanceId, parameters, experiment, user, onAccount);

      instanceData.addPropertyChangeListener(new UserDataChangeListener(instance));

      eventTracker.enable();
      eventTracker.setExperimentInstance(instance);
      gameListener.experimentInstance = instance;
      gameListener.start();

      // Re-run the Steps, then the autopilot settings over them: here, under the lock, so another
      // account's rebuild can't come between (queued, they used to run in whatever engine was there).
      String failed = loadSteps(experiment, launchGame.out);
      if (byAutopilot) {
        Autopilot.launchCarriedOut();
        if (failed != null) {
          // The steps define its waiting room and game: participants would wait for a game that
          // never starts. The session stops, and the autopilot pauses until the steps are fixed.
          LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
          data.put("error", failed);
          eventTracker.track("stepsFailedToLoad", data);
          stopSession(gameListener);
          endParticipants(instance.id);
          cancelEngineTimers();
          eventTracker.disable();
          Autopilot.stepsFailed(instance, failed);
        }
      }
    }

    // Update User JSON (in every tab of the account)
    Breadboard.breadboardController.tell(new Breadboard.Update(user, launchGame.out, true), null);
  }

  /**
   * The values a new session starts with, as text, checked before anything is changed. Every
   * parameter the experiment defines gets a value: the one sent, or its default when none was sent
   * (or null was, e.g. a cleared number field). Whole numbers sent as 5.0 count as 5. Values for
   * names the experiment doesn't define are kept as they are, as before.
   *
   * @throws IllegalArgumentException naming the parameter, when a value is missing with no default
   *     or is not a number where one is needed
   */
  static LinkedHashMap<String, String> launchParameters(Map<?, ?> sent, List<Parameter> defined) {
    Map<?, ?> values = sent == null ? Collections.emptyMap() : sent;
    LinkedHashMap<String, String> result = new LinkedHashMap<String, String>();
    Map<String, Parameter> byName = new LinkedHashMap<String, Parameter>();
    for (Parameter p : defined) {
      if (p != null && p.name != null) {
        byName.put(p.name, p);
      }
    }
    for (Map.Entry<?, ?> entry : values.entrySet()) {
      if (entry.getKey() == null) {
        continue;
      }
      String name = entry.getKey().toString();
      Parameter parameter = byName.get(name);
      if (parameter == null) {
        if (entry.getValue() != null) {
          result.put(name, entry.getValue().toString());
        }
      } else {
        result.put(name, parameterValue(parameter, entry.getValue()));
      }
    }
    for (Parameter parameter : byName.values()) {
      if (!result.containsKey(parameter.name)) {
        result.put(parameter.name, parameterValue(parameter, null));
      }
    }
    return result;
  }

  private static String parameterValue(Parameter parameter, Object sent) {
    String type = parameter.type == null ? "" : parameter.type;
    Object value = sent;
    if (value == null || (value instanceof String && ((String) value).trim().isEmpty() && !"Text".equals(type))) {
      value = parameter.defaultVal;
    }
    if (value == null || (value instanceof String && ((String) value).trim().isEmpty() && !"Text".equals(type))) {
      if ("Text".equals(type)) {
        return "";
      }
      if ("Boolean".equals(type)) {
        return "false";
      }
      throw new IllegalArgumentException("\"" + parameter.name + "\" needs a value.");
    }
    if ("Integer".equals(type)) {
      if (value instanceof Number) {
        double d = ((Number) value).doubleValue();
        if (d != Math.rint(d) || Double.isInfinite(d)) {
          throw new IllegalArgumentException("\"" + parameter.name + "\" must be a whole number, not " + value + ".");
        }
        return String.valueOf(((Number) value).longValue());
      }
      String text = value.toString().trim();
      try {
        Integer.parseInt(text);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("\"" + parameter.name + "\" must be a whole number, not \"" + text + "\".");
      }
      return text;
    }
    if ("Decimal".equals(type)) {
      String text = value.toString().trim();
      try {
        double d = Double.parseDouble(text);
        if (Double.isNaN(d) || Double.isInfinite(d)) {
          throw new NumberFormatException();
        }
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("\"" + parameter.name + "\" must be a number, not \"" + text + "\".");
      }
      return text;
    }
    return value.toString();
  }

  private void selectInstance(Breadboard.SelectInstance selectInstance) throws IOException, ScriptException {
    User user = selectInstance.user;
    ExperimentInstance target = ExperimentInstance.findById(selectInstance.id);
    if (target == null) {
      Breadboard.replyError(selectInstance.out, "SelectInstance", "There is no session with id " + selectInstance.id + ".");
      return;
    }
    // The session's own experiment, so the engine never runs one experiment's steps for another's session.
    Experiment experiment = target.experiment == null ? null : Experiment.findById(target.experiment.id);
    if (experiment == null) {
      experiment = user.selectedExperiment;
    }
    if (experiment == null) {
      Breadboard.replyError(selectInstance.out, "SelectInstance", "Select an experiment first.");
      return;
    }
    if (user.selectedExperiment == null || !experiment.id.equals(user.selectedExperiment.id)) {
      user.setSelectedExperiment(experiment);
    }
    boolean resuming = target.status == ExperimentInstance.Status.STOPPED;

    synchronized (REBUILD_LOCK) {
      // The engine's current session ends (the account that started it may be another one), and so
      // does this actor's own; the selected one carries on (restarted) if it still can.
      GameListener previous = engineGameListener;
      Long continuing = target.status != null && target.status.isRunnable() ? target.id : null;
      pauseAutopilotIfItsSession(gameListener, user, "ended when %s opened another session", continuing);
      if (previous != null && previous != gameListener) {
        pauseAutopilotIfItsSession(previous, user, "ended when %s opened another session", continuing);
      }
      rebuildScriptBoard(experiment, builtFor(selectInstance));
      loadSteps(experiment, selectInstance.out);
      user.setExperimentInstanceId(target.id);
      user.update();

      stopSession(gameListener);
      if (previous != null && previous != gameListener) {
        stopSession(previous);
      }
      // Loaded again: stopping may just have saved this same session.
      ExperimentInstance instance = ExperimentInstance.findById(target.id);
      if (instance != null && instance.status != null && instance.status.isRunnable()) {
        List<Data> parameters = instance.data;
        for (Data param : parameters) {
          initParam(param, experiment);
        } // END for (Data param : parameters)

        eventTracker.enable();
        eventTracker.setExperimentInstance(instance);
        gameListener.experimentInstance = instance;
        gameListener.start();
        // (After start(): a stopped session being resumed counts as running only from there.)
        reopenAfterRebuild(instance);
        if (resuming) {
          // Its game starts again from the beginning: the data says where, so the runs can be told apart.
          LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
          data.put("by", user.email);
          eventTracker.track("sessionResumed", data);
        }

        instanceData.addPropertyChangeListener(new UserDataChangeListener(instance));
      } else {
        // A finished session is shown, not restarted: the engine runs no session now.
        gameListener.experimentInstance = null;
      }
    }
    //don't know why... ebean bug? if don't do this, the instances in the selectedExperiment doesn't get updated
    //which is causing the launch button still shows up (because the status isn't updated) instead of the stop button for the instance
    user.selectedExperiment = Experiment.findById(experiment.id);
    Breadboard.breadboardController.tell(new Breadboard.Update(user, selectInstance.out, true), null);
  }

  private void deleteExperiment(Breadboard.DeleteExperiment deleteExperiment) {
    User requester = deleteExperiment.user;
    // By id when the console sends one; the name is only unique per account, and not even then.
    Experiment toBeDeleteExperiment = null;
    if (deleteExperiment.experimentId != null) {
      toBeDeleteExperiment = Experiment.findById(deleteExperiment.experimentId);
    } else if (deleteExperiment.experimentName != null) {
      toBeDeleteExperiment = requester.getExperimentByName(deleteExperiment.experimentName);
    }
    if (toBeDeleteExperiment == null) {
      Breadboard.replyError(deleteExperiment.out, "DeleteExperiment", "There is no experiment "
          + (deleteExperiment.experimentId != null ? "number " + deleteExperiment.experimentId : "called \"" + deleteExperiment.experimentName + "\"") + ".");
      return;
    }
    Logger.debug("DeleteExperiment: " + toBeDeleteExperiment.name);
    Long deletedId = toBeDeleteExperiment.id;

    // Also stops its game, if the engine runs this experiment (and only then: deleting another
    // experiment used to empty the network of whatever session was running).
    if (engine != null && deletedId.equals(engineExperimentId)) {
      for (Parameter p : toBeDeleteExperiment.getParameters()) {
        Logger.debug("Key: " + p.name);
        if (engine.getBindings(ScriptContext.ENGINE_SCOPE).containsKey(p.name))
          engine.getBindings(ScriptContext.ENGINE_SCOPE).remove(p.name);
      }
      processScript("g.empty()", deleteExperiment.out, null);
    }

    requester.currentScript = "";
    //needs to remove all the selectedExperiment and ownedExperiments which is this toBeDeleteExperiment
    List<User> users = User.find.all();

    boolean update;
    boolean updateManyToMany;
    for (User user : users) {
      update = false;
      if (user.email.equalsIgnoreCase(requester.email)) {
        user = requester;
        user.setExperimentInstanceId(-1L);
        update = true;
      }
      updateManyToMany = false;
      // equals, not ==: ids are Long objects, equal only by reference above 127
      if (user.selectedExperiment != null && deletedId.equals(user.selectedExperiment.id)) {
        update = true;
        user.setSelectedExperiment(null);
      }
      if (user.ownedExperiments.contains(toBeDeleteExperiment)) {
        updateManyToMany = true;
        user.ownedExperiments.remove(toBeDeleteExperiment);
      }
      if (update) {
        user.update();
      }
      if (updateManyToMany) {
        user.saveManyToManyAssociations("ownedExperiments");
      }
    }

    toBeDeleteExperiment.delete();
    Autopilot.experimentDeleted(deletedId);

    Breadboard.breadboardController.tell(new Breadboard.Update(requester, deleteExperiment.out, true), null);
    experimentsChanged();
  }

  /**
   * Create the new session and record it as the account's ({@code onAccount}): the account's previous
   * running session is marked stopped, and the new one becomes its current one. Not for an autopilot
   * launch while its account has another experiment selected.
   */
  private ExperimentInstance startGame(String gameName, long currentExperimentInstanceId, LinkedHashMap parameters, Experiment selectedExperiment, User user,
                                       boolean onAccount) {
    ExperimentInstance instance = new ExperimentInstance(gameName, selectedExperiment);
    instance.hasStarted = false;
    instance.status = ExperimentInstance.Status.STOPPED;

    if (onAccount && currentExperimentInstanceId != -1) {
      ExperimentInstance runningInstance = ExperimentInstance.findById(currentExperimentInstanceId);
      // (Often stopped already, as the engine's session; then left as it is.)
      if (runningInstance != null && (runningInstance.status == ExperimentInstance.Status.RUNNING
          || runningInstance.status == ExperimentInstance.Status.TESTING)) {
        runningInstance.status = ExperimentInstance.Status.STOPPED;
        runningInstance.update();
      }
    }

    initAllParam(parameters, selectedExperiment, instance);

    selectedExperiment.instances.add(instance);
    selectedExperiment.save();

    if (!onAccount) {
      return instance;
    }
    user.setExperimentInstanceId(instance.id);
    try {
      user.update();
    } catch (javax.persistence.OptimisticLockException changedMeanwhile) {
      // The account changed since this copy was loaded (a sign-in, another tab): Ebean refuses a
      // stale copy, which used to abort the launch after the session was created. Record it on a
      // fresh copy instead.
      User fresh = User.findByEmail(user.email);
      if (fresh == null) {
        throw changedMeanwhile;
      }
      fresh.setExperimentInstanceId(instance.id);
      if (fresh.selectedExperiment == null && user.selectedExperiment != null) {
        fresh.setSelectedExperiment(user.selectedExperiment);
      }
      fresh.update();
    }

    return instance;
  }

  private void initAllParam(Map params, Experiment experiment, ExperimentInstance instance) {

    Iterator it = params.keySet().iterator();
    while (it.hasNext()) {
      Object key = it.next();
      // Add the initial parameters to the data of the ExperimentInstance
      Data data = new Data();
      data.name = key.toString();
      data.value = params.get(key).toString();
      if (instance != null) {
        instance.data.add(data);
      }

      initParam(data, experiment);
    } // END while(it.hasNext())
  }

  private void initParam(Data param, Experiment experiment) {
    String key = param.name;
    Logger.debug("initParam: " + key);
    Parameter parameter = experiment == null ? null : experiment.getParameterByName(key);
    String type = parameter == null ? null : parameter.type;

    // Coercion logic lives in ScriptBoardSupport.coerceParam so it can be unit-tested
    // without the engine/DB. A null result means "leave the binding unset" -- which for
    // Integer/Decimal indicates a parse failure worth logging (as the original did).
    Object coerced = ScriptBoardSupport.coerceParam(type, param.value);
    if (coerced != null) {
      engine.getBindings(ScriptContext.ENGINE_SCOPE).put(key, coerced);
    } else if ("Integer".equals(type) || "Decimal".equals(type)) {
      Logger.error("Breadboard.LaunchGame: Caught NumberFormatException parsing string as "
          + type + ": " + param.value);
    }
  }

  /** Log an event for the current instance from outside the script engine (e.g. an entry route). */
  public static void trackEvent(String name, LinkedHashMap<Object, Object> data) {
    if (eventTracker != null) {
      eventTracker.track(name, data);
    }
  }

  /**
   * Record whether a participant currently has an open socket: {@code player._system.connected}
   * on the vertex (engine-internal, never sent to neighbors) and a clientConnected /
   * clientDisconnected event in the log.
   */
  static void setConnected(String clientId, boolean connected) {
    // Each part on its own: setting _system runs every graph listener (consoles, the player's and
    // neighbours' pages), and one of them failing used to skip bb:connection, so the autopilot never
    // learned that someone left and no AI took their seat.
    try {
      if (engine != null) {
        Object g = engine.get("g");
        if (g instanceof Graph) {
          Vertex v = ((Graph) g).getVertex(clientId);
          if (v != null) {
            setSystemValue(v, "connected", connected);
          }
        }
      }
    } catch (Exception e) {
      Logger.error("Could not record connection state for " + clientId, e);
    } finally {
      try {
        // (not into a session that is over: people leaving its thank-you page aren't part of it)
        if (eventTracker != null && eventTracker.sessionLive()) {
          LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
          data.put("clientId", clientId);
          eventTracker.track(connected ? "clientConnected" : "clientDisconnected", data);
        }
      } catch (Exception e) {
        Logger.error("Could not log the " + (connected ? "connection" : "disconnection") + " of " + clientId, e);
      } finally {
        // Scripts can react, e.g. groovy/autopilot.groovy hands a departed player to the AI.
        try {
          Map<String, Object> info = new HashMap<String, Object>();
          info.put("clientId", clientId);
          info.put("connected", connected);
          eventBus.emit("bb:connection", info);
        } catch (Exception e) {
          Logger.error("Could not announce the connection change of " + clientId, e);
        }
      }
    }
  }

  /**
   * Put a value into {@code player._system} and set the property again, so the change is announced
   * (to the admin console, live, and to the player's own page). Writing into the map alone told
   * no one until the next full replay.
   *
   * <p>It stays the same map, as groovy/autopilot.groovy's status() does, rather than a copy: the
   * autopilot's timers write into it from other threads, and swapping in a copy could drop the
   * write that lands in between (e.g. this very connected flag, which the autopilot counts).
   */
  @SuppressWarnings("unchecked")
  static void setSystemValue(Vertex v, String key, Object value) {
    Object system = v.getProperty("_system");
    if (system instanceof Map) {
      Map<String, Object> systemMap = (Map<String, Object>) system;
      systemMap.put(key, value);
      v.setProperty("_system", systemMap);
    }
  }

  // Held (per player) from checking that a choice is still offered until it has been made, so a
  // participant and a researcher answering the same choice at once don't both run it, and the
  // researcher is told "made" only when theirs ran.
  private static final Object[] CHOICE_LOCKS = new Object[32];
  static {
    for (int i = 0; i < CHOICE_LOCKS.length; i++) {
      CHOICE_LOCKS[i] = new Object();
    }
  }

  private static Object choiceLock(String playerId) {
    return CHOICE_LOCKS[(playerId.hashCode() & 0x7fffffff) % CHOICE_LOCKS.length];
  }

  /**
   * A participant's choice (clientId set), answered with {} or {"error"}, or a researcher's choice
   * on a player's behalf from the console (clientId null), answered with
   * {"request": "MakeChoice", "choice": "made"}, {"request": "MakeChoice", "choice": "gone"} when
   * the choice is no longer offered (answered, expired or the game moved on), or
   * {"error", "request": "MakeChoice"}; each with the choice's "choiceUID".
   */
  static void makeChoice(String clientId, String uid, String params, ThrottledWebSocketOut out) {
    boolean console = clientId == null;
    ObjectNode jsonOutput = Json.newObject();
    try {
      boolean made = false;
      // A choice uid is only valid for the participant it was shown to.
      PlayerActionsInterface actions = playerActions;
      String owner = (actions == null || uid == null) ? null : actions.ownerOf(uid);
      if (owner != null && (console || owner.equals(clientId))) {
        synchronized (choiceLock(owner)) {
          // Someone else may have answered it in the meantime.
          if (owner.equals(actions.ownerOf(uid))) {
            actions.choose(uid, params);
            made = true;
          }
        }
      }
      if (console) {
        jsonOutput.put("request", "MakeChoice");
        jsonOutput.put("choice", made ? "made" : "gone");
        jsonOutput.put("choiceUID", uid);
      } else if (!made) {
        Logger.info("Ignored choice " + uid + " from " + clientId + ": not (or no longer) offered to this participant");
      }
    } catch (Exception e) {
      ScriptLoader.humanizeStackTrace(e);
      Logger.error("Failed to make choice " + uid, e);
      if (console) {
        // The script's own error, not the proxy's wrapping of it (UndeclaredThrowableException).
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
          root = root.getCause();
        }
        Breadboard.replyError(out, "MakeChoice", "The choice could not be made: " + ScriptBoardSupport.describeError(root), null,
            uid == null ? Collections.<String, String>emptyMap() : Collections.singletonMap("choiceUID", uid));
        return;
      }
      jsonOutput.put("error", "Caught error: " + ScriptBoardSupport.describeError(e) + "\n");
    }
    out.write(jsonOutput);
  }

  /**
   * Run a script in the engine, writing its output or its error to {@code out} (if any). Returns what
   * went wrong, briefly (see below), or null when the script ran: the autopilot's start of the game
   * (groovy/autopilot.groovy) acts on it.
   */
  public static String processScript(String script, ThrottledWebSocketOut out, String scriptName) {
    return processScript(script, out, scriptName, null, null);
  }

  /**
   * As above, with {@code tag: tagValue} on every frame it sends (its output, its error, "started
   * already"), e.g. {@code "request": "RunGame"} or {@code "source": "OnJoinStep"}, so a console can
   * tell whose answer a frame is. No tag when {@code tag} is null.
   */
  static String processScript(String script, ThrottledWebSocketOut out, String scriptName, String tag, String tagValue) {
    return processScript(script, out, scriptName, tag, tagValue, null);
  }

  /** As above, with {@code "requestId": requestId} on every frame too when it is not null (see Breadboard.echoed). */
  static String processScript(String script, ThrottledWebSocketOut out, String scriptName, String tag, String tagValue,
                              String requestId) {
    return processScript(script, out, scriptName, tag, tagValue, requestId, false);
  }

  /**
   * As above. {@code stepCode}: the script is an experiment's step code (loaded or run again from the
   * Steps page), which defines steps rather than starting the game, even when it mentions
   * initStep.start() (e.g. an OnJoinStep that starts the game once enough people are in). Its text
   * says nothing then: whether the game starts is seen when the init step actually starts
   * (stepStarting). Returns what went wrong, briefly (ScriptBoardSupport.briefError), or null when
   * the script ran. A step that throws while the script runs is reported here, as the script's error
   * (stepFailed leaves it to this method).
   */
  static String processScript(String script, ThrottledWebSocketOut out, String scriptName, String tag, String tagValue,
                              String requestId, boolean stepCode) {
    if (scriptName == null) scriptName = "Unnamed Script";
    // (FAILED_RUN is about this run from here on)
    FAILED_RUN.remove();

    ObjectNode jsonOutput = Json.newObject();
    if (tag != null) {
      jsonOutput.put(tag, tagValue);
    }
    if (requestId != null) {
      jsonOutput.put("requestId", requestId);
    }
    // One engine throughout, even if another actor rebuilds it meanwhile: the start is marked, run
    // and (if it fails) undone in the same one.
    final ScriptEngine running = engine;
    if (running == null) {
      // Nothing has built an engine yet (no experiment selected since the server started).
      jsonOutput.put("error", "Caught error: The script engine isn't running yet. Select an experiment first.\n");
      if (out != null) {
        out.write(jsonOutput);
      }
      return "the script engine isn't running";
    }
    // The start command itself (what the console's Start, the autopilot and the AMT timer send). It
    // is known before it runs, so a second one is refused and newcomers are turned away first. Other
    // scripts only mentioning it start nothing by that: an OnJoinStep's code defining when to start,
    // or a participant's id in "onJoinStep.start('<id>')". A real start of the init step from any
    // script is seen as it happens (stepStarting).
    boolean initStep = false;
    // For a start: the game listener whose session it closed to newcomers, and that session's
    // hasStarted from before (put back if the start fails).
    GameListener closedBy = null;
    Boolean startedBefore = null;
    synchronized (ScriptBoard.class) {
      if (!stepCode && START_COMMAND.equals(script.trim())) {
        if (running.get("initStep.start()") != null) {
          Logger.debug("engine.get(\"initStep.start()\") = " + running.get("initStep.start()").toString());
          Logger.warn("The initStep has started already.");
          jsonOutput.put("error", "Caught error: ".concat("initStep started already").concat("\n"));
          if (out != null) {
            out.write(jsonOutput);
          }
          return "the game has started already";
        }
        running.put("initStep.start()", true);
        initStep = true;
        // Close the game to newcomers once it starts, however it was started. Only the AMT timer
        // used to do this, so with a manual start late joiners were admitted mid-game.
        if (!Play.application().configuration().getBoolean("breadboard.allowLateJoin", false)) {
          Object listener = running.get("gameListener");
          if (listener instanceof GameListener) {
            try {
              startedBefore = ((GameListener) listener).closeToNewcomers();
              closedBy = (GameListener) listener;
            } catch (Exception e) {
              // The game still starts; only newcomers may still get in.
              Logger.error("Could not close the session to newcomers as its game starts", e);
            }
          }
        }
      }
    }
    if (initStep) {
      // Scripts can react however the game was started (admin, AMT timer, a script), e.g.
      // groovy/autopilot.groovy clears its waiting screen. Sent outside the lock above: a handler
      // may hold its own lock and call back in here.
      Map<String, Object> info = new HashMap<String, Object>();
      info.put("scriptName", scriptName);
      eventBus.emit("bb:game-start", info);
    }
    // A researcher's script (the console's Start, Script console or Steps page) may not start a game
    // that has started already, however it calls initStep.start(); see stepStarting.
    ResearcherRun outer = RESEARCHER_RUN.get();
    if ("request".equals(tag)) {
      ResearcherRun run = new ResearcherRun();
      run.startPending = initStep ? running : null;
      RESEARCHER_RUN.set(run);
    } else {
      RESEARCHER_RUN.remove();
    }
    ScriptRun outerScript = SCRIPT_RUN.get();
    ScriptRun thisScript = new ScriptRun();
    SCRIPT_RUN.set(thisScript);
    String failure = null;
    try {
      Object outputObject = running.eval(script);
      // Clear out error
      jsonOutput.put("error", "");

      // Write out the results Map as JSON
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

      mapper.writeValue(outputStream, running.getBindings(ScriptContext.ENGINE_SCOPE).get("results"));
      String outputString = script.trim().concat("\n\n==>");

      if (outputObject != null) {
        if (outputObject instanceof GremlinGroovyPipeline) {
          outputString += StringUtils.join(((GremlinGroovyPipeline) outputObject).toList(), "\n==>");
        } else {
          outputString += outputObject.toString();
        }
      }

      jsonOutput.put("output", outputString.trim());
      if (initStep) {
        gameStartedIn = running;
        gameStartNotice("GameStarted");
      }
    } catch (CompilationFailedException cfe) {
      // Translate Groovy's opaque "Script<N>.groovy" names back to real file names in both the
      // logged stack trace and the error sent to the browser (see ScriptLoader.humanize*).
      ScriptLoader.humanizeStackTrace(cfe);
      Logger.error("Unable to compile the script. " + scriptName, cfe);
      jsonOutput.put("error", "Caught error: " + ScriptBoardSupport.describeError(cfe) + "\n");
      failure = ScriptBoardSupport.briefError(cfe);
      thisScript.thrown = cfe;
      if (initStep) {
        startFailed(running, closedBy, startedBefore);
      }
    } catch (ScriptException se) {
      ScriptLoader.humanizeStackTrace(se);
      Logger.error("Script Error. " + scriptName, se);
      jsonOutput.put("error", "Caught error: " + ScriptBoardSupport.describeError(se) + "\n");
      failure = ScriptBoardSupport.briefError(se);
      thisScript.thrown = se;
      if (initStep) {
        startFailed(running, closedBy, startedBefore);
      }
    } catch (Throwable e) {
      // (Throwable: the engine passes an Error, e.g. a failed assert in a step, on as it is, and a
      // step that throws one leaves it to this method like any other)
      ScriptLoader.humanizeStackTrace(e);
      Logger.error("Failed to process the script. " + scriptName, e);
      jsonOutput.put("error", "Caught error: " + ScriptBoardSupport.describeError(e) + "\n");
      failure = ScriptBoardSupport.briefError(e);
      thisScript.thrown = e;
      if (initStep) {
        startFailed(running, closedBy, startedBefore);
      }
    } finally {
      if (outer != null) {
        RESEARCHER_RUN.set(outer);
      } else {
        RESEARCHER_RUN.remove();
      }
      if (outerScript != null) {
        SCRIPT_RUN.set(outerScript);
      } else {
        SCRIPT_RUN.remove();
      }
      if (out != null) {
        out.write(jsonOutput);
      }
    }
    if (failure != null) {
      // A step that threw counts only if its failure is what the script failed with (code in
      // between may have caught it).
      if (thisScript.stepFailure != null && !causedBy(thisScript.thrown, thisScript.stepFailure)) {
        thisScript.step = null;
        thisScript.stepFailure = null;
      }
      FAILED_RUN.set(thisScript);
    } else {
      // (nor one this script ran, which failed)
      FAILED_RUN.remove();
    }
    return failure;
  }

  /** Whether {@code cause} is {@code thrown} or one of its causes. */
  private static boolean causedBy(Throwable thrown, Throwable cause) {
    Set<Throwable> seen = new HashSet<Throwable>();
    for (Throwable t = thrown; t != null && seen.add(t); t = t.getCause()) {
      if (t == cause) {
        return true;
      }
    }
    return false;
  }

  /** The script that starts the game. */
  static final String START_COMMAND = "initStep.start()";

  /** What stepStarting marked when an init step started outside processScript, for stepStartFailed. */
  static final class StartMark {
    final ScriptEngine engine;
    final GameListener closedBy;
    final Boolean before;

    StartMark(ScriptEngine engine, GameListener closedBy, Boolean before) {
      this.engine = engine;
      this.closedBy = closedBy;
      this.before = before;
    }
  }

  /**
   * A step is starting (groovy/step.groovy calls this, through GameListener, from Step.start()). When
   * it is the engine's initStep, the game starts, however it was started. The start command
   * (console's Start, autopilot, AMT timer) has been marked by processScript already. A start in the
   * experiment's own code (e.g. an OnJoinStep that starts the game once enough people are in) is
   * marked now, as processScript would: the next Start answers "started already", the session is
   * closed to newcomers, the console sees gameStarted, and scripts get "bb:game-start". Returns what
   * it marked, which Step.start hands to stepStartFailed if the init step throws; null when it
   * marked nothing. Steps of an engine that has since been replaced are ignored.
   *
   * @throws IllegalStateException "initStep started already" when a researcher's script starts a
   *     game that has started already (the console's Start is refused the same way)
   */
  static StartMark stepStarting(GameListener listener, Object step) {
    ScriptEngine running = engine;
    if (running == null || listener == null || step == null || listener.engine != running) {
      return null;
    }
    if (step != running.get("initStep")) {
      return null;
    }
    StartMark mark = null;
    synchronized (ScriptBoard.class) {
      if (running != engine) {
        return null;
      }
      ResearcherRun researcher = RESEARCHER_RUN.get();
      if (running.get("initStep.start()") == null) {
        running.put("initStep.start()", true);
        GameListener closedBy = null;
        Boolean before = null;
        if (!Play.application().configuration().getBoolean("breadboard.allowLateJoin", false)) {
          try {
            before = listener.closeToNewcomers();
            closedBy = listener;
          } catch (Exception e) {
            // The game still starts; only newcomers may still get in.
            Logger.error("Could not close the session to newcomers as its game starts", e);
          }
        }
        mark = new StartMark(running, closedBy, before);
      } else if (researcher != null && researcher.startPending == running) {
        // The start command's own start, marked before it ran; it undoes it itself if it fails.
        researcher.startPending = null;
      } else if (researcher != null) {
        throw new IllegalStateException("initStep started already");
      }
      gameStartedIn = running;
    }
    if (mark != null) {
      // Outside the lock, as in processScript: a handler may hold its own lock and call back in here.
      Map<String, Object> info = new HashMap<String, Object>();
      info.put("scriptName", "initStep");
      eventBus.emit("bb:game-start", info);
      gameStartNotice("GameStarted");
    }
    return mark;
  }

  /**
   * Tell every open console that the game started (or that a start from the experiment's own code
   * was taken back), so it asks again what the engine runs: a start by the autopilot, the AMT timer
   * or the experiment's code answers no console request.
   */
  private static void gameStartNotice(String what) {
    ObjectNode notice = Json.newObject();
    notice.putObject("notify").put(what, new Date().getTime());
    toAllAdmins(notice);
  }

  /**
   * The init step whose start stepStarting marked threw: undone as for the start command (see
   * startFailed), unless the engine has been replaced since. Never throws.
   */
  static void stepStartFailed(Object token) {
    if (!(token instanceof StartMark)) {
      return;
    }
    StartMark mark = (StartMark) token;
    synchronized (ScriptBoard.class) {
      if (mark.engine != engine) {
        return;
      }
    }
    startFailed(mark.engine, mark.closedBy, mark.before);
    gameStartNotice("GameStartFailed");
  }

  /**
   * initStep.start() threw: it may be tried again, the game doesn't count as started, and its session
   * takes newcomers again if it did before (hasStarted back to {@code before}, saved). Never throws.
   */
  private static void startFailed(ScriptEngine running, GameListener closedBy, Boolean before) {
    running.put("initStep.start()", null);
    if (gameStartedIn == running) {
      gameStartedIn = null;
    }
    if (closedBy != null) {
      try {
        closedBy.startFailed(before);
      } catch (Exception e) {
        Logger.error("Could not reopen the session to newcomers after its start failed", e);
      }
    }
  }

  /**
   * A step's own code (run, done, onTimeout) threw (groovy/step.groovy). Inside a script processScript
   * runs, the script fails and processScript tells whoever ran it (the console's Start, the Steps
   * page; the autopilot's start, see gameStartFailed): false, the step throws it on. Anywhere else (a
   * timer, a participant's choice, a computer player's) it used to end that thread, with the error
   * only in the server's output, while the game waited for good: it is reported here instead (see
   * gameFailed), and true, it goes no further. Never throws.
   */
  static boolean stepFailed(GameListener listener, Object step, Throwable failure) {
    String name = stepName(listener, step);
    ScriptRun run = SCRIPT_RUN.get();
    if (run != null) {
      // (the innermost step, when a step's done starts the next one)
      if (run.stepFailure == null) {
        run.step = name;
        run.stepFailure = failure;
      }
      return false;
    }
    try {
      ScriptLoader.humanizeStackTrace(failure);
      Logger.error("The step " + name + " failed", failure);
      gameFailed(listener, name, ScriptBoardSupport.briefError(failure), ScriptBoardSupport.describeError(failure));
    } catch (Throwable e) {
      Logger.error("Could not report the failure of the step " + name, e);
    }
    return true;
  }

  /**
   * The autopilot's waiting room started the game, and the start failed with {@code error}, what
   * processScript answered (groovy/autopilot.groovy's launch calls this right after, on the same
   * thread). Its participants would stay on the countdown for good, with newcomers let in again:
   * reported as for stepFailed, naming the step that threw (or initStep, which the start runs), and
   * the autopilot's session ends. Nothing when the game has started after all (another start came
   * first). Never throws.
   */
  static void gameStartFailed(GameListener listener, String error) {
    ScriptRun failed = FAILED_RUN.get();
    FAILED_RUN.remove();
    try {
      ScriptEngine running = engine;
      if (error == null || listener == null || running == null || listener.engine != running
          || running.get(START_COMMAND) != null) {
        return;
      }
      String step = "initStep";
      String brief = error;
      String details = failed != null && failed.thrown != null ? ScriptBoardSupport.describeError(failed.thrown) : error;
      if (failed != null && failed.stepFailure != null) {
        step = failed.step;
        brief = ScriptBoardSupport.briefError(failed.stepFailure);
        details = ScriptBoardSupport.describeError(failed.stepFailure);
      }
      Logger.warn("The autopilot could not start the game: the step " + step + " failed (" + brief + ")");
      gameFailed(listener, step, brief, details);
    } catch (Throwable e) {
      Logger.error("Could not report that the game failed to start", e);
    }
  }

  /**
   * A step of the engine's game failed where no one would see it (stepFailed, gameStartFailed). It is
   * recorded (the event "stepFailed": step, error), every open console is told as processScript tells
   * of an error ({"error": "Caught error: ...", "source": the step}), so the Live session page shows
   * it, and the autopilot's session ends (endAutopilotSession). A session a researcher runs goes on:
   * they see the error and decide. Nothing for a step of an engine that has been replaced since.
   */
  private static void gameFailed(GameListener listener, String step, String brief, String details) {
    ScriptEngine failedIn = listener == null ? null : listener.engine;
    if (failedIn == null || failedIn != engine) {
      return;
    }
    try {
      LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
      data.put("step", step);
      data.put("error", brief);
      trackEvent("stepFailed", data);
    } catch (Exception e) {
      Logger.error("Could not record the failure of the step " + step, e);
    }
    ObjectNode frame = Json.newObject();
    frame.put("source", step);
    frame.put("error", "Caught error: " + details + "\n");
    toAllAdmins(frame);
    endAutopilotSession(listener, failedIn, step, brief);
  }

  /**
   * The game of the engine's session failed (gameFailed). When it is the autopilot's session, its
   * participants would wait for good and no new session would open: it ends as when its steps don't
   * load (stepsFailedToLoad: participants get the ended screen, the game's timers stop, nothing more
   * is recorded, the session is stopped), and the autopilot pauses, saying where (Autopilot.gameFailed).
   * On the autopilot's thread, under the engine lock, which the failing thread may not wait for: it
   * can hold the autopilot's own lock (groovy/autopilot.groovy), which an engine change can wait for.
   */
  private static void endAutopilotSession(final GameListener listener, final ScriptEngine failedIn, final String step,
                                          final String error) {
    final Long sessionId = instanceIdOf(listener);
    if (sessionId == null || Experiment.TEST_INSTANCE_ID.equals(sessionId)) {
      return;
    }
    Autopilot.later(new Runnable() {
      public void run() {
        synchronized (REBUILD_LOCK) {
          // Still that session, in that engine? (Someone may have stopped or replaced it meanwhile.)
          if (engine != failedIn || engineGameListener != listener || !sessionId.equals(instanceIdOf(listener))) {
            return;
          }
          ExperimentInstance live = Autopilot.liveEngineSession();
          if (live == null || !sessionId.equals(live.id) || !Autopilot.gameFailed(live, step, error)) {
            return;
          }
          stopSession(listener);
          endParticipants(sessionId);
          cancelEngineTimers();
          eventTracker.disable();
          // The console tabs of the account running it show it stopped, as after Stop.
          User owner = listener.user == null ? null : User.findByEmail(listener.user.email);
          if (owner != null) {
            toAccount(owner.email, owner.toJson());
          }
        }
      }
    });
  }

  /**
   * What to call a step: its name (stepFactory.createStep("RoundStep")), else the variable the
   * experiment keeps it in ("finishStep" for finishStep = stepFactory.createNoUserActionStep()).
   */
  static String stepName(GameListener listener, Object step) {
    try {
      Object name = step instanceof groovy.lang.GroovyObject ? ((groovy.lang.GroovyObject) step).getProperty("name") : null;
      if (name != null && !name.toString().trim().isEmpty()) {
        return name.toString();
      }
      ScriptEngine in = listener == null ? null : listener.engine;
      if (in != null && step != null) {
        for (Map.Entry<String, Object> binding : in.getBindings(ScriptContext.ENGINE_SCOPE).entrySet()) {
          if (binding.getValue() == step) {
            return binding.getKey();
          }
        }
      }
    } catch (Exception e) {
      // (the experiment's code may change the variables while they are read) named as below
    }
    return "Step";
  }

  /**
   * A researcher adding or cutting a tie from the admin network view, through
   * edgeEdits.adminEdit (groovy/edge_edits.groovy), which logs it. The admin is told the outcome as
   * {"tieEdit": {ok, op, a, b, reason}}; the tie itself reaches it with the next graph update.
   */
  public static void editTie(String op, String a, String b, ThrottledWebSocketOut out) {
    ObjectNode result = Json.newObject();
    result.put("op", op);
    result.put("a", a);
    result.put("b", b);
    String reason = null;
    if (!"add".equals(op) && !"remove".equals(op)) {
      reason = "Unknown edit \"" + op + "\"";
    } else if (engine == null || !(engine.get("edgeEdits") instanceof groovy.lang.GroovyObject)) {
      reason = "The experiment's engine isn't loaded yet.";
    } else {
      try {
        ((groovy.lang.GroovyObject) engine.get("edgeEdits")).invokeMethod("adminEdit", new Object[]{op, a, b});
      } catch (Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
          root = root.getCause();
        }
        reason = root.getMessage() != null ? root.getMessage() : root.toString();
      }
    }
    result.put("ok", reason == null);
    if (reason != null) {
      result.put("reason", reason);
    }
    ObjectNode message = Json.newObject();
    message.put("tieEdit", result);
    if (out != null) {
      out.write(message);
    }
  }

  private class UserDataChangeListener implements PropertyChangeListener {

    private ExperimentInstance experimentInstance;

    private UserDataChangeListener(ExperimentInstance experimentInstance) {
      this.experimentInstance = experimentInstance;
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
      List<Data> dataList = experimentInstance.data;
      Data foundData = null;
      if (evt.getPropertyName() != null) {
        foundData = findDataByName(evt.getPropertyName());
      }
      if (evt instanceof ObservableMap.PropertyAddedEvent) {
        Data newData = foundData == null ? new Data() : foundData;
        newData.name = evt.getPropertyName();
        newData.value = evt.getNewValue().toString();
        newData.experimentInstance = experimentInstance;
        if (foundData == null) {
          dataList.add(newData);
          experimentInstance.save();
        } else {
          newData.save();
        }
        initParam(newData, null);

      } else if (evt instanceof ObservableMap.PropertyClearedEvent) {
        List<Parameter> parameters = experimentInstance.experiment.getParameters();
        List<String> parameterNames = new ArrayList<String>();
        for (Parameter parameter : parameters) {
          parameterNames.add(parameter.name);
        }
        Iterator<Data> dataIterator = dataList.iterator();
        while (dataIterator.hasNext()) {
          Data d = dataIterator.next();
          //make sure not removing the parameters
          if (!parameterNames.contains(d.name)) {
            dataIterator.remove();
            engine.getBindings(ScriptContext.ENGINE_SCOPE).remove(d.name);
          }
        }
        experimentInstance.save();

      } else if (evt instanceof ObservableMap.PropertyRemovedEvent) {
        if (foundData != null) {
          dataList.remove(foundData);
          engine.getBindings(ScriptContext.ENGINE_SCOPE).remove(foundData.name);
        }
        experimentInstance.save();
      } else if (evt instanceof ObservableMap.PropertyUpdatedEvent) {
        // (This saved and re-bound foundData even when it was null, throwing into the script.)
        if (foundData != null) {
          foundData.value = String.valueOf(evt.getNewValue());
          foundData.save();
          initParam(foundData, null);
        }
      }
    }

    private Data findDataByName(String name) {
      List<Data> dataList = experimentInstance.data;
      for (Data d : dataList) {
        //this should be case-sensitive
        if (d.name.equals(name)) {
          return d;
        }
      }
      return null;
    }
  }
}
