package models;

import akka.actor.*;
import com.avaje.ebean.Ebean;
import com.avaje.ebean.SqlRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Graph;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.gremlin.groovy.GremlinGroovyPipeline;
import groovy.lang.GroovyObject;
import groovy.lang.MissingMethodException;
import groovy.util.ObservableMap;
import groovy.lang.Closure;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.groovy.control.CompilationFailedException;
import play.Logger;
import play.Play;
import play.libs.Akka;
import play.libs.F.Callback;
import play.libs.F.Callback0;
import play.libs.Json;
import play.mvc.WebSocket;
import scala.concurrent.duration.Duration;

import javax.script.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
  // A page that pings (pages do every few seconds from 3.1 on) and then sends nothing for this long has
  // lost its connection, though its socket never closed (see closeSilentPages). Checked this often.
  static volatile long silenceLimitMs = 30000L;
  private static final long SILENCE_CHECK_SECONDS = 5L;
  // When a page was heard from (ms); only tests set another clock.
  static volatile java.util.function.LongSupplier clock = System::currentTimeMillis;
  private static final AtomicBoolean silenceWatched = new AtomicBoolean();
  // Pages given their final screen as they connected, whose sockets are still open (see finalScreen).
  private static final Set<Client.Connection> endedPages = java.util.concurrent.ConcurrentHashMap.newKeySet();
  // Sessions whose end is under way while their status stays as it is: the server's stop
  // (ServerStop), which leaves it to the next start. A page that connects meanwhile is given its end
  // screen, as for a session that is over, not a seat in the game that ends with the server: what
  // each participant's end screen is (the function; for someone who has no Client in the game, it is
  // given one that stands for them, see endedSessionScreen). Kept until the process ends.
  private static final Map<Long, java.util.function.Function<Client, ObjectNode>> endingSessions =
      new java.util.concurrent.ConcurrentHashMap<>();
  // Final screens of the participants of the last few sessions, by instance id and client id, for
  // pages that reconnect after their session has ended (see addClient). A rebuild of the engine that
  // carries a session on keeps only ends there (see keptAtRebuild).
  private static final int ENDED_SESSIONS_KEPT = 10;
  private static final Map<Long, Map<String, ObjectNode>> endedSessionScreens = Collections.synchronizedMap(
      new LinkedHashMap<Long, Map<String, ObjectNode>>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Map<String, ObjectNode>> eldest) {
          if (size() <= ENDED_SESSIONS_KEPT) {
            return false;
          }
          releaseSeatsIfOver(eldest.getKey());
          return true;
        }
      });
  // How each of the last few sessions that endParticipants ended did so: the end screen each of its
  // participants was given, which endedSessionScreen gives a page that comes back to it with no last
  // screen remembered. That is someone its game didn't have as it ended: away when a rebuild of the
  // engine carried the session on (which remembers no game views), and not back before the end.
  // Forgotten when the session runs again (Resume).
  private static final Map<Long, java.util.function.Function<Client, ObjectNode>> sessionEnds = Collections.synchronizedMap(
      new LinkedHashMap<Long, java.util.function.Function<Client, ObjectNode>>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, java.util.function.Function<Client, ObjectNode>> eldest) {
          return size() > ENDED_SESSIONS_KEPT;
        }
      });

  // For each recent real session, the participants whose own page has held their seat in it
  // (Client.ownPageHeld): read from its data (clientConnected without preview) the first time it is
  // needed, kept up to date after. A Client lasts only as long as the engine it joined, and every
  // rebuild drops them all: after a Resume, or a reload that carries the session on, a preview of a
  // former participant made a new one and counted as them being there again. As many sessions as
  // there are last screens: forgetting one only means reading its data again.
  private static final Map<Long, Set<String>> ownPagesHeld = Collections.synchronizedMap(
      new LinkedHashMap<Long, Set<String>>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Set<String>> eldest) {
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
  // When that game started (markGameStarted), for the autopilot's longest session (gameStartedAt).
  private static volatile long gameStartedAt = 0L;

  /**
   * The script or step a researcher's action runs on this thread (SendScript, SendStep, RunGame),
   * and the start it has marked itself, if any (see stepStarting).
   */
  private static final class ResearcherRun {
    ScriptEngine startPending;
  }
  private static final ThreadLocal<ResearcherRun> RESEARCHER_RUN = new ThreadLocal<ResearcherRun>();

  /**
   * A script processScript runs on this thread (the innermost, when one script runs another), while it
   * runs. A step that throws in it is left to processScript, which tells whoever ran the script (see
   * stepFailed); the first one is kept, to name it. So is whether the game's start was taken back
   * while it ran (startFailed).
   */
  private static final class ScriptRun {
    String step;
    Throwable stepFailure;
    boolean startTakenBack;
  }
  private static final ThreadLocal<ScriptRun> SCRIPT_RUN = new ThreadLocal<ScriptRun>();

  /** How a script processScript ran failed, in words (see FAILED_RUN). */
  private static final class ScriptFailure {
    // what processScript answered (ScriptBoardSupport.briefError of what the script threw)
    final String answer;
    // the step whose code threw, when the script failed with that; else null
    final String step;
    // what went wrong there (in that step, else in the script): in one line (briefError), and in
    // full, with the lines it ran through (describeError)
    final String brief;
    final String details;
    // The game's start was taken back as it failed (startFailed): the game stalls, since nothing
    // starts it again by itself (the waiting room's start is spent once a start is made, and the
    // init step that threw stays as it is). E.g. an OnJoinStep that starts the game once enough
    // people are in, whose InitStep has a typo.
    final boolean stalled;
    // The engine it ran in had been replaced by the time it ended: not the running game's failure.
    final boolean replaced;

    ScriptFailure(String answer, String step, String brief, String details, boolean stalled, boolean replaced) {
      this.answer = answer;
      this.step = step;
      this.brief = brief;
      this.details = details;
      this.stalled = stalled;
      this.replaced = replaced;
    }
  }
  // How the last script processScript ran on this thread failed (nothing when it ran), for whoever
  // ran it and waits on nobody: the autopilot's start (gameStartFailed, called right after on the same
  // thread) and OnJoinStep / OnLeaveStep (joinOrLeaveStep). Words only, worked out as it fails: kept
  // as an exception, it held on to the script's class (and so the engine it ran in, after a reload)
  // and a missing method's arguments (e.g. a player, and through it the graph) until the next script
  // ran on that thread, if ever.
  private static final ThreadLocal<ScriptFailure> FAILED_RUN = new ThreadLocal<ScriptFailure>();
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
    rememberLastScreens(keptAtRebuild(clients.values()));
    clients.clear();
  }

  /**
   * The Clients whose last screens a rebuild of the engine remembers: everyone's in a session that is
   * over (the screen its game left them with), but in one that carries on in the new engine (it is
   * still live: a Reload engine of the researcher's own session, the running session selected again)
   * only an end of theirs (endScreen: a completion code, the experiment's last screen, the screen of
   * someone the game started without). Not their game view: whoever comes back while the session
   * runs joins it again, and once it has ended, a page with no last screen remembered gets the end
   * its participants were given (sessionEnds), or the plain end of a game that finished by itself.
   * The game view was remembered, and a page away during the rebuild that came back after the end
   * was given it: a game that looked live, whose buttons did nothing, for as long as it stayed open.
   */
  static List<Client> keptAtRebuild(Collection<Client> sessionClients) {
    List<Client> kept = new ArrayList<Client>();
    Map<Long, Boolean> carriesOn = new HashMap<Long, Boolean>();
    for (Client client : sessionClients) {
      Long instanceId = client.experimentInstance == null ? null : client.experimentInstance.id;
      if (instanceId == null || endScreen(client.lastFrame)) {
        kept.add(client);
        continue;
      }
      Boolean live = carriesOn.get(instanceId);
      if (live == null) {
        live = liveNow(instanceId);
        carriesOn.put(instanceId, live);
      }
      if (!live) {
        kept.add(client);
      }
    }
    return kept;
  }

  // Whether the database has this session live (RUNNING or TESTING); false when it can't be told.
  private static boolean liveNow(Long instanceId) {
    try {
      ExperimentInstance fresh = ExperimentInstance.findById(instanceId);
      return fresh != null && (fresh.status == ExperimentInstance.Status.RUNNING
          || fresh.status == ExperimentInstance.Status.TESTING);
    } catch (Exception e) {
      Logger.warn("Could not tell whether session " + instanceId + " carries on: " + e);
      return false;
    }
  }

  static void rememberLastScreens(Collection<Client> sessionClients) {
    for (Client client : sessionClients) {
      if (client.lastFrame == null || client.experimentInstance == null || client.experimentInstance.id == null) {
        continue;
      }
      screensOf(client.experimentInstance.id).put(client.id, client.lastFrame);
    }
  }

  // The last screens remembered of this session (endedSessionScreens), none yet in a new one.
  private static Map<String, ObjectNode> screensOf(Long instanceId) {
    synchronized (endedSessionScreens) {
      Map<String, ObjectNode> screens = endedSessionScreens.get(instanceId);
      if (screens == null) {
        screens = new java.util.concurrent.ConcurrentHashMap<>();
        endedSessionScreens.put(instanceId, screens);
      }
      return screens;
    }
  }

  /**
   * The session ended (endParticipants), each participant with the end screen {@code screenFor}
   * gives them: also the end of those its game no longer has (sessionEnds). It counts among the last
   * few sessions that ended (endedSessionScreens, with whose screens its seats go), whether or not
   * anyone's own screen is remembered.
   */
  private static void sessionEnded(Long instanceId, java.util.function.Function<Client, ObjectNode> screenFor) {
    sessionEnds.put(instanceId, screenFor);
    screensOf(instanceId);
  }

  /**
   * The session runs again (Resume): how it ended before no longer stands for those who don't come
   * back. (Their own ends remembered meanwhile, a completion code, still do.)
   */
  static void sessionRunsAgain(Long instanceId) {
    if (instanceId != null) {
      sessionEnds.remove(instanceId);
    }
  }

  /**
   * A session whose last screens are forgotten: its seat tokens go too (they used to be kept for
   * good), unless it can still be resumed. Someone coming back to it then gets the general "isn't
   * running" page, as they would without a last screen. A stopped session can be resumed, and takes
   * back those who held a seat by their tokens: those stay until it is deleted
   * (ExperimentInstance.delete), or its screens are forgotten again once it has finished.
   */
  private static void releaseSeatsIfOver(Long instanceId) {
    try {
      ExperimentInstance session = ExperimentInstance.findById(instanceId);
      if (session == null || session.status == null || !session.status.isRunnable()) {
        security.ParticipantAuth.releaseInstance(String.valueOf(instanceId));
      }
    } catch (Exception e) {
      // (its seats stay, as they always used to)
      Logger.warn("Could not tell whether session " + instanceId + " can still be resumed: " + e);
    }
  }

  /**
   * Whether {@code clientId}'s own page has held their seat in this real session before (see
   * ownPagesHeld). Never for the test session, whose players are played through previews.
   */
  static boolean ownPageHeldBefore(ExperimentInstance session, String clientId) {
    Set<String> held = ownPagesOf(session);
    return held != null && held.contains(clientId);
  }

  /** Their own page holds their seat in this session now (see ownPagesHeld). */
  static void ownPageHolds(ExperimentInstance session, String clientId) {
    Set<String> held = ownPagesOf(session);
    if (held != null) {
      held.add(clientId);
    }
  }

  // The session's own pages (ownPagesHeld), read from its data when it isn't known yet; null for the
  // test session, which isn't recorded.
  private static Set<String> ownPagesOf(ExperimentInstance session) {
    if (session == null || session.id == null || session.isTestInstance()) {
      return null;
    }
    synchronized (ownPagesHeld) {
      Set<String> held = ownPagesHeld.get(session.id);
      if (held == null) {
        held = java.util.concurrent.ConcurrentHashMap.newKeySet();
        held.addAll(ownPagesRecorded(session.id));
        ownPagesHeld.put(session.id, held);
      }
      return held;
    }
  }

  // Who the session's data says was connected by their own page (clientConnected without preview).
  private static List<String> ownPagesRecorded(Long sessionId) {
    List<String> ids = new ArrayList<String>();
    try {
      List<SqlRow> rows = Ebean.createSqlQuery("select distinct who.value as client_id from events conn"
          + " join event_data who on who.event_id = conn.id"
          + " where conn.experiment_instance_id = :session and conn.name = 'clientConnected' and who.name = 'clientId'"
          + " and not exists (select 1 from event_data p where p.event_id = conn.id and p.name = 'preview' and p.value = 'true')")
          .setParameter("session", sessionId)
          .findList();
      for (SqlRow row : rows) {
        String id = row.getString("client_id");
        if (id != null) {
          ids.add(id);
        }
      }
    } catch (RuntimeException e) {
      // (only those seen from now on, then)
      Logger.warn("Could not read who has been in session " + sessionId + ": " + e);
    }
    return ids;
  }

  /**
   * What a page that reconnects to a session that has ended (or whose end is under way: the server's
   * stop) is sent: its last screen, if known; else the end screen its participants were given, for
   * someone its game didn't have as it ended (endingSessions, sessionEnds); else the plain end, e.g.
   * of a game that finished by itself.
   */
  static ObjectNode endedSessionScreen(Long instanceId, String clientId) {
    Map<String, ObjectNode> screens = instanceId == null ? null : endedSessionScreens.get(instanceId);
    ObjectNode last = screens == null || clientId == null ? null : screens.get(clientId);
    if (last != null) {
      return last;
    }
    java.util.function.Function<Client, ObjectNode> ending = instanceId == null ? null : endingSessions.get(instanceId);
    if (ending == null && instanceId != null) {
      ending = sessionEnds.get(instanceId);
    }
    if (ending != null) {
      // Someone away when a rebuild of the engine carried the session on, and not back before its
      // end, or who was never in it: what its participants were told, for this id (e.g. in the words
      // for Prolific to someone who came from there), through a Client that stands for them.
      return ending.apply(new Client(clientId, null));
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
   * no longer answers), and keep saying so if they reconnect. See endParticipants(Long, ObjectNode).
   */
  static void endParticipants(Long instanceId) {
    endParticipants(instanceId, stoppedScreen());
  }

  /** What the participants of a session a researcher stopped see. */
  static ObjectNode stoppedScreen() {
    ObjectNode frame = Json.newObject();
    ObjectNode player = frame.putObject("player");
    player.putObject("_system");
    player.put("finished", true);
    player.put("ended", "stopped");
    player.put("text", "<h3>This session has been stopped</h3><p>The researcher stopped this session. "
        + "If they start it again, open the link you were given once more. Otherwise, thank you for "
        + "taking part: you can close this page.</p>");
    return frame;
  }

  private static boolean hasPlayer(String clientId) {
    try {
      return engine != null && playerVertex(engine.get("g"), clientId) != null;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * The player's vertex in this graph, or null. The engine's graph is asked under the lock it holds
   * while players are added and removed (getPlayer in groovy/graph.groovy): TinkerGraph keeps its
   * vertices in a plain HashMap, and a lookup while another participant joining resized it could
   * miss a vertex that is there. Other graphs (tests' own) are asked directly. As it waits for that
   * lock, never call it while holding a Client's monitor (the lock order is written at structureLock).
   */
  static Vertex playerVertex(Object g, String clientId) {
    if (clientId == null) {
      return null;
    }
    if (g instanceof GroovyObject) {
      try {
        Object v = ((GroovyObject) g).invokeMethod("getPlayer", clientId);
        return v instanceof Vertex ? (Vertex) v : null;
      } catch (MissingMethodException noGetPlayer) {
        // (a graph of another class: asked directly below)
      }
    }
    return g instanceof Graph ? ((Graph) g).getVertex(clientId) : null;
  }

  /** What someone gets whose session started while they were away, without them (see addClient). */
  static ObjectNode missedStartScreen(boolean prolific) {
    ObjectNode frame = Json.newObject();
    ObjectNode player = frame.putObject("player");
    player.putObject("_system");
    player.put("finished", true);
    player.put("ended", "missedStart");
    player.put("text", "<h3>The session started without you</h3><p>The session started while your connection was "
        + "lost, so it went ahead without you, and it can't take you in any more. "
        + (prolific ? "Please return the study on Prolific. You can then close this page."
            : "You can close this page. If you have any questions, please contact the researcher.") + "</p>");
    return frame;
  }

  /**
   * Whether a screen is the participant's own end, which a later notice must not replace: the
   * experiment finished them (player.finished, without the server's "ended"), or gave them a completion
   * code (_system.recruitment.completed). As the participant page tells them apart.
   */
  static boolean ownEnd(JsonNode frame) {
    JsonNode player = frame == null ? null : frame.get("player");
    if (player == null || !player.isObject() || player.hasNonNull("ended")) {
      return false;
    }
    return player.path("finished").asBoolean(false)
        || player.path("_system").path("recruitment").path("completed").asBoolean(false);
  }

  /**
   * Whether a screen is an end, which a page that comes back is given as it is: the participant's own
   * (ownEnd), or one the server gave them (player.ended: the session was stopped, started without
   * them, ...). Not a game view, which only the game that showed it can answer.
   */
  static boolean endScreen(JsonNode frame) {
    return ownEnd(frame) || (frame != null && frame.path("player").hasNonNull("ended"));
  }

  /**
   * Whether this participant came through a Prolific link: their last screen says so
   * (_system.recruitment), or their player in the game does (their last screen may not have caught up
   * with it), or the session recorded their entry (prolificEntry; not asked when {@code instanceId}
   * is null).
   */
  private static boolean fromProlific(Client client, Long instanceId) {
    ObjectNode last = client.lastFrame;
    if (last != null && "prolific".equals(last.path("player").path("_system").path("recruitment").path("source").asText())) {
      return true;
    }
    try {
      Vertex v = engine == null ? null : playerVertex(engine.get("g"), client.id);
      Object system = v == null ? null : v.getProperty("_system");
      Object recruitment = system instanceof Map ? ((Map<?, ?>) system).get("recruitment") : null;
      if (recruitment instanceof Map && "prolific".equals(String.valueOf(((Map<?, ?>) recruitment).get("source")))) {
        return true;
      }
    } catch (Exception e) {
      // (the database may still say)
    }
    if (instanceId == null) {
      return false;
    }
    try {
      SqlRow entries = Ebean.createSqlQuery("select count(*) as n from events e join event_data d on d.event_id = e.id"
          + " where e.experiment_instance_id = :session and e.name = 'prolificEntry' and d.name = 'clientId' and d.value = :client")
          .setParameter("session", instanceId)
          .setParameter("client", client.id)
          .findUnique();
      Integer n = entries == null ? null : entries.getInteger("n");
      return n != null && n > 0;
    } catch (RuntimeException e) {
      Logger.warn("Could not tell whether " + client.id + " came from Prolific: " + e);
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

  /**
   * What a researcher's preview of someone who isn't in the game of a real session is sent (see
   * addClient): nothing is there to show, and the preview adds nobody.
   */
  static ObjectNode notInGameScreen(String clientId) {
    ObjectNode frame = Json.newObject();
    ObjectNode player = frame.putObject("player");
    player.putObject("_system");
    player.put("finished", true);
    player.put("ended", "notInGame");
    player.put("text", "<h3>Not in the game</h3><p>" + org.apache.commons.lang3.StringEscapeUtils.escapeHtml4(clientId)
        + " isn't in this session's game right now, so there is nothing to show. A preview adds nobody to the game: "
        + "participants join from their own page. Reload this preview once they have joined.</p>");
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
   * that experiment loaded and runs no real session; not once the server's stop is ending it (its
   * status stays RUNNING until the next start). {@code instance} is a freshly loaded copy (its
   * status is current).
   */
  public static boolean runsSession(Long experimentId, ExperimentInstance instance) {
    if (experimentId == null || instance == null || instance.id == null || endingSessions.containsKey(instance.id)) {
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
      // Let go of what kept every replaced engine in memory for good (Groovy's caches of its
      // classes, Jackson's), before the next one is built.
      EngineCleanup.release(engine);
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

  // AutopilotHooks.afterStepsLoaded, for the session the engine runs (they apply only in the
  // autopilot's own); a field only so tests can watch when it runs.
  static volatile java.util.function.BiConsumer<Experiment, ScriptEngine> afterStepsLoadedHook =
      new java.util.function.BiConsumer<Experiment, ScriptEngine>() {
        public void accept(Experiment experiment, ScriptEngine engine) {
          AutopilotHooks.afterStepsLoaded(experiment, engine, instanceIdOf(engineGameListener));
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
   * session, launching, reloading; selecting an experiment leaves a live session alone). When that
   * is the autopilot's live session, the autopilot pauses first, as for Stop, so it doesn't take the
   * engine back behind their back (and its periodic check can't slip in between). {@code continuing}
   * is a session that carries on (re-selected), {@code how} says what the researcher did, with %s
   * for who.
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

  /**
   * A rebuild of the engine is about to end the live session {@code listener} runs (a launch over it,
   * as Stop it and create does, another session opened, a reload that doesn't carry it on): it ends
   * for its participants first, as Stop ends it. The computer players go off, the session is marked
   * stopped (a page that comes back is given its end screen), its participants and the previews
   * watching them are told the session was stopped (a completion code on screen stays), the game's
   * timers stop and nothing more is recorded in it. The rebuild used to close their sockets without
   * a word and keep each page's game view as its last screen: a page that came back showed a round
   * whose buttons did nothing, for as long as it stayed open. Nothing for a session that isn't live,
   * nor for the test session. Under the engine lock.
   */
  private static void endBeforeRebuild(GameListener listener) {
    Long id = instanceIdOf(listener);
    if (id == null || Experiment.TEST_INSTANCE_ID.equals(id)) {
      return;
    }
    ExperimentInstance live = Autopilot.liveEngineSession();
    if (live == null || !id.equals(live.id)) {
      return;
    }
    // (the computer players first, as for Stop: a takeover as the pages close would play on)
    if (playerActions != null) {
      playerActions.turnAIOff();
    }
    stopSession(listener);
    endParticipants(id, stoppedScreen());
    cancelEngineTimers();
    eventTracker.disable();
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
    // (the engine the player is in: what fails once another has been built isn't that one's game)
    long generation = engineGeneration;
    Breadboard.BreadboardMessage message = joined
        ? new Breadboard.RunOnJoinStep(null, player, allAdmins, generation)
        : new Breadboard.RunOnLeaveStep(null, player, allAdmins, generation);
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

      java.util.function.Function<Client, ObjectNode> ending = endingSessions.get(experimentInstance.id);
      if (ending != null) {
        // The server is stopping, and its game with it (ServerStop): their end screen, not a seat in
        // it. Someone the stop hasn't told yet is told now, as it would tell them.
        if (client != null && sameSession(client, experimentId, experimentInstance)) {
          endParticipant(client, ending.apply(client));
        }
        finalScreen(in, out, endedSessionScreen(experimentInstance.id, clientId));
        return;
      }

      if (client != null && !sameSession(client, experimentId, experimentInstance)) {
        // The seat of that id in the game the engine runs belongs to another session's participant:
        // the token was for this (older, test or other) session, not for that seat. Taking it over
        // used to hand someone else's screen and choices to whoever held such a token. Not a newcomer
        // either: the id is taken in the game the engine runs.
        Logger.info("addClient: " + clientId + " of instance " + experimentInstanceId + " refused: that id is a participant of "
            + (client.experimentInstance == null ? "another session" : "instance " + client.experimentInstance.id));
        // (a stopped session counts as over here: its participants keep the screen it ended with)
        boolean over = experimentInstance.status != null
            && (!experimentInstance.status.isRunnable() || experimentInstance.status == ExperimentInstance.Status.STOPPED);
        finalScreen(in, out, over ? endedSessionScreen(experimentInstance.id, clientId) : sessionNotRunningScreen());
        return;
      }

      if (client != null && client.ended()) {
        // Their session is ending this very moment (endParticipants): their end screen, as for a page
        // that comes back later, not a seat in the game that is over.
        finalScreen(in, out, endedSessionScreen(experimentInstance.id, clientId));
        return;
      }

      if (client == null && experimentInstance.status != null
          && (!experimentInstance.status.isRunnable() || experimentInstance.status == ExperimentInstance.Status.STOPPED)) {
        // The session is over (finished, or given up by the autopilot), and the engine may already
        // be running the next one, which this participant must not be pulled into. Give the page
        // back its last screen (a completion code, a "could not start" notice) instead.
        Logger.debug("addClient: instance " + experimentInstanceId + " is no longer running");
        finalScreen(in, out, endedSessionScreen(experimentInstance.id, clientId));
        return;
      }

      if (client == null && !runsSession(experimentId, experimentInstance)) {
        // Not the session the engine runs (it moved on to another experiment or session, or nothing
        // has taken this one up since a restart). Its pages reconnect, and used to be put into
        // whatever game the engine was running.
        Logger.debug("addClient: instance " + experimentInstanceId + " is not the session the engine runs");
        finalScreen(in, out, sessionNotRunningScreen());
        return;
      }

      if (byResearcher && !experimentInstance.isTestInstance() && !hasPlayer(clientId)
          && (client == null || !Boolean.TRUE.equals(experimentInstance.hasStarted))) {
        // A researcher's preview of someone who isn't in the game of a real session: it says so, and
        // adds nobody (only the test session takes the test players a preview names). It used to add
        // them before the start, as a page of theirs would, OnJoinStep and all, and they counted as
        // connected: the console's Preview page keeps its tiles across a Stop and Resume (or a reload
        // that carries the session on), so a waiting room could start with nobody but previews in it.
        // Someone the game left out, or took out after the start, is shown their last screen below.
        finalScreen(in, out, notInGameScreen(clientId));
        return;
      }

      if (client == null && experimentInstance.hasStarted && !hasPlayer(clientId)) {
        // Someone new after the start (sign-in already turns them away, so e.g. an old page): say
        // so instead of leaving the page connecting for ever. Someone whose player is in the game
        // (a participant whose page is gone, a researcher's preview of a computer player) goes on.
        Logger.debug("New client trying to join after game has already started.");
        finalScreen(in, out, sessionStartedScreen());
        return;
      }

      final Client.Connection connection = new Client.Connection(in, out, byResearcher);
      // Someone this session has seen: their page reconnecting, another tab, or a preview of them.
      boolean returning = client != null;
      if (client == null) {
        // New client, let's create a new Client object (another page of this id may come in at the
        // same moment: two tabs opened at once)
        Client created = new Client(clientId, experimentInstance);
        if (byResearcher && ownPageHeldBefore(experimentInstance, clientId)) {
          // (their own page held the seat with a Client a rebuild of the engine has dropped since)
          created.ownPageHeld();
        }
        client = clients.putIfAbsent(clientId, created);
        if (client == null) {
          client = created;
        }
      }
      // The controller has checked that this request holds the seat's token, or is a researcher's
      // preview. Client.connect says what becomes of this socket, and of the one before, which can
      // no longer act for this participant.
      Client.Arrival arrival = client.connect(connection);
      if (!byResearcher && arrival != Client.Arrival.WATCHING) {
        ownPageHolds(experimentInstance, clientId);
      }
      final Client thisClient = client;
      watchForSilence();

      in.onClose(new Callback0() {
        public void invoke() {
          socketClosed(thisClient, connection);
        }
      });

      in.onMessage(new Callback<JsonNode>() {
        public void invoke(JsonNode event) {
          participantMessage(thisClient, connection, clientId, byResearcher, event);
        }
      });

      if (graphChangedListener != null) {
        // (also for a computer player's, whose page a preview watches: it is sent what changes)
        graphChangedListener.addClientListener(client);
      }

      if (arrival == Client.Arrival.WATCHING) {
        // A researcher's preview of a player whose own page is connected, or of a computer player:
        // it changes nothing (no connection is recorded, no player added), and only shows what that
        // player's page would.
        startWatching(thisClient, connection, experimentInstance);
        return;
      }

      if (graphInterface != null) {
        if (!experimentInstance.hasStarted) {
          graphInterface.addPlayer(clientId);
        }
        Vertex clientVertex = playerVertex(engine.get("g"), clientId);
        if (clientVertex == null && client.leftOut) {
          // The game started without them (see leftOutAtStart).
          leftOutOfTheGame(client, connection);
          return;
        }
        if (clientVertex == null && returning && Boolean.TRUE.equals(experimentInstance.hasStarted)) {
          // Taken out of the network some other way (by a researcher, or by the experiment, maybe
          // after giving them a completion code): their last screen again, as it was.
          ObjectNode last = client.lastFrame;
          if (last != null) {
            connection.write(last);
          }
        }
        if (clientVertex == null && !experimentInstance.hasStarted) {
          // Taken out again as they joined (graph.groovy no longer loses a player added at the
          // same time as another): e.g. by the autopilot's launch, which leaves out whoever isn't
          // connected yet, as they aren't until recordConnection below. That stands; adding them
          // back would undo it and run OnJoinStep for them twice.
          Logger.info("addClient: " + clientId + " was taken out of the network as they joined");
        }
        Recruitment.applyTo(clientVertex);
        // After addPlayer, so a new participant's vertex exists to record it on.
        recordConnection(client, true);
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
      connection.writeAside(jsonOutput);
    } catch (NumberFormatException nfe) {
      Logger.debug("addClient: NumberFormatException");
    }
  }

  /**
   * A message from a participant's page, or from a researcher's preview of it. Every message counts
   * as hearing from that socket (see closeSilentPages), and {"action": "Ping"} is answered on it with
   * {"pong": <the server's time in ms>}. What the page that holds the seat sends acts for the player.
   * A preview that only watches can't act: its sign-in is recorded (as a preview's), the rest is
   * ignored. So is everything from a socket that has been replaced.
   */
  @SuppressWarnings("unchecked")
  private static void participantMessage(Client client, Client.Connection connection, final String clientId,
                                         boolean byResearcher, JsonNode event) {
    String action = actionOf(event);
    if (heardFrom(connection, action)) {
      return;
    }
    if (!client.holdsSeat(connection) && !(client.watches(connection) && "LogIn".equals(action))) {
      return;
    }
    try {
      ObjectMapper mapper = new ObjectMapper();
      // TODO: is event.toString() the correct method here?
      Map<String, Object> jsonInput = mapper.readValue(event.toString(), Map.class);
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
        makeChoice(clientId, choiceUID, params, connection.out);
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

  // A message's "action", or "".
  private static String actionOf(JsonNode event) {
    JsonNode actionNode = event == null ? null : event.get("action");
    return actionNode == null ? "" : actionNode.asText();
  }

  /**
   * Something came from this page: it counts as hearing from it (see closeSilentPages), and
   * {"action": "Ping"} is answered on it with {"pong": <the server's time in ms>}. True for a ping,
   * which needs nothing more.
   */
  private static boolean heardFrom(Client.Connection connection, String action) {
    boolean ping = "Ping".equals(action);
    connection.heard(ping, clock.getAsLong());
    if (ping) {
      ObjectNode pong = Json.newObject();
      pong.put("pong", System.currentTimeMillis());
      connection.writeAside(pong);
    }
    return ping;
  }

  /**
   * A page given its final screen as it connected: its session is over, isn't the one the engine
   * runs, or has started without it. Nothing more comes for it, and its socket stays open, as before
   * (a page from before 3.1 reconnects as soon as it closes). Its pings are answered, so the page's
   * heartbeat finds a connection that works, and one that falls silent is closed (closeSilentPages);
   * its closing drops it. Nobody used to listen: such a page (say, a thank-you screen left open when
   * the autopilot opened the next session) pinged every 5 s for as long as it was open, unanswered,
   * and its socket was never closed.
   */
  private static void finalScreen(WebSocket.In<JsonNode> in, ThrottledWebSocketOut out, JsonNode screen) {
    final Client.Connection page = new Client.Connection(in, out, false);
    endedPages.add(page);
    in.onClose(new Callback0() {
      public void invoke() {
        page.markClosed();
        endedPages.remove(page);
      }
    });
    in.onMessage(new Callback<JsonNode>() {
      public void invoke(JsonNode event) {
        heardFrom(page, actionOf(event));
      }
    });
    page.write(screen);
    watchForSilence();
  }

  /**
   * A researcher's preview of a player whose own page holds the seat, or of a computer player
   * (Client.connect has told it that it only watches): it gets what the player's page shows now,
   * their view (or their last screen) and the style, then every frame their page gets, for as long
   * as it is open.
   */
  private static void startWatching(Client client, Client.Connection watcher, ExperimentInstance instance) {
    Vertex v = engine == null ? null : playerVertex(engine.get("g"), client.id);
    ObjectNode last = client.lastFrame;
    if (v != null) {
      client.showTo(watcher, v);
    } else if (last != null) {
      watcher.write(last);
    }
    ObjectNode style = Json.newObject();
    style.put("style", instance.experiment.getStyle());
    watcher.writeAside(style);
  }

  /**
   * The autopilot's launch left this participant out of the game: they had been away too long when
   * it started (groovy/autopilot.groovy). Their page used to keep showing the waiting room it
   * remembered ("Please keep this page open") for good, and to keep reconnecting, even into the next
   * session. Now their last screen is the one that says what happened and what to do, which their
   * page gets whenever it comes back (leftOutOfTheGame), also once the engine has been rebuilt
   * (rememberLastScreens), and the event missedStart is recorded. Someone the experiment had already
   * given an end of their own (e.g. a completion code) keeps it.
   */
  public static void leftOutAtStart(String clientId) {
    Client client = clientId == null ? null : clients.get(clientId);
    if (client == null || client.leftOut || ownEnd(client.lastFrame)) {
      return;
    }
    Long instanceId = client.experimentInstance == null ? null : client.experimentInstance.id;
    client.lastFrame = missedStartScreen(fromProlific(client, instanceId));
    client.leftOut = true;
    if (eventTracker != null && eventTracker.sessionLive()) {
      LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
      data.put("clientId", clientId);
      eventTracker.track("missedStart", data);
    }
  }

  /**
   * The autopilot's launch took this participant out of the game because its waiting room was full:
   * maxPlayers were seated, the first to arrive, and they came after (groovy/autopilot.groovy). Their
   * page is told so now, with how to join the next session (sessionFullScreen), and closed, the
   * previews watching it too; their going is recorded (clientDisconnected), and so is the event
   * sessionFull. A page of theirs that comes back gets that screen again (their session is over for
   * them: Client.end). They used to be seated however many were there already. Someone the experiment
   * had already given an end of their own (e.g. a completion code) keeps it.
   */
  public static void sessionFullAtStart(String clientId) {
    Client client = clientId == null ? null : clients.get(clientId);
    if (client == null || client.leftOut || client.ended() || ownEnd(client.lastFrame)) {
      return;
    }
    ExperimentInstance session = client.experimentInstance;
    Long instanceId = session == null ? null : session.id;
    Long experimentId = session == null || session.experiment == null ? null : session.experiment.id;
    ObjectNode screen = sessionFullScreen(fromProlific(client, instanceId), experimentId);
    client.leftOut = true;
    client.end(screen);
    rememberLastScreens(Collections.singletonList(client));
    if (eventTracker != null && eventTracker.sessionLive()) {
      LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
      data.put("clientId", clientId);
      eventTracker.track("sessionFull", data);
    }
    client.disconnect(screen);
    recordConnection(client, false);
  }

  /**
   * What someone gets whom a full waiting room left out at the start (sessionFullAtStart). When the
   * autopilot opens the experiment's next session by itself, it says so: a link to the study's
   * sign-in link, whose page waits for the next session and takes them in (ClientLogin.toOpenSession),
   * or, for a {@code prolific} participant, to open the study again from Prolific; otherwise to
   * contact the researcher, or to return the study.
   */
  static ObjectNode sessionFullScreen(boolean prolific, Long experimentId) {
    boolean next = experimentId != null && Autopilot.opensNextBySelf(experimentId);
    String what;
    if (prolific) {
      what = next ? "The next session opens as soon as this one has ended: open the study again from Prolific to join "
          + "it (the page waits for it to open), or return the study on Prolific if you can't wait."
          : "Please return the study on Prolific, and message the researcher there if you have any questions.";
    } else {
      what = next ? "The next session opens as soon as this one has ended. <a href=\"/play/" + experimentId
          + "/login\">Join the next session</a>: the page waits for it to open and takes you in."
          : "You can close this page. If you have any questions, please contact the researcher.";
    }
    ObjectNode frame = Json.newObject();
    ObjectNode player = frame.putObject("player");
    player.putObject("_system");
    player.put("finished", true);
    player.put("ended", "sessionFull");
    player.put("text", "<h3>This session is full</h3><p>It started with as many people as it can take, so it "
        + "can't take you in. " + what + "</p>");
    return frame;
  }

  /**
   * Whether the game of this session left this participant out (leftOutAtStart, sessionFullAtStart):
   * the study's link then treats them as someone new (ClientLogin.toOpenSession), rather than sending
   * them back to the seat that only says so. Known while the engine runs that session's game.
   */
  public static boolean leftOutOf(String instanceId, String clientId) {
    Client client = clientId == null ? null : clients.get(clientId);
    ExperimentInstance seat = client == null ? null : client.experimentInstance;
    return client != null && client.leftOut && seat != null && seat.id != null && seat.id.toString().equals(instanceId);
  }

  /**
   * Whether the engine's waiting room (groovy/autopilot.groovy) is full and counting down, so it can
   * seat nobody more (Autopilot.openInstance then sends nobody to its session). Never waits.
   */
  static boolean waitingRoomFull() {
    ScriptEngine running = engine;
    Object autopilot = running == null ? null : running.get("autopilot");
    if (!(autopilot instanceof GroovyObject)) {
      return false;
    }
    try {
      return Boolean.TRUE.equals(((GroovyObject) autopilot).invokeMethod("roomFull", new Object[0]));
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * A page of someone the game started without (leftOutAtStart) connected: it is told so, and the
   * socket is closed, as nothing more comes for them. A researcher's preview of them sees the same.
   */
  private static void leftOutOfTheGame(Client client, Client.Connection connection) {
    ObjectNode screen = client.lastFrame;
    if (screen != null) {
      client.write(screen);
    }
    socketClosed(client, connection);
    connection.close(null);
  }

  /**
   * Record who is in this participant's seat (Client.presence): whether they are connected, and
   * whether a researcher's preview holds the seat (player._system, see markConnection). The events
   * clientConnected / clientDisconnected only when that has changed: their first page, their return
   * after it closed or fell silent, or its going; two tabs swapping the seat used to be recorded as
   * a connection every few seconds. A preview that holds the seat of a test player (one no page of
   * theirs has held in this session) counts as connected, and its events say preview = true (its
   * coming, its going, and its handing the seat to the participant's own page). On any other seat a
   * preview only stands in, and counts as nobody (Presence.AWAY): whether it watched as their page
   * went or came after (the console opens new ones all the time), it never makes them connected, so
   * the computer's takeover and the waiting room go on as for anyone away.
   * {@code tookSeat}: a socket has just taken the seat, maybe from another open one: their player is
   * marked connected if it isn't (one added again since was left unmarked, so the autopilot didn't
   * count them), and bb:connection is announced all the same, so the autopilot shows that page the
   * waiting room's or countdown's seconds as they are now. Nothing for a computer player, whose seat
   * the computer plays (a preview of it only watches).
   */
  static void recordConnection(Client client, boolean tookSeat) {
    if (client.computer()) {
      return;
    }
    synchronized (client.recordLock()) {
      Client.Presence now = client.presence();
      Client.Presence before = client.recordPresence(now);
      boolean connected = now != Client.Presence.AWAY;
      boolean wasConnected = before != null && before != Client.Presence.AWAY;
      // Each part on its own, as in setConnected.
      try {
        markConnection(client.id, connected, client.previewHolds());
      } finally {
        try {
          if (now != before && wasConnected) {
            trackConnection(client.id, false, before == Client.Presence.PREVIEW);
          }
          if (now != before && connected) {
            trackConnection(client.id, true, now == Client.Presence.PREVIEW);
          }
        } finally {
          if (connected != wasConnected || (connected && tookSeat)) {
            announceConnection(client.id, connected);
          }
        }
      }
    }
  }

  /**
   * A participant's socket closed, or was closed because it fell silent (closeSilentPages). Only the
   * page that held the seat counts: a replaced socket or a watching preview going changes nothing.
   * Nor does a socket of a session that has ended, whose participants are gone (the next session may
   * have someone with the same id). When the participant's page goes, a researcher's preview watching
   * them takes the seat (Client.seatAWatcher) and can act for them until they are back, but they have
   * left all the same: the computer takes their seat after the usual wait, and the waiting room
   * stops counting them, as for anyone who leaves.
   */
  static void socketClosed(Client client, Client.Connection connection) {
    if (client.closed(connection) && clients.get(client.id) == client) {
      client.seatAWatcher();
      recordConnection(client, false);
    }
  }

  /**
   * Close the sockets that have gone quiet: pages that ping but have sent nothing for longer than
   * silenceLimitMs. A connection that drops without closing (a phone out of range, a laptop put to
   * sleep) used to look connected for good, so the computer never took that seat. Now the
   * participant counts as gone, as when a socket closes, and the computer takes over after the
   * usual wait. A preview that watches is just closed and dropped, and so is a page that got its
   * final screen as it connected (finalScreen). Pages that never ping (from before 3.1) are left
   * alone. {@code now}: the time to judge by.
   */
  static void closeSilentPages(long now) {
    for (Client client : clients.values()) {
      for (Client.Connection connection : client.connections()) {
        if (connection.silent(now, silenceLimitMs)) {
          Logger.info("Closing a socket of " + client.id + (connection.preview ? " (a preview)" : "")
              + ": nothing heard from it for " + (now - connection.lastHeard()) / 1000 + " s");
          socketClosed(client, connection);
          connection.close(null);
        }
      }
    }
    for (Client.Connection page : endedPages) {
      if (page.silent(now, silenceLimitMs)) {
        Logger.debug("Closing the socket of a page at its final screen: nothing heard from it for "
            + (now - page.lastHeard()) / 1000 + " s");
        endedPages.remove(page);
        page.close(null);
      }
    }
  }

  // closeSilentPages every few seconds, from the first participant on (not in tests, which call it).
  private static void watchForSilence() {
    if (Play.isTest() || !silenceWatched.compareAndSet(false, true)) {
      return;
    }
    Akka.system().scheduler().schedule(
        Duration.create(SILENCE_CHECK_SECONDS, TimeUnit.SECONDS),
        Duration.create(SILENCE_CHECK_SECONDS, TimeUnit.SECONDS),
        new Runnable() {
          public void run() {
            try {
              closeSilentPages(System.currentTimeMillis());
            } catch (Throwable t) {
              Logger.error("Could not check whether participants' connections are still there", t);
            }
          }
        },
        Akka.system().dispatcher());
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
        // (the game it starts, whichever account's actor built the engine: see consoleStartFailed)
        GameListener listener = engineGameListener;
        // Run Game (every frame tagged, "started already" included, so the console knows it is the answer)
        String failed = processScript("initStep.start()", breadboardMessage.out, null, "request", "RunGame");
        consoleStartFailed(listener, failed);
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
          try {
            breadboardMessage.user.update();
          } catch (javax.persistence.OptimisticLockException changedMeanwhile) {
            // The account changed since this copy was loaded (e.g. it followed a session that went live
            // while this waited for the lock): Ebean refuses a stale copy, and the script didn't run.
            User fresh = User.findByEmail(breadboardMessage.user.email);
            if (fresh != null) {
              fresh.currentScript = sendScript.script;
              fresh.update();
            }
          }

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
          // Breadboard passes a selection on only while no session is live in the engine. One may
          // have started since (read under the lock): the engine is left to it all the same.
          ExperimentInstance live = sessionKeptBySelection();
          if (live != null) {
            selectionKept(breadboardMessage, live);
            return;
          }
          // The engine's last session isn't live (it would have kept the engine, above). If the game's
          // copy of it still counts as running, it is marked stopped, so its pages aren't put into this
          // experiment's game. (Read under the lock: another actor may have changed the engine since
          // this message was sent.)
          GameListener previous = engineGameListener;
          if (previous != null && (resumed == null || !resumed.id.equals(instanceIdOf(previous)))) {
            stopSession(previous);
          }

          rebuildScriptBoard(experiment, builtFor(breadboardMessage));
          if (resumed != null) {
            bindLaunchParameters(resumed, changeExperiment.experiment);
          }
          loadSteps(experiment, breadboardMessage.out);

          //reset the instance
          if (resumed != null) {
            eventTracker.setExperimentInstance(resumed);
            gameListener.experimentInstance = resumed;
            reopenAfterRebuild(resumed);
          } else {
            // The engine runs no session now; events no longer go to the one it ran before.
            gameListener.experimentInstance = null;
            eventTracker.setExperimentInstance(null);
          }
          engineChanged();
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
      String over = null;
      synchronized (REBUILD_LOCK) {
        // Read again here: the game may have finished, or someone else stopped it, since this
        // console showed it running.
        ExperimentInstance current = ExperimentInstance.findById(instance.id);
        // The session the engine is running, whichever account started it.
        GameListener running = engineGameListener != null ? engineGameListener : gameListener;
        if (current == null || (current.status != ExperimentInstance.Status.RUNNING
            && current.status != ExperimentInstance.Status.TESTING)) {
          // Over already: nothing is stopped or recorded, and the autopilot isn't paused (Stop on its
          // finished session paused it, which also called off the next session it was about to open).
          over = current == null ? "There is no session with id " + stopGame.id + " any more." : alreadyOver(current);
        } else if (instance.id.equals(instanceIdOf(running))) {
          // A researcher stopping the autopilot's session pauses it (no new one opens behind their
          // back); first, so its periodic check can't take the engine in between.
          Autopilot.sessionStopped(current, breadboardMessage.user);
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
          // The computer players off before the timers stop, as a rebuild does: while they are on, a
          // pick asked for afterwards starts a timer of its own, so a takeover as the participants'
          // pages close (autopilot.handleDeparture) played the stopped game on, and its finish() made
          // the session finished, no longer resumable. (Resuming rebuilds the engine, with them on.)
          if (playerActions != null) {
            playerActions.turnAIOff();
          }
          stopSession(running);
          endParticipants(instance.id);
          // The game's timers (a round's time limit, takeovers, the experiment's own) would go on
          // firing into a stopped session, without its launch parameters; and nothing more is
          // recorded in it until it is resumed (which turns recording back on).
          cancelEngineTimers();
          eventTracker.disable();
          engineChanged();
        } else {
          // (This used to change the status in memory only.)
          stopQuietly(instance.id);
        }
      }
      if (over != null) {
        // (It stays the account's session, so its console shows how it ended.)
        Breadboard.replyError(breadboardMessage.out, "StopGame", over);
      } else if (instance.id.equals(breadboardMessage.user.experimentInstanceId)) {
        // Stopping an older session leaves the researcher's current one selected.
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
        joinOrLeaveStep("onJoinStep.start(" + groovyString(player.getId().toString()) + ")", runOnJoinStep.out,
            Experiment.ON_JOIN_STEP_NAME, runOnJoinStep.generation);
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
          joinOrLeaveStep("onLeaveStep.start(" + groovyString(player.getId().toString()) + ")", runOnLeaveStep.out,
              Experiment.ON_LEAVE_STEP_NAME, runOnLeaveStep.generation);
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
        // Not over another experiment's live session, which selecting left running (reloadRefusal).
        String refused = reloadRefusal(breadboardMessage.user);
        if (refused != null) {
          Breadboard.replyError(breadboardMessage, refused);
          return;
        }
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
          endBeforeRebuild(previous);
          stopSession(previous);
          if (previous == gameListener) {
            gameListener.experimentInstance = null;
          }
        }
        if (carriesOn) {
          carryOnThroughRebuild(own, breadboardMessage.user);
        }
        rebuildScriptBoard(selectedExperiment, builtFor(breadboardMessage));
        if (carriesOn) {
          Recruitment.restoreFor(own.id);
        }
        Breadboard.ReloadEngine reloadEngine = (Breadboard.ReloadEngine) message;
        ExperimentInstance ei = reloadEngine.user.experimentInstanceId == -1 ? null
            : ExperimentInstance.findById(reloadEngine.user.experimentInstanceId);
        // Only the account's session of the experiment reloaded: it can be one of another experiment
        // (selecting one leaves a live session as the account's), which would get this one's events
        // and set its parameters here. Nor do events go on to the session the engine ran before.
        Long of = ei == null || ei.experiment == null ? null : ei.experiment.id;
        boolean accountsSession = of != null && selectedExperiment != null && of.equals(selectedExperiment.id);
        if (accountsSession) {
          bindLaunchParameters(ei, selectedExperiment);
        }
        loadSteps(selectedExperiment, breadboardMessage.out);
        if (carriesOn) {
          reopenAfterRebuild(own);
        }
        if (accountsSession) {
          eventTracker.enable();
          eventTracker.setExperimentInstance(ei);
        } else {
          eventTracker.setExperimentInstance(null);
        }
        engineChanged();
      }
    }
  }

  /**
   * The live session (RUNNING or TESTING) the engine runs, the autopilot's or any researcher's, of
   * whichever experiment; null when none is live. While there is one, selecting an experiment changes
   * only what the account works on, and the engine is left as it is: rebuilding it for the selection
   * ended that session for everyone in it, so the console refused to switch at all, even just to
   * edit. An experiment's Build pages and data need no engine, and its engine actions are refused
   * until the engine runs it (engineRefusal, reloadRefusal). With no live session, selecting loads the
   * selected experiment's steps, as before.
   */
  static ExperimentInstance sessionKeptBySelection() {
    return engine == null ? null : Autopilot.liveEngineSession();
  }

  /**
   * The account selected an experiment (or signed in with one selected) while the engine runs the live
   * session {@code live}: only its selection changes (the caller saves it). When that session is of
   * the experiment selected, the account follows it, as one whose selection the autopilot's session
   * is of does, so its consoles show the session the engine runs rather than an older one. Returns
   * whether the account's session changed (its other tabs then need its User JSON).
   */
  static boolean followLiveSession(User user, ExperimentInstance live) {
    if (user == null || user.selectedExperiment == null || live == null || live.id == null || live.isTestInstance()) {
      return false;
    }
    Long of = live.experiment == null ? null : live.experiment.id;
    if (of == null || !of.equals(user.selectedExperiment.id) || live.id.equals(user.experimentInstanceId)) {
      return false;
    }
    user.setExperimentInstanceId(live.id);
    user.update();
    return true;
  }

  /**
   * A session has gone live in the engine (launched, or resumed or taken up again by SelectInstance):
   * every account that has its experiment selected follows it (followLiveSession), as when it selects
   * the experiment or signs in. Those used to be the only times, so once the session it followed had
   * ended, a researcher who isn't the autopilot's account kept the finished one, with New session
   * offered, while the autopilot ran its next one; nor did a session of their experiment that went
   * live while their console was open reach it. An account's own session has ended by now, if it had
   * one: the engine runs one at a time, and this one now. Under the engine lock, so no other change of
   * the engine comes in between. Returns the accounts in it now (those that followed it already too,
   * whose previous console showed it stopped), for tellAccounts; not {@code actor}, the account that
   * launched or selected it, which gets its User JSON anyway.
   */
  private static List<String> moveFollowersTo(ExperimentInstance live, User actor) {
    List<String> following = new ArrayList<String>();
    if (live == null || live.id == null || live.isTestInstance() || live.experiment == null) {
      return following;
    }
    sawLiveSession(live.id);
    for (User user : User.find.where().eq("selectedExperiment.id", live.experiment.id).findList()) {
      if (actor != null && actor.email != null && actor.email.equalsIgnoreCase(user.email)) {
        continue;
      }
      try {
        followLiveSession(user, live);
        if (live.id.equals(user.experimentInstanceId)) {
          following.add(user.email);
        }
      } catch (Exception e) {
        // (that account keeps the session it had; the others follow all the same)
        Logger.warn("Could not move " + user.email + " to session " + live.id + ": " + e);
      }
    }
    return following;
  }

  /** Every open console tab of these accounts gets its User JSON again: their session changed. */
  static void tellAccounts(Collection<String> emails) {
    for (String email : emails) {
      if (!consoleOpen(email)) {
        continue;
      }
      try {
        User fresh = User.findByEmail(email);
        if (fresh != null) {
          toAccount(email, fresh.toJson());
        }
      } catch (Exception e) {
        Logger.warn("Could not send " + email + " its User JSON: " + e);
      }
    }
  }

  /** Whether the account has a console tab open. */
  private static boolean consoleOpen(String email) {
    for (Admin admin : admins) {
      User user = admin.getUser();
      if (user != null && user.email != null && user.email.equalsIgnoreCase(email)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The engine's live session {@code ended} has ended (finished, stopped, ended by the autopilot or a
   * guard, or replaced): every account with a console open whose session it is gets its User JSON,
   * which shows how it ended. The new console asks for it when it hears the engine news; the previous
   * one (/legacy-admin) doesn't read that news, and kept showing a followed session running after it
   * finished or another account stopped it.
   */
  private static void tellWhoseSessionEnded(Long ended) {
    Set<String> asked = new HashSet<String>();
    for (Admin admin : admins) {
      User user = admin.getUser();
      if (user == null || user.email == null || !asked.add(user.email.toLowerCase(Locale.ENGLISH))) {
        continue;
      }
      try {
        User fresh = User.findByEmail(user.email);
        if (fresh != null && ended.equals(fresh.experimentInstanceId)) {
          toAccount(fresh.email, fresh.toJson());
        }
      } catch (Exception e) {
        Logger.warn("Could not tell " + user.email + " that session " + ended + " ended: " + e);
      }
    }
  }

  /**
   * A selection that reached the engine's actor after a live session had started: the engine is left
   * to that session, as Breadboard does when one is live already. The account's consoles get its User
   * JSON again (its session may have changed); the one that asked gets it with {@code engineKept}, so
   * it doesn't wait for a rebuild.
   */
  private static void selectionKept(Breadboard.BreadboardMessage request, ExperimentInstance live) {
    User fresh = User.findByEmail(request.user.email);
    if (fresh == null) {
      return;
    }
    followLiveSession(fresh, live);
    ObjectNode json = fresh.toJson();
    toOtherTabs(fresh.email, json, request.out);
    if (request.out != null) {
      request.out.write(json.deepCopy().put("engineKept", true));
    }
  }

  /**
   * A Reload engine is about to rebuild the engine under the account's live session, which carries
   * on in the new one (ReloadEngine; or the session is selected again, SelectInstance), before the
   * rebuild closes its participants' pages. Its data
   * says the engine was reloaded there, by whom, and whether a game in progress starts again from the
   * beginning (engineReloaded: restarted, by), as sessionResumed says for a Resume: the data of the
   * game before is the old game's. And it takes people again now (reopenAfterRebuild), not once the
   * new engine has loaded the steps: the pages of those who held a seat reconnect within a second of
   * being closed, while the steps were still loading, and were given the late-arrival page ("it began
   * before you arrived") of a game that was no longer there, and stopped trying. Nothing for a
   * session that isn't running.
   */
  private static void carryOnThroughRebuild(ExperimentInstance session, User by) {
    ExperimentInstance fresh = session == null || session.id == null ? null : ExperimentInstance.findById(session.id);
    if (fresh == null || fresh.isTestInstance() || fresh.status != ExperimentInstance.Status.RUNNING) {
      return;
    }
    // (the game counts as started by the engine, or as closed to newcomers by the session: with
    // breadboard.allowLateJoin a game that started never closes)
    ScriptEngine running = engine;
    boolean restarted = (running != null && gameStartedIn == running) || Boolean.TRUE.equals(fresh.hasStarted);
    LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
    data.put("restarted", restarted);
    data.put("by", by == null || by.email == null ? "" : by.email);
    EventTracker.record(fresh, "engineReloaded", data);
    reopenAfterRebuild(session);
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
   * says so itself). Its console shows that experiment; the engine may run another one's session,
   * which selecting left running (see sessionKeptBySelection).
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
    ExperimentInstance session = Autopilot.liveEngineSession();
    String yours = user == null || user.selectedExperiment == null ? null : user.selectedExperiment.name;
    StringBuilder text = new StringBuilder("Nothing was done: the engine is running ");
    text.append(describeEngine(user, session));
    text.append(yours == null ? ", and you have no experiment selected." : ", not your selected experiment \"" + yours + "\".");
    if (session != null) {
      text.append(toRunYours(yours, session));
    } else {
      text.append(yours == null ? " Select one first." : " Reload the engine to load \"" + yours + "\".");
    }
    return text.toString();
  }

  /**
   * Why this account's reload must not run now, or null: the engine runs a live session of another
   * experiment than the one it has selected (selecting left it running), and rebuilding the engine
   * would end that session for everyone in it. With no live session, a reload is how the selected
   * experiment gets loaded; a new session of it takes the engine over too, after the console asks.
   */
  static String reloadRefusal(User user) {
    if (engine == null) {
      return null;
    }
    Long selected = user == null || user.selectedExperiment == null ? null : user.selectedExperiment.id;
    ExperimentInstance session = Autopilot.liveEngineSession();
    if (session == null || (selected != null && selected.equals(engineExperimentId))) {
      return null;
    }
    String yours = user == null || user.selectedExperiment == null ? null : user.selectedExperiment.name;
    return "Nothing was reloaded: the engine is running " + describeEngine(user, session)
        + (yours == null ? "" : ", not your selected experiment \"" + yours + "\"")
        + ", and reloading it would end that session for everyone in it." + toRunYours(yours, session);
  }

  /**
   * Why file mode can't be switched on for the account's selected experiment now, or null: the engine
   * runs a live session of another experiment. In file mode a step file saved in the experiment's
   * folder runs at once, which it can't while that session holds the engine (see stepFileSaved).
   */
  static String fileModeRefusal(User user) {
    Experiment selected = user == null ? null : user.selectedExperiment;
    ExperimentInstance session = engine == null ? null : Autopilot.liveEngineSession();
    Long of = session == null || session.experiment == null ? null : session.experiment.id;
    if (selected == null || of == null || of.equals(selected.id)) {
      return null;
    }
    return "File mode was not switched on: the engine is running " + describeEngine(user, session)
        + ". In file mode a step file you save runs at once, which it can't while that session runs."
        + " Switch file mode on once it has ended.";
  }

  /**
   * A step file of an experiment in file mode was saved in its folder (FileWatcher.fileSaved). It runs
   * in the engine at once, as file mode promises, while the engine runs that experiment, during its
   * sessions too. Otherwise it is not loaded: the engine runs another experiment, maybe its session
   * (the autopilot's study, say), whose game it would change; the next build of this one loads what
   * is saved. Under the engine lock, as other step loads, so the engine can't be replaced in between.
   * Every open console hears what happened. Returns whether it was loaded.
   */
  static boolean stepFileSaved(Experiment experiment, String source, String fileName) {
    String notLoaded;
    synchronized (REBUILD_LOCK) {
      if (engine != null && experiment.id.equals(engineExperimentId)) {
        // Step code: it defines steps, so mentioning initStep.start() starts nothing (see processScript).
        processScript(source, allAdmins, fileName, "source", STEPS_SOURCE, null, true);
        // A reloaded step may have set up the autopilot itself; the console's settings win again.
        afterStepsLoaded(experiment);
        return true;
      }
      ExperimentInstance session = engine == null ? null : Autopilot.liveEngineSession();
      String saved = "Saved " + fileName + " of \"" + experiment.name + "\"; not loaded, because ";
      if (engine == null) {
        notLoaded = saved + "the script engine isn't running yet. It loads when \"" + experiment.name + "\" is loaded.";
      } else if (session != null) {
        notLoaded = saved + "the engine is running " + describeEngine(null, session) + ". It loads when the next session of \""
            + experiment.name + "\" starts.";
      } else {
        notLoaded = saved + "the engine has " + describeEngine(null, null) + " loaded. It loads when the next session of \""
            + experiment.name + "\" starts, or when the engine is reloaded with it selected.";
      }
    }
    Logger.info(notLoaded);
    ObjectNode note = Json.newObject();
    note.put("source", STEPS_SOURCE);
    note.put("output", notLoaded);
    toAllAdmins(note);
    return false;
  }

  /**
   * The engine's experiment ("\"A\"", or "no experiment") and its live session, if any: " (the
   * autopilot's session \"S\")", " (your session \"S\")" or " (session \"S\" of x@lab.org)".
   */
  private static String describeEngine(User user, ExperimentInstance session) {
    Experiment experiment = engineExperiment();
    String text = experiment == null ? "no experiment" : "\"" + experiment.name + "\"";
    if (session == null) {
      return text;
    }
    String owner = engineOwnerEmail();
    if (isAutopilots(session) && Autopilot.isAutopilotsSession(session.id)) {
      return text + " (the autopilot's session \"" + session.name + "\")";
    }
    if (owner != null && user != null && owner.equalsIgnoreCase(user.email)) {
      return text + " (your session \"" + session.name + "\")";
    }
    return text + " (session \"" + session.name + "\"" + (owner == null ? "" : " of " + owner) + ")";
  }

  /** What to do to run the selected experiment while {@code session} holds the engine. */
  private static String toRunYours(String yours, ExperimentInstance session) {
    if (yours == null) {
      return " Select an experiment first.";
    }
    return " To run \"" + yours + "\", end that session first" + (isAutopilots(session) ? " (that pauses the autopilot)" : "")
        + ", or open a new session of \"" + yours + "\", which ends it.";
  }

  /** Whether a session is of the experiment the autopilot runs (ending it pauses the autopilot). */
  private static boolean isAutopilots(ExperimentInstance session) {
    Experiment autopilot = Autopilot.experiment();
    Long of = session == null || session.experiment == null ? null : session.experiment.id;
    return autopilot != null && of != null && of.equals(autopilot.id);
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

  // Whether a push of what the engine runs is waiting to go out (see engineChanged).
  private static final java.util.concurrent.atomic.AtomicBoolean engineNewsDue = new java.util.concurrent.atomic.AtomicBoolean(false);
  // One push at a time, so the last one out says what runs now.
  private static final Object ENGINE_NEWS_LOCK = new Object();
  // The live session the engine was last seen to run (as one went live, and at each push), and those
  // seen live that have ended since, whose accounts are still to be told (tellWhoseSessionEnded). Seen
  // at both: a session that went live and ended between two pushes was in none. Under REBUILD_LOCK.
  private static Long liveSessionSeen = null;
  private static final Set<Long> endedUntold = new LinkedHashSet<Long>();

  /** The engine runs this live session now (null: none). Under REBUILD_LOCK. */
  private static void sawLiveSession(Long live) {
    if (liveSessionSeen != null && !liveSessionSeen.equals(live)) {
      endedUntold.add(liveSessionSeen);
    }
    liveSessionSeen = live;
  }

  /**
   * The engine's session or experiment has changed: a session launched, resumed, started, stopped,
   * finished or ended, or the engine rebuilt. Every open console is told what the engine runs now,
   * as {@code {"engine": ...}} (engineJson, as in the User JSON), a moment later. Only the account
   * that acted used to be told (in its User JSON): other consoles kept refusing Reload and Run for a
   * session that had ended, and one that followed a session that finished kept showing it running.
   * Never waits: the caller may hold the game's own locks (a waiting room giving up finishes its
   * session while holding its own).
   */
  static void engineChanged() {
    if (!engineNewsDue.compareAndSet(false, true)) {
      return;
    }
    try {
      play.libs.Akka.system().scheduler().scheduleOnce(
          scala.concurrent.duration.Duration.create(50, java.util.concurrent.TimeUnit.MILLISECONDS),
          new Runnable() {
            public void run() {
              tellEngineNews();
            }
          },
          play.libs.Akka.system().dispatcher());
    } catch (Exception e) {
      engineNewsDue.set(false);
      Logger.warn("Could not tell the consoles what the engine runs: " + e);
    }
  }

  /**
   * {@code {"engine": ...}} to every open console, read under the engine lock (never half-way through a
   * change). When a live session has ended since the last push, the accounts whose session it is get
   * their User JSON first (tellWhoseSessionEnded).
   */
  private static void tellEngineNews() {
    synchronized (ENGINE_NEWS_LOCK) {
      engineNewsDue.set(false);
      ObjectNode news = Json.newObject();
      List<Long> ended;
      try {
        synchronized (REBUILD_LOCK) {
          ObjectNode engineNow = engineJson();
          news.put("engine", engineNow);
          JsonNode sessionId = engineNow.get("sessionId");
          sawLiveSession(sessionId != null && sessionId.isNumber() ? sessionId.asLong() : null);
          ended = new ArrayList<Long>(endedUntold);
          endedUntold.clear();
        }
      } catch (Exception e) {
        Logger.warn("Could not tell the consoles what the engine runs: " + e);
        return;
      }
      for (Long session : ended) {
        tellWhoseSessionEnded(session);
      }
      toAllAdmins(news);
    }
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

  /** What Stop answers for a session that is over already (it finished, or was stopped). */
  private static String alreadyOver(ExperimentInstance session) {
    String how = session.status == ExperimentInstance.Status.FINISHED ? "it has finished"
        : session.status == ExperimentInstance.Status.STOPPED ? "it was stopped"
        : "it is " + String.valueOf(session.status).toLowerCase(Locale.ENGLISH);
    return "Nothing was stopped: session \"" + session.name + "\" is already over (" + how + ").";
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

    // The other accounts working on this experiment, which follow the new session.
    List<String> following = Collections.emptyList();
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
      endBeforeRebuild(previous);
      rebuildScriptBoard(experiment, byAutopilot ? null : user.email);
      stopSession(previous);
      ExperimentInstance instance = startGame(launchGame.name, user.experimentInstanceId, parameters, experiment, user, onAccount);

      instanceData.addPropertyChangeListener(new UserDataChangeListener(instance));

      eventTracker.enable();
      eventTracker.setExperimentInstance(instance);
      gameListener.experimentInstance = instance;
      gameListener.start();

      // The autopilot's session from now on, before its steps load: the Autopilot page's settings
      // apply in it (AutopilotHooks).
      if (byAutopilot) {
        Autopilot.launchCarriedOut(instance.id);
      }
      // Re-run the Steps, then the autopilot settings over them: here, under the lock, so another
      // account's rebuild can't come between (queued, they used to run in whatever engine was there).
      String failed = loadSteps(experiment, launchGame.out);
      if (byAutopilot) {
        if (failed != null) {
          // The steps define its waiting room and game: participants would wait for a game that
          // never starts. The session stops, and the autopilot pauses until the steps are fixed.
          LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
          data.put("error", failed);
          eventTracker.track("stepsFailedToLoad", data);
          // (the computer players off before the timers stop, as for Stop)
          if (playerActions != null) {
            playerActions.turnAIOff();
          }
          stopSession(gameListener);
          // (not the "stopped" screen: no researcher stopped it; a completion code on screen stays)
          endParticipants(instance.id, problemScreens(instance.id));
          cancelEngineTimers();
          eventTracker.disable();
          Autopilot.stepsFailed(instance, failed);
        }
      }
      // (not into a session the autopilot stopped already, above)
      if (!byAutopilot || failed == null) {
        following = moveFollowersTo(instance, user);
      }
    }
    // Their consoles show it, before the engine news reaches them (they needn't ask then).
    tellAccounts(following);
    engineChanged();

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

    // The other accounts working on its experiment, which follow it once it is live again.
    List<String> following = Collections.emptyList();
    synchronized (REBUILD_LOCK) {
      // The engine's current session ends (the account that started it may be another one), and so
      // does this actor's own; the selected one carries on (restarted) if it still can.
      GameListener previous = engineGameListener;
      Long continuing = target.status != null && target.status.isRunnable() ? target.id : null;
      pauseAutopilotIfItsSession(gameListener, user, "ended when %s opened another session", continuing);
      if (previous != null && previous != gameListener) {
        pauseAutopilotIfItsSession(previous, user, "ended when %s opened another session", continuing);
      }
      // (the engine's session, unless it is the one selected, which carries on)
      if (previous != null && (continuing == null || !continuing.equals(instanceIdOf(previous)))) {
        endBeforeRebuild(previous);
      }
      if (continuing != null && continuing.equals(instanceIdOf(previous)) && !resuming) {
        // The engine's live session selected again: it carries on in the new engine, as with a reload.
        carryOnThroughRebuild(previous.experimentInstance, user);
      }
      rebuildScriptBoard(experiment, builtFor(selectInstance));
      if (continuing != null) {
        bindLaunchParameters(target, experiment);
        Recruitment.restoreFor(target.id);
      }
      loadSteps(experiment, selectInstance.out);
      user.setExperimentInstanceId(target.id);
      try {
        user.update();
      } catch (javax.persistence.OptimisticLockException changedMeanwhile) {
        // As in startGame: the account changed since this copy was loaded (e.g. it followed a session
        // that went live while this waited for the lock), and the selection stopped half-way, with the
        // engine rebuilt and no session in it. Saved on a fresh copy instead.
        User fresh = User.findByEmail(user.email);
        if (fresh == null) {
          throw changedMeanwhile;
        }
        fresh.setExperimentInstanceId(target.id);
        fresh.setSelectedExperiment(user.selectedExperiment);
        fresh.update();
      }

      stopSession(gameListener);
      if (previous != null && previous != gameListener) {
        stopSession(previous);
      }
      // Loaded again: stopping may just have saved this same session.
      ExperimentInstance instance = ExperimentInstance.findById(target.id);
      if (instance != null && instance.status != null && instance.status.isRunnable()) {
        // (its launch parameters were bound before its steps loaded, above)
        eventTracker.enable();
        eventTracker.setExperimentInstance(instance);
        gameListener.experimentInstance = instance;
        gameListener.start();
        sessionRunsAgain(instance.id);
        // (After start(): a stopped session being resumed counts as running only from there.)
        reopenAfterRebuild(instance);
        if (resuming) {
          // Its game starts again from the beginning: the data says where, so the runs can be told apart.
          LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
          data.put("by", user.email);
          eventTracker.track("sessionResumed", data);
        }

        instanceData.addPropertyChangeListener(new UserDataChangeListener(instance));
        following = moveFollowersTo(instance, user);
      } else {
        // A finished session is shown, not restarted: the engine runs no session now.
        gameListener.experimentInstance = null;
      }
      engineChanged();
    }
    tellAccounts(following);
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
    if (deletedId.equals(engineExperimentId)) {
      engineChanged();
    }

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

  /**
   * Bind an existing session's launch parameters (its data) in the engine just built, as a launch binds
   * a new one's (initAllParam): before its steps load, which may read them as they do (the network
   * game's waiting room takes its size from them). Selecting a session (Resume, or the running one
   * again), a Reload engine that carries the session on, and taking the account's running session up
   * again as its experiment is selected bound them only after the steps had loaded, which then ran with
   * the experiment's defaults: a session launched for 3 people waited for 2.
   */
  private void bindLaunchParameters(ExperimentInstance session, Experiment experiment) {
    for (Data param : session.data) {
      initParam(param, experiment);
    }
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
   * on the vertex (engine-internal, never sent to neighbors), with {@code _system.disconnectedAt}
   * (ms) while they are away, a clientConnected / clientDisconnected event in the log, and
   * bb:connection for the scripts.
   */
  static void setConnected(String clientId, boolean connected) {
    // Each part on its own (as in recordConnection): setting _system runs every graph listener
    // (consoles, the player's and neighbours' pages), and one of them failing used to skip
    // bb:connection, so the autopilot never learned that someone left and no AI took their seat.
    try {
      markConnection(clientId, connected, false);
    } finally {
      try {
        trackConnection(clientId, connected, false);
      } finally {
        announceConnection(clientId, connected);
      }
    }
  }

  /**
   * The player's {@code _system}: connected, disconnectedAt while they are away, and preview while a
   * researcher's preview holds the seat (playing a test player, or standing in for anyone else), so the
   * console can tell it from the participant's own page. Written only where it differs.
   */
  private static void markConnection(String clientId, boolean connected, boolean previewHolds) {
    try {
      if (engine != null) {
        Vertex v = playerVertex(engine.get("g"), clientId);
        if (v != null) {
          Map<String, Object> changes = connectionChanges(v, connected, previewHolds);
          if (!changes.isEmpty()) {
            setSystemValues(v, changes);
          }
        }
      }
    } catch (Exception e) {
      Logger.error("Could not record connection state for " + clientId, e);
    }
  }

  /**
   * The event clientConnected or clientDisconnected, with preview = true when a researcher's preview
   * came or went, so the data can tell it from the participant's own page. Not into a session that is
   * over (people leaving its thank-you page aren't part of it), nor for a computer player.
   */
  private static void trackConnection(String clientId, boolean connected, boolean preview) {
    try {
      if (eventTracker != null && eventTracker.sessionLive() && !Client.isComputer(clientId)) {
        LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
        data.put("clientId", clientId);
        if (preview) {
          data.put("preview", "true");
        }
        eventTracker.track(connected ? "clientConnected" : "clientDisconnected", data);
      }
    } catch (Exception e) {
      Logger.error("Could not log the " + (connected ? "connection" : "disconnection") + " of " + clientId, e);
    }
  }

  // bb:connection, so scripts can react: e.g. groovy/autopilot.groovy hands a departed player to the AI.
  private static void announceConnection(String clientId, boolean connected) {
    try {
      Map<String, Object> info = new HashMap<String, Object>();
      info.put("clientId", clientId);
      info.put("connected", connected);
      eventBus.emit("bb:connection", info);
    } catch (Exception e) {
      Logger.error("Could not announce the connection change of " + clientId, e);
    }
  }

  // What changes in the player's _system: connected; since when they are away (disconnectedAt, set
  // as they go: the autopilot's launch keeps the seat of someone gone only a moment); and preview,
  // there only while a researcher's preview holds the seat.
  private static Map<String, Object> connectionChanges(Vertex v, boolean connected, boolean preview) {
    Object system = v.getProperty("_system");
    Map<?, ?> now = system instanceof Map ? (Map<?, ?>) system : Collections.emptyMap();
    Map<String, Object> changes = new HashMap<String, Object>();
    if (!Boolean.valueOf(connected).equals(now.get("connected"))) {
      changes.put("connected", connected);
      changes.put("disconnectedAt", connected ? null : System.currentTimeMillis());
    }
    if (preview != Boolean.TRUE.equals(now.get("preview"))) {
      changes.put("preview", preview ? Boolean.TRUE : null);
    }
    return changes;
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
  static void setSystemValue(Vertex v, String key, Object value) {
    setSystemValues(v, Collections.singletonMap(key, value));
  }

  /** setSystemValue for several keys at once, announced once; a null value removes its key. */
  @SuppressWarnings("unchecked")
  static void setSystemValues(Vertex v, Map<String, Object> values) {
    Object system = v.getProperty("_system");
    if (system instanceof Map) {
      Map<String, Object> systemMap = (Map<String, Object>) system;
      for (Map.Entry<String, Object> value : values.entrySet()) {
        if (value.getValue() == null) {
          systemMap.remove(value.getKey());
        } else {
          systemMap.put(value.getKey(), value.getValue());
        }
      }
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
   * (stepFailed leaves it to this method), and FAILED_RUN names it.
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
    String details = null;
    Throwable thrown = null;
    // (for a start that fails: whether a step got going meanwhile, see startCommandFailed)
    long goingBefore = stepsStarted();
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
        markGameStarted(running);
        gameStartNotice("GameStarted");
      }
    } catch (CompilationFailedException cfe) {
      // Translate Groovy's opaque "Script<N>.groovy" names back to real file names in both the
      // logged stack trace and the error sent to the browser (see ScriptLoader.humanize*).
      ScriptLoader.humanizeStackTrace(cfe);
      Logger.error("Unable to compile the script. " + scriptName, cfe);
      details = ScriptBoardSupport.describeError(cfe);
      jsonOutput.put("error", "Caught error: " + details + "\n");
      failure = ScriptBoardSupport.briefError(cfe);
      thrown = cfe;
      if (initStep) {
        startCommandFailed(running, closedBy, startedBefore, goingBefore);
      }
    } catch (ScriptException se) {
      ScriptLoader.humanizeStackTrace(se);
      Logger.error("Script Error. " + scriptName, se);
      details = ScriptBoardSupport.describeError(se);
      jsonOutput.put("error", "Caught error: " + details + "\n");
      failure = ScriptBoardSupport.briefError(se);
      thrown = se;
      if (initStep) {
        startCommandFailed(running, closedBy, startedBefore, goingBefore);
      }
    } catch (Throwable e) {
      // (Throwable: the engine passes an Error, e.g. a failed assert in a step, on as it is, and a
      // step that throws one leaves it to this method like any other)
      ScriptLoader.humanizeStackTrace(e);
      Logger.error("Failed to process the script. " + scriptName, e);
      details = ScriptBoardSupport.describeError(e);
      jsonOutput.put("error", "Caught error: " + details + "\n");
      failure = ScriptBoardSupport.briefError(e);
      thrown = e;
      if (initStep) {
        startCommandFailed(running, closedBy, startedBefore, goingBefore);
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
      Throwable inStep = thisScript.stepFailure;
      boolean replaced = running != engine;
      FAILED_RUN.set(inStep != null && causedBy(thrown, inStep)
          ? new ScriptFailure(failure, thisScript.step, ScriptBoardSupport.briefError(inStep),
              ScriptBoardSupport.describeError(inStep), thisScript.startTakenBack, replaced)
          : new ScriptFailure(failure, null, failure, details, thisScript.startTakenBack, replaced));
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
    // the steps that had got going on its thread as its init step started, counted once the
    // bb:game-start handlers had run (STEPS_STARTED)
    final long steps;

    StartMark(ScriptEngine engine, GameListener closedBy, Boolean before, long steps) {
      this.engine = engine;
      this.closedBy = closedBy;
      this.before = before;
      this.steps = steps;
    }
  }

  // How a step that fails during the game is dealt with, in one rule. A failure stalls the game when
  // it kept the next step from getting going; a step gets going when its start() completes without
  // throwing (it waits for choices or for its time, or its done has run). In the autopilot's own
  // session (one it opened, or the one of its experiment Resume or a save handed it), a failure that
  // stalls ends the session (stopped, its participants told) and pauses the autopilot, naming the step
  // and line (endAutopilotSession); one that doesn't stall is recorded (a stepFailed event), shown on
  // the consoles, and the game goes on (gameFailed). In a session run by hand nothing is ended: a
  // start that didn't get going is taken back (startFailed), so Start works again, and the error is
  // shown.
  //
  // How many steps have got going on each thread (stepStarted counts them). Whoever runs a step's code
  // keeps the count from before it: if a step got going since, when that code throws (an init step's
  // done started the first round, then failed), the game is under way and goes on. Per thread, since
  // a step's done starts the next step on the thread it runs on: a step another thread starts
  // meanwhile (a newcomer's OnJoinStep) says nothing about this one.
  private static final ThreadLocal<long[]> STEPS_STARTED = new ThreadLocal<long[]>() {
    @Override
    protected long[] initialValue() {
      return new long[1];
    }
  };

  /**
   * A step got going: its start() is returning without having thrown (groovy/step.groovy calls this,
   * through GameListener). Counted on this thread (STEPS_STARTED). Never throws.
   */
  static void stepStarted(Object step) {
    STEPS_STARTED.get()[0]++;
    STEPS_GOT_GOING.incrementAndGet();
  }

  /** How many steps have got going on this thread so far (stepStarted). */
  static long stepsStarted() {
    return STEPS_STARTED.get()[0];
  }

  /** Whether a step has got going on this thread since stepsStarted() answered {@code before}. */
  private static boolean gotGoingSince(long before) {
    return STEPS_STARTED.get()[0] != before;
  }

  /**
   * A step is starting (groovy/step.groovy calls this, through GameListener, from Step.start()). When
   * it is the engine's initStep, the game starts, however it was started. The start command
   * (console's Start, autopilot, AMT timer) has been marked by processScript already. A start in the
   * experiment's own code (e.g. an OnJoinStep that starts the game once enough people are in) is
   * marked now, as processScript would: the next Start answers "started already", the session is
   * closed to newcomers, the console sees gameStarted, and scripts get "bb:game-start". Returns what
   * it marked, which Step.start hands to stepStartFailed if the init step throws as it starts (its
   * run, or a done that runs at once); null when it marked nothing. Steps of an engine that has since
   * been replaced are ignored.
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
    boolean marked = false;
    GameListener closedBy = null;
    Boolean before = null;
    synchronized (ScriptBoard.class) {
      if (running != engine) {
        return null;
      }
      ResearcherRun researcher = RESEARCHER_RUN.get();
      if (running.get("initStep.start()") == null) {
        running.put("initStep.start()", true);
        if (!Play.application().configuration().getBoolean("breadboard.allowLateJoin", false)) {
          try {
            before = listener.closeToNewcomers();
            closedBy = listener;
          } catch (Exception e) {
            // The game still starts; only newcomers may still get in.
            Logger.error("Could not close the session to newcomers as its game starts", e);
          }
        }
        marked = true;
      } else if (researcher != null && researcher.startPending == running) {
        // The start command's own start, marked before it ran; it undoes it itself if it fails.
        researcher.startPending = null;
      } else if (researcher != null) {
        throw new IllegalStateException("initStep started already");
      }
      markGameStarted(running);
    }
    if (!marked) {
      return null;
    }
    // Outside the lock, as in processScript: a handler may hold its own lock and call back in here.
    Map<String, Object> info = new HashMap<String, Object>();
    info.put("scriptName", "initStep");
    eventBus.emit("bb:game-start", info);
    // The steps that got going on this thread are counted from here, once the handlers have run, as
    // the start command counts them: a step a handler started (a game clock, say) is not this game
    // getting going, and a first round that then fails still stalls it (stepStartFailed).
    StartMark mark = new StartMark(running, closedBy, before, stepsStarted());
    gameStartNotice("GameStarted");
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
    engineChanged();
  }

  /**
   * The init step whose start stepStarting marked threw as it started (Step.start calls this on the
   * same thread): undone as for the start command (see startFailed), unless the engine has been
   * replaced since, or another step got going first. Never throws.
   */
  static void stepStartFailed(Object token) {
    if (!(token instanceof StartMark)) {
      return;
    }
    StartMark mark = (StartMark) token;
    if (gotGoingSince(mark.steps)) {
      // Another step got going before it threw (its done started the first round, say): the game is
      // under way, and its start stays. Taken back, the session took newcomers again in the middle of
      // that round, and an OnJoinStep that starts the game once enough people are in started it again
      // on top of it.
      return;
    }
    synchronized (ScriptBoard.class) {
      if (mark.engine != engine) {
        return;
      }
    }
    startFailed(mark.engine, mark.closedBy, mark.before);
    gameStartNotice("GameStartFailed");
  }

  /**
   * The start command threw (processScript, which counted the steps that had got going on this thread
   * before it ran: {@code goingBefore}). The start is taken back (startFailed), unless a step got going
   * since: InitStep's done started the first round, then threw. That game is under way, so it stays
   * started and closed to newcomers, and the consoles learn it started (the console's Start is
   * answered with the error). Never throws.
   */
  private static void startCommandFailed(ScriptEngine running, GameListener closedBy, Boolean before,
                                         long goingBefore) {
    if (gotGoingSince(goingBefore)) {
      markGameStarted(running);
      gameStartNotice("GameStarted");
      return;
    }
    startFailed(running, closedBy, before);
  }

  /**
   * initStep.start() threw: it may be tried again, the game doesn't count as started, and its session
   * takes newcomers again if it did before (hasStarted back to {@code before}, saved). The script
   * running on this thread, if any, learns its game stalled (ScriptFailure.stalled). Never throws.
   */
  private static void startFailed(ScriptEngine running, GameListener closedBy, Boolean before) {
    running.put("initStep.start()", null);
    if (gameStartedIn == running) {
      gameStartedIn = null;
    }
    ScriptRun script = SCRIPT_RUN.get();
    if (script != null) {
      script.startTakenBack = true;
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
   * page; the autopilot's start, see gameStartFailed; OnJoinStep and OnLeaveStep, see
   * joinOrLeaveStep): false, the step throws it on. Anywhere else (a timer, a participant's choice, a
   * computer player's) it used to end that thread, with the error only in the server's output, while
   * the game waited for good: it is reported here instead (see gameFailed), and true, it goes no
   * further. It stalls the game unless a step got going on this thread since that code began
   * ({@code going}: stepsStarted() then, null when not known), as when a done on a timer starts the
   * next round and then throws. A step of an engine that has been replaced since is only logged.
   * Never throws.
   */
  static boolean stepFailed(GameListener listener, Object step, Throwable failure, Long going) {
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
      if (!ofRunningEngine(step)) {
        Logger.warn("The step " + name + " failed in an engine other than the one running now (replaced since); "
            + "the game running now goes on", failure);
        return true;
      }
      Logger.error("The step " + name + " failed", failure);
      gameFailed(listener, name, ScriptBoardSupport.briefError(failure), ScriptBoardSupport.describeError(failure),
          false, going == null || !gotGoingSince(going));
    } catch (Throwable e) {
      Logger.error("Could not report the failure of the step " + name, e);
    }
    return true;
  }

  /**
   * Whether a step belongs to the engine running now. The game listener can't tell: an actor gives
   * every engine it builds the same one (buildEngine), and a step of an engine it has replaced can
   * still fail there (code that goes on after its finish(), a timer task under way when its timers
   * were cancelled). A step has its own engine's player actions ("a", which stepFactory gives it).
   */
  static boolean ofRunningEngine(Object step) {
    ScriptEngine running = engine;
    if (running == null || !(step instanceof groovy.lang.GroovyObject)) {
      return false;
    }
    try {
      Object actions = ((groovy.lang.GroovyObject) step).getProperty("playerActions");
      return actions != null && actions == running.get("a");
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * The autopilot's waiting room started the game, and the start failed with {@code error}, what
   * processScript answered (groovy/autopilot.groovy's launch calls this right after, on the same
   * thread). Reported as for stepFailed, naming the step that threw (or initStep, which the start
   * runs). When the start was taken back, its participants would stay on the countdown for good, with
   * newcomers let in again: the game stalls, and the session ends if it is the autopilot's own. When a
   * step got going first (InitStep's done started the first round, then threw), the start stays and
   * the game goes on. Nothing when the game has started after all (another start came first); only
   * logged when the start ran in an engine that has been replaced since (the actor's game listener
   * serves the new one). Never throws.
   */
  static void gameStartFailed(GameListener listener, String error) {
    ScriptFailure failed = FAILED_RUN.get();
    FAILED_RUN.remove();
    try {
      ScriptEngine running = engine;
      if (error == null || listener == null || running == null || listener.engine != running) {
        return;
      }
      // (only when it is how this start failed, which is what processScript answered it)
      boolean known = failed != null && error.equals(failed.answer);
      // (a start that failed and wasn't taken back got going; one processScript knows nothing of was
      // refused: "started already")
      boolean stalls = !known || failed.stalled;
      if (stalls && running.get(START_COMMAND) != null) {
        return;
      }
      String step = known && failed.step != null ? failed.step : "initStep";
      String brief = known ? failed.brief : error;
      String details = known ? failed.details : error;
      if (known && failed.replaced) {
        Logger.warn("The autopilot could not start the game of an engine that has since been replaced: the step " + step
            + " failed (" + brief + ")");
        return;
      }
      if (stalls) {
        Logger.warn("The autopilot could not start the game: the step " + step + " failed (" + brief + ")");
      } else {
        Logger.warn("The step " + step + " failed as the autopilot started the game (" + brief
            + "); the game got going, so it goes on");
      }
      gameFailed(listener, step, brief, details, false, stalls);
    } catch (Throwable e) {
      Logger.error("Could not report that the game failed to start", e);
    }
  }

  /**
   * The console's Start (RunGame) answered {@code error}, what processScript answered (null: it
   * started). In a session run by hand nothing more happens: a start that didn't get going was taken
   * back, so Start works again once the step is fixed. In the autopilot's own session (Autopilot.
   * isOwnSession) the start had told its waiting room the game began (bb:game-start), so nothing would
   * ever start it again: it is dealt with as the autopilot's own start that fails (gameStartFailed):
   * recorded, and when it didn't get going the session ends and the autopilot pauses, naming the step.
   * The console that pressed Start has the error already. Never throws.
   */
  private static void consoleStartFailed(GameListener listener, String error) {
    ScriptFailure failed = FAILED_RUN.get();
    FAILED_RUN.remove();
    // (nothing to deal with: it started, or there was no start to make, e.g. "started already")
    if (error == null || failed == null || !error.equals(failed.answer)) {
      return;
    }
    Long sessionId = instanceIdOf(listener);
    try {
      ScriptEngine running = engine;
      if (failed.replaced || listener == null || running == null || listener.engine != running || !Autopilot.isOwnSession(sessionId)) {
        return;
      }
      // (a start taken back and made again since, e.g. by the OnJoinStep: the game is under way)
      if (failed.stalled && running.get(START_COMMAND) != null) {
        return;
      }
      String step = failed.step != null ? failed.step : "initStep";
      Logger.warn("The console's start of the autopilot's session failed: the step " + step + " failed (" + failed.brief + ")"
          + (failed.stalled ? "" : "; the game got going, so it goes on"));
      gameFailed(listener, step, failed.brief, failed.details, true, failed.stalled);
    } catch (Throwable e) {
      Logger.error("Could not deal with the failed start of the autopilot's session", e);
    }
  }

  /**
   * Run the experiment's OnJoinStep or OnLeaveStep ({@code name}) for a player of the engine built as
   * {@code generation} (null: whichever runs), with {@code script} (e.g. onJoinStep.start('p1')). What
   * it says goes to every open console ({@code out}), tagged "source": name, as before. Nobody waits
   * on it, so what fails in it is dealt with as a step that fails on a timer (gameFailed), naming the
   * step that threw, else the OnJoinStep or OnLeaveStep itself: it is recorded (the consoles have
   * processScript's error). It stalls the game when it took a start back (an OnJoinStep that starts
   * the game once enough people are in, whose InitStep throws before it gets going), and when the
   * OnJoinStep fails while the game is yet to start with no waiting room to start it
   * (nothingElseStartsTheGame). Anything else leaves the game going on, e.g. a mistake in the
   * OnLeaveStep, which Breadboard's own waiting room runs for those who left as it starts the game.
   * Only logged once another engine has been built than the one the player was in.
   */
  private static void joinOrLeaveStep(String script, ThrottledWebSocketOut out, String name, Long generation) {
    // (the game it runs in: gameFailed leaves it alone if another engine has been built since)
    GameListener listener = engineGameListener;
    String failed = processScript(script, out, null, "source", name);
    ScriptFailure failure = FAILED_RUN.get();
    FAILED_RUN.remove();
    if (failed == null) {
      return;
    }
    if (generation != null && generation != engineGeneration) {
      Logger.warn("The " + name + " for a player of an engine that has since been replaced failed; the game running now goes on");
      return;
    }
    try {
      boolean inStep = failure != null && failure.step != null;
      boolean stalls = (failure != null && failure.stalled)
          || (Experiment.ON_JOIN_STEP_NAME.equals(name) && nothingElseStartsTheGame());
      gameFailed(listener, inStep ? failure.step : name, failure != null ? failure.brief : failed, null, true, stalls);
    } catch (Throwable e) {
      Logger.error("Could not report the failure of the " + name, e);
    }
  }

  /**
   * Whether the engine's game is yet to start with no waiting room set in it (autopilot.room,
   * groovy/autopilot.groovy) to start it. Nobody presses Start in the autopilot's own session, so then
   * only the OnJoinStep would (e.g. once enough people are in), and one that fails stalls the game.
   */
  private static boolean nothingElseStartsTheGame() {
    ScriptEngine running = engine;
    if (running == null || running.get(START_COMMAND) != null) {
      return false;
    }
    try {
      Object autopilot = running.get("autopilot");
      return !(autopilot instanceof groovy.lang.GroovyObject)
          || ((groovy.lang.GroovyObject) autopilot).getProperty("room") == null;
    } catch (Exception e) {
      Logger.warn("Could not tell whether the autopilot's waiting room is set: " + e);
      return false;
    }
  }

  /**
   * A step of the engine's game failed where no one would see it (stepFailed, gameStartFailed,
   * joinOrLeaveStep). It is recorded (the event "stepFailed": step, error), every open console is
   * told as processScript tells of an error ({"error": "Caught error: ...", "source": the step}), so
   * the Live session page shows it (unless processScript has told them: {@code consolesTold}), and,
   * when the failure stalls the game ({@code stalls}: it kept the next step from getting going; the
   * rule is above STEPS_STARTED), the autopilot's own session ends (endAutopilotSession). A session a
   * researcher runs goes on: they see the error and decide. Nothing for a step of an engine that has
   * been replaced since.
   */
  private static void gameFailed(GameListener listener, String step, String brief, String details,
                                 boolean consolesTold, boolean stalls) {
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
    // (for the autopilot's stall backstop, failureRecorded)
    lastFailure = new RecordedFailure(engineGeneration, instanceIdOf(listener), step, brief);
    if (!consolesTold) {
      ObjectNode frame = Json.newObject();
      frame.put("source", step);
      frame.put("error", "Caught error: " + details + "\n");
      toAllAdmins(frame);
    }
    if (stalls) {
      endAutopilotSession(listener, failedIn, step, brief);
    }
  }

  /**
   * The game of the engine's session failed and stalls (gameFailed). When it is the autopilot's own
   * session (Autopilot.gameFailed decides: one it opened, or was handed on Resume or a save; not one
   * a researcher launched or resumed by hand, even of its experiment), its participants would wait
   * for good and no new session would open: it ends as when its steps don't load (stepsFailedToLoad:
   * participants are told it had to stop because of a problem with the study (problemScreens), the
   * computer players and the game's timers stop, nothing more is recorded, the session is stopped),
   * with a sessionFailed event (step, line, error), and the autopilot pauses, saying where.
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
          // The data says the autopilot ended it, and why: stepFailed alone doesn't say whether the
          // failure stalled the game (one that didn't is recorded the same way), and nothing marked the end.
          trackEvent("sessionFailed", sessionFailedData(step, error));
          // The computer players first, as a rebuild does: a pick asked for once the timers are
          // cancelled starts a timer of its own while they are on, so a takeover as the participants'
          // pages close (autopilot.handleDeparture) would play the stopped game on, and its finish()
          // would make the session finished, no longer resumable.
          if (playerActions != null) {
            playerActions.turnAIOff();
          }
          stopSession(listener);
          endParticipants(sessionId, problemScreens(sessionId));
          cancelEngineTimers();
          eventTracker.disable();
          // The console tabs of the account running it show it stopped, as after Stop.
          User owner = listener.user == null ? null : User.findByEmail(listener.user.email);
          if (owner != null) {
            toAccount(owner.email, owner.toJson());
          }
          // and every other console hears the engine runs no session now
          engineChanged();
        }
      }
    });
  }

  // What briefError says of a failure at a line of the experiment's code: "line 71: what went wrong".
  private static final java.util.regex.Pattern AT_LINE = java.util.regex.Pattern.compile("^line (\\d+): (.*)$",
      java.util.regex.Pattern.DOTALL);

  /**
   * The data of sessionFailed, the event the autopilot's session ends with when a step failure stalls
   * its game (endAutopilotSession): {@code step}, {@code line} (left out when the error names none) and
   * {@code error}, what went wrong there; {@code brief} is briefError's "line N: what went wrong".
   */
  static LinkedHashMap<Object, Object> sessionFailedData(String step, String brief) {
    LinkedHashMap<Object, Object> data = new LinkedHashMap<Object, Object>();
    data.put("step", step);
    java.util.regex.Matcher at = brief == null ? null : AT_LINE.matcher(brief);
    if (at != null && at.matches()) {
      data.put("line", at.group(1));
      data.put("error", at.group(2));
    } else {
      data.put("error", brief);
    }
    return data;
  }

  // ---- what the autopilot's guards of its own session see and do (Autopilot.guardOwnSession) ----

  /** A step failure gameFailed recorded: in which engine build and session, the step, what went wrong. */
  static final class RecordedFailure {
    final long generation;
    final Long session;
    final String step;
    final String error;

    RecordedFailure(long generation, Long session, String step, String error) {
      this.generation = generation;
      this.session = session;
      this.step = step;
      this.error = error;
    }
  }

  // The last step failure recorded in the engine's game (gameFailed), whether it stalled the game or not.
  private static volatile RecordedFailure lastFailure = null;
  // How many steps have got going, on every thread (stepStarted): a step that does means the game moved on.
  private static final java.util.concurrent.atomic.AtomicLong STEPS_GOT_GOING = new java.util.concurrent.atomic.AtomicLong();

  /** How many steps have got going so far, all threads together (stepStarted). */
  static long stepsGotGoing() {
    return STEPS_GOT_GOING.get();
  }

  /** The game of {@code running} counts as started (gameStartedIn), since now unless it did already. */
  private static void markGameStarted(ScriptEngine running) {
    if (gameStartedIn != running) {
      gameStartedAt = System.currentTimeMillis();
    }
    gameStartedIn = running;
  }

  /** When the game of session {@code sessionId} started, if the engine runs it and its game has; else 0. */
  static long gameStartedAt(Long sessionId) {
    ScriptEngine running = engine;
    long at = gameStartedAt;
    return running != null && gameStartedIn == running && sessionId != null && sessionId.equals(instanceIdOf(engineGameListener))
        ? at : 0L;
  }

  /** The last step failure recorded in the game of session {@code sessionId}, if the engine runs it; else null. */
  static RecordedFailure failureRecorded(Long sessionId) {
    RecordedFailure failure = lastFailure;
    return failure != null && engine != null && failure.generation == engineGeneration && sessionId != null
        && sessionId.equals(failure.session) && sessionId.equals(instanceIdOf(engineGameListener)) ? failure : null;
  }

  /**
   * Whether the engine's game has anything to wait for: a choice offered to someone in the game
   * (PlayerActions; not one left to a player removed since, which nobody can make), a task on one of
   * the engine's timers (a timed step waiting for its time, a computer player's pick, a takeover, the
   * experiment's own timers) or on an AMT timer, or an input from a participant it listens for
   * (player.on in groovy/events.groovy: a form they have yet to finish, tie edits; not the chat, see
   * waitsFor). True when that can't be told: the stall backstop then ends nothing. A
   * step whose code is running right now is not seen.
   */
  static boolean somethingPending() {
    ScriptEngine running = engine;
    if (running == null) {
      return false;
    }
    try {
      Object g = running.get("g");
      Object actions = property(running.get("a"), "actions");
      if (!(actions instanceof Map) || !(g instanceof Graph)) {
        return true;
      }
      for (Object action : ((Map<?, ?>) actions).values()) {
        Object player = property(action, "player");
        if (!(player instanceof Vertex) || playerVertex(g, String.valueOf(((Vertex) player).getId())) != null) {
          return true;
        }
      }
      Object registered = property(running.get("timers"), "timers");
      if (!(registered instanceof Collection)) {
        return true;
      }
      for (Object timer : (Collection<?>) registered) {
        if (timer instanceof Timer && hasTaskScheduled((Timer) timer)) {
          return true;
        }
      }
      for (Timer timer : amtTimers) {
        if (hasTaskScheduled(timer)) {
          return true;
        }
      }
      return listensToPlayers(running);
    } catch (Exception e) {
      Logger.warn("Could not tell whether the game has anything to wait for: " + e);
      return true;
    }
  }

  private static Object property(Object scriptObject, String name) {
    return scriptObject instanceof groovy.lang.GroovyObject ? ((groovy.lang.GroovyObject) scriptObject).getProperty(name) : null;
  }

  // java.util.Timer keeps its tasks in a queue of its own and can't be asked about them; on Java 8 they
  // can be read through reflection. Null when a field can't be found: every timer then counts as busy.
  private static final java.lang.reflect.Field TIMER_QUEUE = accessibleField("java.util.Timer", "queue");
  private static final java.lang.reflect.Field QUEUE_TASKS = accessibleField("java.util.TaskQueue", "queue");
  private static final java.lang.reflect.Field QUEUE_SIZE = accessibleField("java.util.TaskQueue", "size");
  private static final java.lang.reflect.Field TASK_STATE = accessibleField("java.util.TimerTask", "state");
  // TimerTask.SCHEDULED: waiting for its time (not run yet, or repeating), not cancelled.
  private static final int TASK_SCHEDULED = 1;
  // The listeners scripts add to the shared event bus (events.groovy names a player's "__player-<id>-<event>").
  private static final java.lang.reflect.Field BUS_EVENTS = accessibleField(EventBus.class.getName(), "events");
  private static final String PLAYER_EVENT = "__player-";

  private static java.lang.reflect.Field accessibleField(String className, String name) {
    try {
      java.lang.reflect.Field field = Class.forName(className).getDeclaredField(name);
      field.setAccessible(true);
      return field;
    } catch (Throwable e) {
      Logger.warn("The autopilot can't tell whether a stalled game is waiting for something (" + className + "." + name + "): " + e);
      return null;
    }
  }

  /** Whether a timer has a task waiting for its time; true when that can't be read. */
  private static boolean hasTaskScheduled(Timer timer) throws Exception {
    if (TIMER_QUEUE == null || QUEUE_TASKS == null || QUEUE_SIZE == null || TASK_STATE == null) {
      return true;
    }
    Object queue = TIMER_QUEUE.get(timer);
    // (the timer's own lock, held only briefly: not while a task runs)
    synchronized (queue) {
      int size = QUEUE_SIZE.getInt(queue);
      Object[] tasks = (Object[]) QUEUE_TASKS.get(queue);
      for (int i = 1; i <= size && i < tasks.length; i++) {
        if (tasks[i] != null && TASK_STATE.getInt(tasks[i]) == TASK_SCHEDULED) {
          return true;
        }
      }
    }
    return false;
  }

  /** Whether a script waits for an input of a player in the game (not a computer player's: Client.isComputer). */
  private static boolean listensToPlayers(ScriptEngine running) throws Exception {
    Object g = running.get("g");
    if (BUS_EVENTS == null || !(g instanceof Graph)) {
      return true;
    }
    Map<?, ?> listened = (Map<?, ?>) BUS_EVENTS.get(eventBus);
    for (Vertex v : ((Graph) g).getVertices()) {
      String id = String.valueOf(v.getId());
      if (Client.isComputer(id)) {
        continue;
      }
      String prefix = PLAYER_EVENT + id + "-";
      for (Map.Entry<?, ?> entry : listened.entrySet()) {
        Object handler = entry.getValue();
        if (!String.valueOf(entry.getKey()).startsWith(prefix) || !(handler instanceof EventHandler)) {
          continue;
        }
        for (Object listener : ((EventHandler<?>) handler).closures) {
          if (waitsFor(listener, v)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * Whether a listener for an input of {@code player} is something the game waits for. A form's
   * (groovy/form.groovy: its owner has formsKey) only while the player has that form still to finish
   * (their private[formsKey] has the form's name: finishing it takes that out; a form takes its
   * listeners off when it is done, and this is for one left on all the same). The chat's
   * (groovy/chat.groovy: CHAT_KEY) never: it only keeps and records the messages, and nothing a
   * participant writes there moves the game on. Any other one always.
   */
  private static boolean waitsFor(Object listener, Vertex player) {
    Object owner = listener instanceof Closure ? ((Closure<?>) listener).getOwner() : null;
    if (!(owner instanceof groovy.lang.GroovyObject)) {
      return true;
    }
    groovy.lang.MetaClass meta = ((groovy.lang.GroovyObject) owner).getMetaClass();
    if (meta.hasProperty(owner, "formsKey") != null) {
      Object state = player.getProperty("private");
      Object forms = state instanceof Map ? ((Map<?, ?>) state).get(property(owner, "formsKey")) : null;
      return forms instanceof Map && ((Map<?, ?>) forms).containsKey(property(owner, "name"));
    }
    return meta.hasProperty(owner, "CHAT_KEY") == null;
  }

  /**
   * What the participants of a session see when the server stops while it runs (ServerStop): its
   * game lived in the server's memory and ends with it, and the next start marks the session stopped.
   * The "stopped" screen said the researcher stopped it, also when the server was only restarted. It
   * says what they can do, as the page for a session that isn't running does (ClientLogin.NOT_RUNNING):
   * once the server is back, the study's link leads to the session open then, which takes someone cut
   * short. A {@code prolific} participant opens the study again from Prolific, or returns it.
   */
  static ObjectNode serverStoppedScreen(boolean prolific) {
    ObjectNode frame = Json.newObject();
    ObjectNode player = frame.putObject("player");
    player.putObject("_system");
    player.put("finished", true);
    player.put("ended", "serverStopped");
    player.put("text", "<h3>This session has ended</h3><p>It ended because the study's server was stopped or restarted. "
        + (prolific
            ? "You can open the study again from Prolific in a few minutes to join the session that is open then. "
                + "If no session is open, please return the study on Prolific, and message the researcher there if you have any questions."
            : "If you were given a link to the study, you can open it again in a few minutes to join the session that is "
                + "open then; otherwise, thank you for taking part, and please contact the researcher if you have any questions.")
        + "</p>");
    return frame;
  }

  /**
   * The server is stopping while the engine runs session {@code sessionId} (ServerStop), which ends
   * with it; its status is left to the next start (Global.markInterruptedSessions). From now on a page
   * that connects to it is given its end screen, not a seat in the game (addClient), and the game
   * stops, as for Stop: the computer players go off, its timers are cancelled and the engine records
   * nothing more. It used to go on behind its participants until the process ended: a page that
   * reconnected meanwhile was put back into it (and recorded as connected after serverStopping), and
   * a round's time running out could finish the game, as if it had come to its end. Returns what each
   * participant is told, for endParticipants: serverStoppedScreen, in the words for Prolific to those
   * who came from there ({@code askDatabase}: whether the database may be asked that; not when it
   * doesn't answer). Never throws. Not under the engine lock, which a rebuild holds for seconds (and a
   * step stuck as it loads for good), while the whole stop has Global.SHUTDOWN_LIMIT_MS.
   */
  static java.util.function.Function<Client, ObjectNode> stopForServerStop(final Long sessionId, final boolean askDatabase) {
    java.util.function.Function<Client, ObjectNode> screens = new java.util.function.Function<Client, ObjectNode>() {
      public ObjectNode apply(Client client) {
        return serverStoppedScreen(client != null && fromProlific(client, askDatabase ? sessionId : null));
      }
    };
    if (sessionId == null) {
      return screens;
    }
    endingSessions.put(sessionId, screens);
    try {
      if (playerActions != null) {
        playerActions.turnAIOff();
      }
    } catch (Exception e) {
      Logger.warn("Stopping: could not turn the computer players off: " + e);
    }
    cancelEngineTimers();
    eventTracker.disable();
    return screens;
  }

  /**
   * What the participants of a session that ran out of time see (Autopilot's longest session). The
   * session finished, and the study gave them no completion code: a {@code prolific} participant
   * messages the researcher on Prolific about their submission (opening the study again would only
   * say they took part already).
   */
  static ObjectNode outOfTimeScreen(boolean prolific) {
    ObjectNode frame = Json.newObject();
    ObjectNode player = frame.putObject("player");
    player.putObject("_system");
    player.put("finished", true);
    player.put("ended", "outOfTime");
    player.put("text", "<h3>This session has ended</h3><p>It ran out of time. Thank you for taking part. "
        + (prolific
            ? "It ended before the study gave you a completion code, so please message the researcher on Prolific "
                + "about your submission."
            : "You can close this page; if you have any questions, please contact the researcher.")
        + "</p>");
    return frame;
  }

  /**
   * What the participants of the autopilot's own session see when it ends that session itself because
   * of a problem with the study: a step failure that stalls its game (endAutopilotSession, the stall
   * backstop) or steps that don't load. The "stopped" screen would say a researcher stopped it. A
   * {@code prolific} participant may open the study again from Prolific later, as the server's stop
   * says (the autopilot opens nothing until the researcher has fixed the study), or return it.
   */
  static ObjectNode problemScreen(boolean prolific) {
    ObjectNode frame = Json.newObject();
    ObjectNode player = frame.putObject("player");
    player.putObject("_system");
    player.put("finished", true);
    player.put("ended", "problem");
    player.put("text", "<h3>This session has ended</h3><p>It had to stop because of a problem with the study. Thank you for "
        + "taking part. "
        + (prolific
            ? "You can open the study again from Prolific later to join a new session, if one is open then. Otherwise, "
                + "please return the study on Prolific, and message the researcher there if you have any questions."
            : "You can close this page; if you have any questions, please contact the researcher.")
        + "</p>");
    return frame;
  }

  /**
   * The end screens of a session's participants, each in the words for Prolific to someone who came
   * from there (fromProlific, which may ask the session's data), as stopForServerStop chooses them:
   * {@code screen} given whether they did. Also for a page that comes back to the session later
   * (endedSessionScreen).
   */
  static java.util.function.Function<Client, ObjectNode> perParticipant(final Long sessionId,
                                                                         final java.util.function.Function<Boolean, ObjectNode> screen) {
    return new java.util.function.Function<Client, ObjectNode>() {
      public ObjectNode apply(Client client) {
        return screen.apply(client != null && fromProlific(client, sessionId));
      }
    };
  }

  /** The out-of-time screens of a session's participants (outOfTimeScreen, per participant). */
  static java.util.function.Function<Client, ObjectNode> outOfTimeScreens(Long sessionId) {
    return perParticipant(sessionId, new java.util.function.Function<Boolean, ObjectNode>() {
      public ObjectNode apply(Boolean prolific) {
        return outOfTimeScreen(prolific);
      }
    });
  }

  /** The problem screens of a session's participants (problemScreen, per participant). */
  static java.util.function.Function<Client, ObjectNode> problemScreens(Long sessionId) {
    return perParticipant(sessionId, new java.util.function.Function<Boolean, ObjectNode>() {
      public ObjectNode apply(Boolean prolific) {
        return problemScreen(prolific);
      }
    });
  }

  /**
   * The session is over for its participants (a researcher's Stop, the server's stop, the autopilot's
   * guards, a failure that ends it), with {@code frame} as their end screen: their pages say so
   * instead of a game that no longer answers, and keep saying so if they reconnect. Their sockets
   * are closed, the previews watching them too (which get the end first), so the engine, which may
   * still hold the game, sends them nothing more. Someone whose screen is their own end keeps it,
   * also when they come back: the experiment finished them, or gave them a completion code
   * (_system.recruitment). Their page is sent the end as "sessionOver", which it shows only when it
   * has no end of its own on the screen. Someone the game started without (leftOutAtStart) keeps
   * that screen as their last: it says what they should do.
   *
   * <p>Each participant's end is settled under their Client's monitor (Client.end), which updateGraph
   * holds, and remembered at once: the client update actor replaces a participant's last screen with
   * their game view every few hundred milliseconds, and one update landing between the end screen and
   * its being remembered (only after every participant had been told) made a page that came back
   * later show a frozen round instead of its end.
   */
  static void endParticipants(Long instanceId, final ObjectNode frame) {
    endParticipants(instanceId, new java.util.function.Function<Client, ObjectNode>() {
      public ObjectNode apply(Client client) {
        return frame;
      }
    });
  }

  /**
   * endParticipants(Long, ObjectNode), with each participant's end screen {@code screenFor} theirs.
   * Someone whose page comes back without a Client in the game (away when a rebuild of the engine
   * carried the session on, and not back since) gets theirs then (see endedSessionScreen).
   */
  static void endParticipants(Long instanceId, java.util.function.Function<Client, ObjectNode> screenFor) {
    if (instanceId == null) {
      return;
    }
    // (first, for such a page that comes back while the others are being told)
    sessionEnded(instanceId, screenFor);
    for (Client client : clients.values()) {
      if (client.experimentInstance != null && instanceId.equals(client.experimentInstance.id)) {
        endParticipant(client, screenFor.apply(client));
      }
    }
  }

  // One participant of endParticipants; nothing for one whose end is settled already (a page of theirs
  // that came back while the server's stop ran settled it: see addClient).
  private static void endParticipant(Client client, ObjectNode frame) {
    // Their player is looked up before their Client's monitor is taken, never under it: the lookup
    // waits for the graph's structureLock, whose holder may be updating this very participant's page
    // (the lock order is written at structureLock, in groovy/graph.groovy). Looked up under the
    // monitor, a Stop that came while the game added a tie deadlocked the engine.
    Vertex player = enginePlayer(client.id);
    JsonNode last;
    synchronized (client) {
      if (client.ended()) {
        return;
      }
      Vertex completed = completed(player);
      boolean keep = completed != null || ownEnd(client.lastFrame);
      if (completed != null) {
        try {
          // Their screen as it is, with the code: a change inside _system alone isn't sent by itself,
          // so a code given a moment ago may not have reached the page (or its last screen) yet.
          client.updateGraph(completed);
        } catch (Exception e) {
          Logger.debug("Could not show " + client.id + " their screen as the session ended: " + e);
        }
      }
      client.end(keep || client.leftOut ? null : frame);
      rememberLastScreens(Collections.singletonList(client));
      if (keep) {
        // The session is over all the same: the page stops reconnecting and keeps its own end
        // (pages from before 3.1 ignore "sessionOver" and keep their screen too).
        ObjectNode over = Json.newObject();
        over.put("sessionOver", frame);
        last = over;
      } else {
        last = frame;
      }
    }
    // Out of the game only now: a page of theirs that comes back meanwhile finds them ended (addClient).
    clients.remove(client.id, client);
    if (graphChangedListener != null) {
      graphChangedListener.removeClientListener(client);
    }
    // To the page in the seat, if any, and to the previews watching (a computer player's seat has only
    // those), each then closed: a socket left open would show a game that no longer answers.
    client.disconnect(last);
  }

  /**
   * The participant's player in the engine's game, or null; never throws. Waits for the graph's
   * structureLock (playerVertex): never ask while holding a Client's monitor.
   */
  private static Vertex enginePlayer(String clientId) {
    try {
      return engine == null ? null : playerVertex(engine.get("g"), clientId);
    } catch (Exception e) {
      return null;
    }
  }

  /** {@code v} when the experiment gave this player a completion code (_system.recruitment); else null. */
  private static Vertex completed(Vertex v) {
    try {
      Object system = v == null ? null : v.getProperty("_system");
      Object recruitment = system instanceof Map ? ((Map<?, ?>) system).get("recruitment") : null;
      return recruitment instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) recruitment).get("completed")) ? v : null;
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * The autopilot's guards end its own session {@code sessionId} (Autopilot.endOwnSession): its game
   * ran longer than the longest session allowed, or had nothing left to do after a step failed. Under
   * the engine lock, if the engine still runs that session live and {@code stillWanted} (asked there)
   * agrees, it ends as the other paths end a session: the computer players off first (see Stop),
   * {@code event} recorded, the session marked finished ({@code finished}: the autopilot opens the
   * next one after its pause, as when a game ends) or stopped, the participants told ({@code screens}
   * gives each theirs, see endParticipants), the game's timers cancelled and nothing more recorded,
   * and every console hears of it. Returns whether it ended it. Waits for the engine lock as long as
   * it takes: the autopilot calls it on a thread of its own.
   */
  static boolean endForGuard(Long sessionId, String event, LinkedHashMap<Object, Object> data,
                             java.util.function.Function<Client, ObjectNode> screens,
                             boolean finished, java.util.concurrent.Callable<Boolean> stillWanted) {
    synchronized (REBUILD_LOCK) {
      GameListener listener = engineGameListener;
      if (engine == null || sessionId == null || !sessionId.equals(instanceIdOf(listener))) {
        return false;
      }
      ExperimentInstance live = Autopilot.liveEngineSession();
      if (live == null || !sessionId.equals(live.id) || !wanted(stillWanted)) {
        return false;
      }
      if (playerActions != null) {
        playerActions.turnAIOff();
      }
      trackEvent(event, data);
      // Over before its participants are told, as for Stop: one told is out of the game at once, and a
      // page of theirs that came back while the session still counted as running was taken into it
      // again (a seat in the game that was ending, and a connection recorded after the end).
      if (finished) {
        // as when its game ends: the next session opens after the pause, every console is told
        finishSession(listener);
      } else {
        stopSession(listener);
      }
      endParticipants(sessionId, screens);
      cancelEngineTimers();
      eventTracker.disable();
      if (!finished) {
        // The console tabs of the account running it show it stopped, as after Stop.
        User owner = listener.user == null ? null : User.findByEmail(listener.user.email);
        if (owner != null) {
          toAccount(owner.email, owner.toJson());
        }
        engineChanged();
      }
      return true;
    }
  }

  private static boolean wanted(java.util.concurrent.Callable<Boolean> stillWanted) {
    try {
      return Boolean.TRUE.equals(stillWanted.call());
    } catch (Exception e) {
      Logger.error("Could not tell whether the autopilot's session is still to be ended", e);
      return false;
    }
  }

  /**
   * Mark the session a game listener runs finished, as the end of its game does (GameListener.finish);
   * never throws. Through a fresh copy when the game's own is out of date (changed meanwhile).
   */
  private static void finishSession(GameListener listener) {
    try {
      listener.finish();
      return;
    } catch (Exception e) {
      Logger.warn("Could not mark the session finished through the game's copy: " + e);
    }
    Long id = instanceIdOf(listener);
    ExperimentInstance fresh = id == null ? null : ExperimentInstance.findById(id);
    if (fresh == null || fresh.status == ExperimentInstance.Status.FINISHED) {
      engineChanged();
      return;
    }
    try {
      fresh.finish();
      fresh.save();
      listener.experimentInstance = fresh;
    } catch (Exception e) {
      Logger.error("Could not mark session " + id + " finished", e);
    }
    Autopilot.sessionFinished(fresh);
    engineChanged();
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
