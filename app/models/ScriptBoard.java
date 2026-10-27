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
  private static EventBus<Map> eventBus = new EventBus();

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
    player.put("text", "<h3>This session has ended</h3><p>Thank you for taking part. You can close this page.</p>");
    return frame;
  }

  /** What a page of a session the engine doesn't run (now) is sent instead of joining a game. */
  static ObjectNode sessionNotRunningScreen() {
    ObjectNode frame = Json.newObject();
    ObjectNode player = frame.putObject("player");
    player.putObject("_system");
    player.put("text", "<h3>This session isn't running</h3><p>It may have ended, or it hasn't been opened yet. "
        + "Please check the link you were given, or close this page.</p>");
    return frame;
  }

  /**
   * Whether the engine runs this session of this experiment now, so newcomers may join it: its own
   * session while that is RUNNING or TESTING, or the experiment's test session while the engine has
   * that experiment loaded. {@code instance} is a freshly loaded copy (its status is current).
   */
  public static boolean runsSession(Long experimentId, ExperimentInstance instance) {
    if (experimentId == null || instance == null || instance.id == null) {
      return false;
    }
    if (instance.isTestInstance()) {
      return engine != null && experimentId.equals(engineExperimentId);
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

  private void resetEngine(Experiment experiment) throws IOException, ScriptException {
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

  private void loadSteps(Experiment experiment, ThrottledWebSocketOut out) {
    if (experiment == null) {
      return;
    }
    for (Step step : experiment.getSteps()) {
      //should call RunStep?
      processScript(step.source + ";null;", out, step.name);
    }
    afterStepsLoaded(experiment);
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

  private void rebuildScriptBoard(Experiment experiment) throws IOException, ScriptException {
    //init();
    resetEngine(experiment);
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

  /** How many console sockets are open. */
  public static int adminCount() {
    return admins.size();
  }

  static boolean hasAdmins() {
    return !admins.isEmpty();
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

  public static void addClient(String experimentIdString, String experimentInstanceIdString, String clientId, final WebSocket.In<JsonNode> in, final ThrottledWebSocketOut out) throws Exception {
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

      if (client == null && experimentInstance.status != null && !experimentInstance.status.isRunnable()) {
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

      if (client == null && experimentInstance.hasStarted) {
        Logger.debug("New client trying to join after game has already started.");
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
        Breadboard.replyError(request.out, Breadboard.requestName(request),
            "Something went wrong: " + ScriptBoardSupport.describeError(e));
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
          Breadboard.replyError(breadboardMessage.out, Breadboard.requestName(breadboardMessage), refusal);
        }
        return;
      }
    }
    if (message instanceof Breadboard.AddAdmin) {
      Breadboard.AddAdmin addAdmin = (Breadboard.AddAdmin) message;
      // This socket's own Admin (one per socket), then this socket's own copy of the live network.
      Admin admin = registerAdmin(addAdmin.user, addAdmin.scriptBoardController, addAdmin.out);
      replayGraph(admin);
    } else if (message instanceof Breadboard.RunGame) {
      if (breadboardMessage.user != null) {
        // Run Game
        processScript("initStep.start()", breadboardMessage.out, null);
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
        // Save script in database
        breadboardMessage.user.currentScript = sendScript.script;
        breadboardMessage.user.update();

        // Process script
        processScript(sendScript.script, breadboardMessage.out, null);
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
        // Now we only run the step
        getSelf().tell(new Breadboard.RunStep(breadboardMessage.user, sendStep.source, breadboardMessage.out), null);
        // A re-run step may set the autopilot up itself; the console's settings apply again after it.
        getSelf().tell(new Breadboard.StepsLoaded(breadboardMessage.user, engineExperiment(), breadboardMessage.out), null);
      } else {
        Breadboard.replyError(breadboardMessage.out, "SendStep", "Select an experiment first.");
      }
    } else if (message instanceof Breadboard.RunStep) {
      // TODO: Compile step here and make available to scripting engine
      Breadboard.RunStep runStep = (Breadboard.RunStep) message;
      processScript(runStep.source, breadboardMessage.out, null);
    } else if (message instanceof Breadboard.StepsLoaded) {
      afterStepsLoaded(((Breadboard.StepsLoaded) message).experiment);
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
        // Any other session the engine runs ends with the rebuild (its network is emptied). It is
        // marked stopped: left running, its pages reconnected into this experiment's game, and the
        // Sessions list and the /play links still showed it as open.
        GameListener previous = engineGameListener;
        if (previous != null && (resumed == null || !resumed.id.equals(instanceIdOf(previous)))) {
          stopSession(previous);
        }

        rebuildScriptBoard(experiment);
        loadSteps(experiment, breadboardMessage.out);

        //reset the instance
        if (resumed != null) {
          eventTracker.setExperimentInstance(resumed);
          gameListener.experimentInstance = resumed;
          for (Data param : resumed.data) {
            initParam(param, changeExperiment.experiment);
          }
        } else {
          // The engine runs no session now; events no longer go to the one it ran before.
          gameListener.experimentInstance = null;
          eventTracker.setExperimentInstance(null);
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
      // The session the engine is running, whichever account started it.
      GameListener running = engineGameListener != null ? engineGameListener : gameListener;
      if (instance.id.equals(instanceIdOf(running))) {
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
        stopSession(running);
        // A researcher stopping the autopilot's session pauses it (no new one opens behind their back).
        Autopilot.sessionStopped(instance, breadboardMessage.user);
      } else {
        // (This used to change the status in memory only.)
        stopQuietly(instance.id);
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
        processScript("onJoinStep.start(" + groovyString(player.getId().toString()) + ")", runOnJoinStep.out, null);
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
          processScript("onLeaveStep.start(" + groovyString(player.getId().toString()) + ")", runOnLeaveStep.out, null);
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
      // A session another account runs ends with the rebuild: marked stopped, as above. This
      // account's own session carries on in the new engine, as before.
      GameListener previous = engineGameListener;
      if (previous != null && previous != gameListener) {
        stopSession(previous);
      }
      rebuildScriptBoard(selectedExperiment);
      loadSteps(selectedExperiment, breadboardMessage.out);
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

  // A researcher's action that runs in the engine's game (not RunStep, StepsLoaded or the join and
  // leave steps, which the engine's own actor queues).
  private static boolean actsOnTheGame(Breadboard.BreadboardMessage message) {
    return message instanceof Breadboard.RunGame || message instanceof Breadboard.SendStep
        || message instanceof Breadboard.SendScript || message instanceof Breadboard.MakeChoice
        || message instanceof Breadboard.EditTie || message instanceof Breadboard.HitCreated;
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
   * {@code experimentName}, {@code sessionId}, {@code sessionName} (its live session, if any) and
   * {@code owner} (the account running it); null values when unknown. An account's own selection
   * can differ, and its engine actions are refused then (see engineRefusal).
   */
  static ObjectNode engineJson() {
    ObjectNode json = Json.newObject();
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
    Experiment experiment = user.selectedExperiment;
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

    // The engine's current session ends (the account that started it may be another one).
    GameListener previous = engineGameListener;
    rebuildScriptBoard(experiment);
    if (previous != gameListener) {
      stopSession(previous);
    }
    ExperimentInstance instance = startGame(launchGame.name, user.experimentInstanceId, parameters, experiment, user);

    instanceData.addPropertyChangeListener(new UserDataChangeListener(instance));

    eventTracker.enable();
    eventTracker.setExperimentInstance(instance);
    gameListener.experimentInstance = instance;
    gameListener.start();

    // Re-run the Steps, then the autopilot settings over them
    for (Step step : instance.experiment.getSteps())
      getSelf().tell(new Breadboard.RunStep(user, step.source, launchGame.out), null);
    getSelf().tell(new Breadboard.StepsLoaded(user, experiment, launchGame.out), null);

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

    // The engine's current session ends (the account that started it may be another one).
    GameListener previous = engineGameListener;
    rebuildScriptBoard(experiment);
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

      instanceData.addPropertyChangeListener(new UserDataChangeListener(instance));
    } else {
      // A finished session is shown, not restarted: the engine runs no session now.
      gameListener.experimentInstance = null;
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
      for (Experiment e : requester.ownedExperiments) {
        if (deleteExperiment.experimentId.equals(e.id)) {
          toBeDeleteExperiment = e;
        }
      }
    } else if (deleteExperiment.experimentName != null) {
      toBeDeleteExperiment = requester.getExperimentByName(deleteExperiment.experimentName);
    }
    if (toBeDeleteExperiment == null) {
      Breadboard.replyError(deleteExperiment.out, "DeleteExperiment", "None of your experiments is "
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
  }

  private ExperimentInstance startGame(String gameName, long currentExperimentInstanceId, LinkedHashMap parameters, Experiment selectedExperiment, User user) {
    ExperimentInstance instance = new ExperimentInstance(gameName, selectedExperiment);
    instance.hasStarted = false;
    instance.status = ExperimentInstance.Status.STOPPED;

    if (currentExperimentInstanceId != -1) {
      ExperimentInstance runningInstance = ExperimentInstance.findById(currentExperimentInstanceId);
      if (runningInstance != null && runningInstance.status.isRunnable()) {
        runningInstance.status = ExperimentInstance.Status.STOPPED;
        runningInstance.update();
      }
    }

    initAllParam(parameters, selectedExperiment, instance);

    selectedExperiment.instances.add(instance);
    selectedExperiment.save();

    user.setExperimentInstanceId(instance.id);
    try {
      user.update();
    } catch (javax.persistence.OptimisticLockException changedMeanwhile) {
      // The account changed since this copy was loaded (a sign-in, another tab, the autopilot
      // selecting the experiment): Ebean refuses a stale copy, which used to abort the launch after
      // the session was created. Record it on a fresh copy instead.
      User fresh = User.findByEmail(user.email);
      if (fresh == null) {
        throw changedMeanwhile;
      }
      fresh.setExperimentInstanceId(instance.id);
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
        if (eventTracker != null) {
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

  private static void makeChoice(String clientId, String uid, String params, ThrottledWebSocketOut out) {
    ObjectNode jsonOutput = Json.newObject();
    try {
      // A choice uid is only valid for the participant it was shown to. clientId is null when an
      // admin makes the choice on a player's behalf from the admin panel.
      String owner = (playerActions == null || uid == null) ? null : playerActions.ownerOf(uid);
      if (clientId != null && (owner == null || !owner.equals(clientId))) {
        Logger.info("Ignored choice " + uid + " from " + clientId + ": not offered to this participant");
        out.write(jsonOutput);
        return;
      }
      playerActions.choose(uid, params);
    } catch (Exception e) {
      ScriptLoader.humanizeStackTrace(e);
      Logger.error("Failed to make choice " + uid, e);
      jsonOutput.put("error", "Caught error: " + ScriptBoardSupport.describeError(e) + "\n");
    }
    out.write(jsonOutput);
  }

  public static void processScript(String script, ThrottledWebSocketOut out, String scriptName) {
    if (scriptName == null) scriptName = "Unnamed Script";

    ObjectNode jsonOutput = Json.newObject();
    if (engine == null) {
      // Nothing has built an engine yet (no experiment selected since the server started).
      jsonOutput.put("error", "Caught error: The script engine isn't running yet. Select an experiment first.\n");
      if (out != null) {
        out.write(jsonOutput);
      }
      return;
    }
    //TODO: better way to handle this?
    boolean initStep = false;
    synchronized (ScriptBoard.class) {
      if (script.contains("initStep.start()")) {
        if (engine.get("initStep.start()") != null) {
          Logger.debug("engine.get(\"initStep.start()\") = " + engine.get("initStep.start()").toString());
          Logger.warn("The initStep has started already.");
          jsonOutput.put("error", "Caught error: ".concat("initStep started already").concat("\n"));
          if (out != null) {
            out.write(jsonOutput);
          }
          return;
        }
        engine.put("initStep.start()", true);
        initStep = true;
        // Close the game to newcomers once it starts, however it was started. Only the AMT timer
        // used to do this, so with a manual start late joiners were admitted mid-game.
        if (!Play.application().configuration().getBoolean("breadboard.allowLateJoin", false)) {
          Object listener = engine.get("gameListener");
          if (listener instanceof GameListener) {
            ((GameListener) listener).hasStarted();
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
    try {
      Object outputObject = engine.eval(script);
      // Clear out error
      jsonOutput.put("error", "");

      // Write out the results Map as JSON
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

      mapper.writeValue(outputStream, engine.getBindings(ScriptContext.ENGINE_SCOPE).get("results"));
      String outputString = script.trim().concat("\n\n==>");

      if (outputObject != null) {
        if (outputObject instanceof GremlinGroovyPipeline) {
          outputString += StringUtils.join(((GremlinGroovyPipeline) outputObject).toList(), "\n==>");
        } else {
          outputString += outputObject.toString();
        }
      }

      jsonOutput.put("output", outputString.trim());
    } catch (CompilationFailedException cfe) {
      // Translate Groovy's opaque "Script<N>.groovy" names back to real file names in both the
      // logged stack trace and the error sent to the browser (see ScriptLoader.humanize*).
      ScriptLoader.humanizeStackTrace(cfe);
      Logger.error("Unable to compile the script. " + scriptName, cfe);
      jsonOutput.put("error", "Caught error: " + ScriptBoardSupport.describeError(cfe) + "\n");
      if (initStep) {
        engine.put("initStep.start()", null);
      }
    } catch (ScriptException se) {
      ScriptLoader.humanizeStackTrace(se);
      Logger.error("Script Error. " + scriptName, se);
      jsonOutput.put("error", "Caught error: " + ScriptBoardSupport.describeError(se) + "\n");
      if (initStep) {
        engine.put("initStep.start()", null);
      }
    } catch (Exception e) {
      ScriptLoader.humanizeStackTrace(e);
      Logger.error("Failed to process the script. " + scriptName, e);
      jsonOutput.put("error", "Caught error: " + ScriptBoardSupport.describeError(e) + "\n");
      if (initStep) {
        engine.put("initStep.start()", null);
      }
    } finally {
      if (out != null) {
        out.write(jsonOutput);
      }
    }
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
