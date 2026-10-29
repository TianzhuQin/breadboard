package models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Graph;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.impls.tg.TinkerGraph;
import com.tinkerpop.blueprints.util.wrappers.event.EventGraph;
import controllers.D3Utils;
import play.Logger;
import play.db.ebean.Model;
import play.libs.Json;
import play.mvc.WebSocket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class Client extends Model {
  public String id;
  // The socket of the page that holds this participant's seat (see connect): what it sends acts for them.
  public WebSocket.In<JsonNode> in;
  public ThrottledWebSocketOut out;
  public ExperimentInstance experimentInstance;
  private int styleHash;
  // The last full update this participant got. ScriptBoard keeps it for a while after the session
  // ends, so a page that reconnects later gets its final screen back (see ScriptBoard.addClient).
  public volatile ObjectNode lastFrame;
  // The autopilot's launch left them out of the game, as they had been away too long
  // (ScriptBoard.leftOutAtStart): lastFrame is the screen that tells them so.
  public volatile boolean leftOut;

  // The page that holds the seat, and researchers' previews watching it: they get every frame the
  // seat holder gets and can't act. Watchers stay when the participant's page reconnects.
  private volatile Connection seat;
  private final List<Connection> watchers = new CopyOnWriteArrayList<Connection>();
  // Held while the seat changes hands, so two sockets arriving or closing at once can't both take it.
  private final Object seatLock = new Object();
  // Held by ScriptBoard.recordConnection while it records who is in the seat, so those records
  // follow each other in order; and what it recorded last (null before the first).
  private final Object recordLock = new Object();
  private Presence recordedPresence;
  // A computer player's (see isComputer): nobody holds its seat, which the computer plays, and a
  // researcher's preview of it only watches. One used to take the seat, which switched the computer off.
  private final boolean computer;
  // The participant's own page (not a researcher's preview) has held the seat in this session. From
  // then on a preview never counts as them being there (see presence): it only watches their page,
  // or stands in for them while it is away. Standing in used to be a mark on the one preview that
  // watched as their page went, so any newer preview of them (the console opens new ones all the
  // time: a tile opened full size, a reload, the Preview page visited again) counted as them coming
  // back: the computer was switched off, and nobody played the seat while it stayed open.
  private volatile boolean ownPageHeld;

  // The frames that tell a page what became of its socket (see connect and seatAWatcher).
  private static final ObjectNode REPLACED = Json.newObject().put("bbClosed", "replaced");
  private static final ObjectNode WATCHING = Json.newObject().put("bbWatching", true);
  private static final ObjectNode NOT_WATCHING = Json.newObject().put("bbWatching", false);

  /**
   * One socket of this participant: the page that holds the seat, or a researcher's preview watching
   * it. It remembers when it was last heard from (ScriptBoard.closeSilentPages), and the last full
   * view written to it, so the same one isn't sent again.
   */
  public static final class Connection {
    public final WebSocket.In<JsonNode> in;
    public final ThrottledWebSocketOut out;
    // A researcher's preview (ClientController: a signed-in researcher, or a preview token), not the
    // participant's own page.
    public final boolean preview;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long lastHeard = System.currentTimeMillis();
    private volatile boolean pinged;
    // The text of the full view written last; null once any other frame has been written since.
    private String lastView;

    public Connection(WebSocket.In<JsonNode> in, ThrottledWebSocketOut out, boolean preview) {
      this.in = in;
      this.out = out;
      this.preview = preview;
    }

    public boolean isOpen() {
      return !closed.get();
    }

    /** It sent something at {@code now} (ms); {@code ping}: a {"action": "Ping"}. */
    public void heard(boolean ping, long now) {
      lastHeard = now;
      if (ping) {
        pinged = true;
      }
    }

    public long lastHeard() {
      return lastHeard;
    }

    /**
     * Whether it has pinged (pages do every few seconds from 3.1 on) and then sent nothing for more
     * than {@code limitMs} by {@code now}: its connection is gone, though the socket never closed.
     * A page that never pings never counts as silent.
     */
    public boolean silent(long now, long limitMs) {
      return pinged && isOpen() && now - lastHeard > limitMs;
    }

    // True the first time only: its close and the check for silence may both report it.
    boolean markClosed() {
      return closed.compareAndSet(false, true);
    }

    /**
     * Write a frame with the player's view ({@code view}: the text of the view alone). One that shows
     * the same as the last frame written is skipped, unless {@code always}: participants get a view
     * every few hundred milliseconds, often unchanged, and a page on a weak connection fell further
     * and further behind. Nothing is written to a closed socket.
     */
    synchronized void writeView(JsonNode frame, String view, boolean always) {
      if (!isOpen() || out == null) {
        return;
      }
      if (!always && view.equals(lastView)) {
        return;
      }
      lastView = view;
      out.write(frame);
    }

    /** Write any other frame: the next view is sent whatever it shows, as the page may have changed. */
    synchronized void write(JsonNode frame) {
      if (!isOpen() || out == null) {
        return;
      }
      lastView = null;
      out.write(frame);
    }

    /** Write a frame that changes nothing on the page's screen (e.g. the answer to a ping). */
    public synchronized void writeAside(JsonNode frame) {
      if (isOpen() && out != null) {
        out.write(frame);
      }
    }

    // Closed by the server: no frame goes to it any more, except {@code last}, first (under the same
    // lock as the writes, so none slips in after it).
    synchronized void close(JsonNode last) {
      markClosed();
      if (out == null) {
        return;
      }
      try {
        if (last != null) {
          out.write(last);
        }
        out.close();
      } catch (Exception e) {
        Logger.debug("Could not close a participant's socket: " + e);
      }
    }
  }

  /** What became of a socket that connected for a participant who has a Client already (see connect). */
  public enum Arrival {
    /** It holds the seat, which no open page held: the participant (or a preview) comes back. */
    SEATED,
    /** It holds the seat, taken from another open page, which was told so and closed. */
    TOOK_OVER,
    /** It holds the seat: the participant is back, and the preview that held it watches from now on. */
    PREVIEW_WATCHES,
    /** A researcher's preview of a player whose own page holds the seat, or of a computer player: it only watches. */
    WATCHING
  }

  /** Who is in the seat, as the session counts it (see ScriptBoard.recordConnection). */
  public enum Presence {
    /**
     * Nobody: their page is away. A researcher's preview may hold the seat of someone whose own page
     * has held it (it stands in: it may act for them), but they are away all the same: the computer
     * takes their seat after the usual wait, and a waiting room doesn't count them.
     */
    AWAY,
    /**
     * A researcher's preview holds a seat no page of the participant's has held in this session (a
     * test player, played through previews): it plays it, and they count as there.
     */
    PREVIEW,
    /** The participant's own page. */
    HERE
  }

  public Client(String id, ExperimentInstance experimentInstance, WebSocket.In<JsonNode> in, ThrottledWebSocketOut out) {
    this(id, experimentInstance, new Connection(in, out, false));
  }

  /** A participant whose first socket is yet to come (see connect). */
  public Client(String id, ExperimentInstance experimentInstance) {
    this.id = id;
    this.experimentInstance = experimentInstance;
    this.computer = isComputer(id);
  }

  /** A participant's first socket, which holds the seat. */
  public Client(String id, ExperimentInstance experimentInstance, Connection first) {
    this(id, experimentInstance);
    connect(first);
  }

  /**
   * Whether this id is a computer player's: g.addAI makes _1, _2, …, and nobody may sign in with an id
   * starting with "_" (ClientLogin).
   */
  public static boolean isComputer(String id) {
    return id != null && id.startsWith("_");
  }

  /** Whether this is a computer player, whose seat nobody holds (see isComputer). */
  public boolean computer() {
    return computer;
  }

  // (under seatLock)
  private void take(Connection holder) {
    this.seat = holder;
    this.in = holder.in;
    this.out = holder.out;
    if (!holder.preview) {
      ownPageHeld = true;
    }
  }

  /**
   * Another socket for this participant. It takes the seat, unless it is a researcher's preview and
   * the participant's own page holds it: a preview never displaces them, and watches instead ({"bbWatching":
   * true}, then everything their page gets). Whoever held the seat before: when it was another open
   * page of the participant, or a preview replaced by a preview, it is told ({"bbClosed": "replaced"})
   * and closed, so it doesn't reconnect and take the seat back, as two tabs used to, for ever; a
   * preview that held it for a participant who comes back watches from now on ({"bbWatching": true});
   * a socket that had closed already is just left. Any socket of a computer player only watches
   * what the computer does. A preview that takes the seat of someone whose own page has held it only
   * stands in for them (see presence).
   */
  public Arrival connect(Connection incoming) {
    synchronized (seatLock) {
      if (computer) {
        return watch(incoming);
      }
      Connection previous = seat;
      if (previous == null || previous == incoming || !previous.isOpen()) {
        take(incoming);
        return Arrival.SEATED;
      }
      if (incoming.preview && !previous.preview) {
        return watch(incoming);
      }
      take(incoming);
      if (previous.preview && !incoming.preview) {
        // (it may have stood in for them: it only watches again)
        previous.writeAside(WATCHING);
        watchers.add(previous);
        return Arrival.PREVIEW_WATCHES;
      }
      previous.close(REPLACED);
      return Arrival.TOOK_OVER;
    }
  }

  // (under seatLock) Told first, before any of the player's frames reach it.
  private Arrival watch(Connection incoming) {
    incoming.writeAside(WATCHING);
    watchers.add(incoming);
    return Arrival.WATCHING;
  }

  /**
   * A socket of this participant closed, or was closed by the server. True when it was the open
   * socket that held the seat, which is empty now. A watcher's just goes; one closed already (a
   * replaced socket, or reported twice) changes nothing.
   */
  public boolean closed(Connection connection) {
    synchronized (seatLock) {
      if (!connection.markClosed()) {
        return false;
      }
      watchers.remove(connection);
      return seat == connection;
    }
  }

  /**
   * The seat is empty (the participant's page went): a researcher's preview watching it takes it, the
   * one that came last, and is told it no longer only watches ({"bbWatching": false}). Its choices
   * count, as a preview's always did, until their own page comes back; but it only stands in
   * (Presence.AWAY): they have left all the same, so the computer takes their seat after the usual
   * wait, and a waiting room no longer counts them. It used to count as them being back, and nobody
   * played the seat for as long as the preview stayed open. Null when the seat is held again, no
   * open preview watches, or the seat is a computer player's.
   */
  public Connection seatAWatcher() {
    synchronized (seatLock) {
      if (computer || seatOpen()) {
        return null;
      }
      for (int i = watchers.size() - 1; i >= 0; i--) {
        Connection watcher = watchers.get(i);
        if (watcher.isOpen()) {
          watchers.remove(i);
          take(watcher);
          watcher.writeAside(NOT_WATCHING);
          return watcher;
        }
      }
      return null;
    }
  }

  /** The socket that holds the seat (open or not). */
  public Connection seat() {
    return seat;
  }

  /** Whether an open socket holds the seat: the participant's page, or a preview. */
  public boolean seatOpen() {
    Connection holder = seat;
    return holder != null && holder.isOpen();
  }

  /**
   * Who is in the seat now: the participant's own page (HERE); a researcher's preview, on a seat no
   * page of theirs has held in this session (PREVIEW); or nobody (AWAY), any preview standing in for
   * someone whose own page has held the seat included, whichever preview it is and however it came.
   */
  public Presence presence() {
    Connection holder = seat;
    if (holder == null || !holder.isOpen()) {
      return Presence.AWAY;
    }
    if (!holder.preview) {
      return Presence.HERE;
    }
    return ownPageHeld ? Presence.AWAY : Presence.PREVIEW;
  }

  /** Whether a researcher's preview holds the seat, as a player or standing in for them. */
  public boolean previewHolds() {
    Connection holder = seat;
    return holder != null && holder.isOpen() && holder.preview;
  }

  /** Whether this socket holds the seat now, open: what it sends acts for the participant. */
  public boolean holdsSeat(Connection connection) {
    return connection != null && connection == seat && connection.isOpen();
  }

  /** Whether this socket is a researcher's preview watching the seat holder. */
  public boolean watches(Connection connection) {
    return watchers.contains(connection);
  }

  /** The previews watching now. */
  public List<Connection> watchers() {
    return new ArrayList<Connection>(watchers);
  }

  /** Every socket of this participant: the seat holder's (if any), then the watchers'. */
  public List<Connection> connections() {
    List<Connection> all = new ArrayList<Connection>();
    Connection holder = seat;
    if (holder != null) {
      all.add(holder);
    }
    all.addAll(watchers);
    return all;
  }

  Object recordLock() {
    return recordLock;
  }

  // What was recorded last (null before the first record); {@code now} is from now on (under recordLock).
  Presence recordPresence(Presence now) {
    Presence before = recordedPresence;
    recordedPresence = now;
    return before;
  }

  public synchronized void updateGraph(Vertex me) {
    EventGraph subGraph = subGraphOf(me);
    writeGraph(subGraph.getVertex(me.getId()), subGraph);
  }

  /** Send one socket the player's view as it is now: a preview that starts watching. */
  public synchronized void showTo(Connection connection, Vertex me) {
    EventGraph subGraph = subGraphOf(me);
    ObjectNode view = view(subGraph.getVertex(me.getId()), subGraph);
    connection.writeView(view, view.toString(), false);
  }

  // What the participant may see of the network: themselves, their neighbors and their ties.
  private static EventGraph subGraphOf(Vertex me) {

    // Create an in-memory graph to store the sub-graph in. The participant keeps their own id;
    // everyone else is added under ClientView.publicId (a random alias by default), so a browser
    // never learns another participant's client id, which used to double as their login.
    TinkerGraph inMemoryGraph = new TinkerGraph();
    EventGraph subGraph = new EventGraph(inMemoryGraph);
    Vertex m = subGraph.addVertex(me.getId());

    // Private keys overwrite public properties so let's get private keys, if they exist
    HashSet<String> privateKeys = new HashSet<String>();
    if (me.getPropertyKeys().contains("private")) {
      if (me.getProperty("private") instanceof Map) {
        Map privateMap = (Map) me.getProperty("private");
        for (Object key : privateMap.keySet()) {
          privateKeys.add(key.toString());
          m.setProperty(key.toString(), privateMap.get(key));
        }
      }
    }

    // Add client vertex with all private properties
    for (String key : me.getPropertyKeys()) {
      if ((!key.equals("private")) && (!privateKeys.contains(key))) {
        if (me.getProperty(key) != null) {
          m.setProperty(key.toString(), me.getProperty(key));
        } else {
          Logger.debug("me.getProperty(key) == null");
        }
      }
    }

    // Get all neighbors and add them, with only the properties neighbors may see
    Map<Object, Vertex> inSubGraph = new HashMap<Object, Vertex>();
    List<Vertex> neighbors = new ArrayList<Vertex>();
    inSubGraph.put(me.getId(), m);
    for (Vertex v : me.getVertices(Direction.BOTH)) {
      if (inSubGraph.containsKey(v.getId())) {
        continue;
      }
      Vertex neighbor = subGraph.addVertex(ClientView.publicId(v.getId()));
      inSubGraph.put(v.getId(), neighbor);
      neighbors.add(v);
      for (String key : v.getPropertyKeys()) {
        if (ClientView.neighborMaySee(key)) {
          neighbor.setProperty(key.toString(), v.getProperty(key));
        }
      }
    }

    // Now edges
    // IN edges, handling inProps
    for (Edge e : me.getEdges(Direction.IN, "connected")) {

      Vertex outVertex = inSubGraph.get(e.getVertex(Direction.OUT).getId());
      Vertex inVertex = inSubGraph.get(e.getVertex(Direction.IN).getId());


      if (outVertex != null && inVertex != null) {
        Edge inE = subGraph.addEdge(e.getId(), outVertex, inVertex, "connected");
        HashSet<String> inPropKeys = new HashSet<String>();
        if (e.getPropertyKeys().contains("inProps")) {
          if (e.getProperty("inProps") instanceof Map) {
            Map inProps = (Map) e.getProperty("inProps");
            for (Object key : inProps.keySet()) {
              inPropKeys.add(key.toString());
              inE.setProperty(key.toString(), inProps.get(key));
            }
          }
        }

        for (String key : e.getPropertyKeys()) {
          if ((!key.equals("outProps")) && (!key.equals("inProps")) && (!inPropKeys.contains(key))) {
            inE.setProperty(key.toString(), e.getProperty(key));
          }
        }
      }
    }

    // OUT edges, handling outProps
    for (Edge e : me.getEdges(Direction.OUT, "connected")) {

      Vertex outVertex = inSubGraph.get(e.getVertex(Direction.OUT).getId());
      Vertex inVertex = inSubGraph.get(e.getVertex(Direction.IN).getId());


      if (outVertex != null && inVertex != null) {
        Edge outE = subGraph.addEdge(e.getId(), outVertex, inVertex, "connected");
        HashSet<String> outPropKeys = new HashSet<String>();
        if (e.getPropertyKeys().contains("outProps")) {
          if (e.getProperty("outProps") instanceof Map) {
            Map outProps = (Map) e.getProperty("outProps");
            for (Object key : outProps.keySet()) {
              outPropKeys.add(key.toString());
              outE.setProperty(key.toString(), outProps.get(key));
            }
          }
        }

        for (String key : e.getPropertyKeys()) {
          if ((!key.equals("outProps")) && (!key.equals("inProps")) && (!outPropKeys.contains(key))) {
            outE.setProperty(key.toString(), e.getProperty(key));
          }
        }
      }
    }

    // Ties among the participant's neighbors, when the experiment shows them. Their per-endpoint
    // inProps / outProps belong to those two players and are not sent.
    if (ClientView.getVisibility() == ClientView.Visibility.EGO_TIES) {
      for (Vertex alter : neighbors) {
        for (Edge e : alter.getEdges(Direction.BOTH, "connected")) {
          Vertex outVertex = inSubGraph.get(e.getVertex(Direction.OUT).getId());
          Vertex inVertex = inSubGraph.get(e.getVertex(Direction.IN).getId());
          if (outVertex == null || inVertex == null || outVertex == m || inVertex == m
              || subGraph.getEdge(e.getId()) != null) {
            continue;
          }
          Edge tie = subGraph.addEdge(e.getId(), outVertex, inVertex, "connected");
          for (String key : e.getPropertyKeys()) {
            if (!key.equals("outProps") && !key.equals("inProps")) {
              tie.setProperty(key, e.getProperty(key));
            }
          }
        }
      }
    }

    return subGraph;
  }

  public void writeGraph(Vertex me, Graph graph) {
    ObjectNode jsonOutput = view(me, graph);
    // Each socket is sent it only if its last frame showed something else: a page that just
    // connected, or a preview that just started watching, gets the whole view.
    String view = jsonOutput.toString();

    boolean newStyle = false;
    int hash = experimentInstance.experiment.getStyle().hashCode();
    if (styleHash != hash) {
      styleHash = hash;
      jsonOutput.put("style", experimentInstance.experiment.getStyle());
      newStyle = true;
    }

    lastFrame = jsonOutput;
    Connection holder = seat;
    if (holder != null) {
      holder.writeView(jsonOutput, view, newStyle);
    }
    for (Connection watcher : watchers) {
      watcher.writeView(jsonOutput, view, newStyle);
    }
  }

  // {"graph": what they see of the network, "player": their own vertex's properties}.
  private static ObjectNode view(Vertex me, Graph graph) {
    ObjectNode jsonOutput = Json.newObject();

    ObjectNode jsonGraph = D3Utils.graphToJsonString(graph);
    jsonOutput.put("graph", jsonGraph);

    // Write client vertex properties to "client" object
    ObjectNode client = Json.newObject();

    for (String key : me.getPropertyKeys()) {
      client.put(key, Json.toJson(me.getProperty(key)));
    }

    jsonOutput.put("player", client);
    return jsonOutput;
  }

  /** Write a frame to the page that holds the seat and to every preview watching it. */
  public void write(JsonNode frame) {
    Connection holder = seat;
    if (holder != null) {
      holder.write(frame);
    }
    for (Connection watcher : watchers) {
      watcher.write(frame);
    }
  }

  public void graphChanged(Graph wholeGraph) {
    ObjectNode jsonOutput = Json.newObject();

    ObjectNode graph = D3Utils.graphToJsonString(wholeGraph);
    jsonOutput.put("graph", graph);

    write(jsonOutput);
  }

  public void vertexAdded(Vertex vertex) {
    ObjectNode jsonOutput = Json.newObject();

    jsonOutput.put("action", "addNode");
    jsonOutput.put("id", vertex.getId().toString());
    write(jsonOutput);

    for (String key : vertex.getPropertyKeys()) {
      vertexPropertyChanged(vertex, key, vertex.getProperty(key));
    }
  }

  public void vertexRemoved(Vertex vertex) {
    ObjectNode jsonOutput = Json.newObject();

    jsonOutput.put("action", "removeNode");
    jsonOutput.put("id", vertex.getId().toString());

    write(jsonOutput);
  }

  public void vertexPropertyChanged(Vertex vertex, String key, Object setValue) {
    ObjectNode jsonOutput = Json.newObject();

    // Only send "text" and "choices" properties to the player whose text or choices is changing
    if (key.equals("text")) {
      if (vertex.getId().toString().equals(id)) {
        jsonOutput.put("text", Json.toJson(setValue));
        write(jsonOutput);
      }
    } else if (key.equals("choices")) {
      if (vertex.getId().toString().equals(id)) {
        jsonOutput.put("choices", Json.toJson(setValue));
        write(jsonOutput);
      }
    } else if (key.startsWith("private")) {
      // private key should be sent, without the containing private key, to vertex player only
      if (vertex.getId().toString().equals(id)) {
        String privateKey = key.substring(7);
        jsonOutput.put("action", "nodePropertyChanged");
        jsonOutput.put("id", vertex.getId().toString());
        jsonOutput.put("key", privateKey);
        jsonOutput.put("value", Json.toJson(setValue));
        write(jsonOutput);
      }
    } else {
      jsonOutput.put("action", "nodePropertyChanged");
      jsonOutput.put("id", vertex.getId().toString());
      jsonOutput.put("key", key);
      jsonOutput.put("value", Json.toJson(setValue));
      write(jsonOutput);
    }
  }

  public void vertexPropertyRemoved(Vertex vertex, String key) {
    ObjectNode jsonOutput = Json.newObject();

    jsonOutput.put("action", "nodePropertyRemoved");
    jsonOutput.put("id", vertex.getId().toString());
    jsonOutput.put("key", key);

    write(jsonOutput);
  }

  public void edgeAdded(Edge edge) {
    ObjectNode jsonOutput = Json.newObject();

    jsonOutput.put("action", "addLink");
    jsonOutput.put("id", edge.getId().toString());
    jsonOutput.put("source", edge.getVertex(Direction.OUT).getId().toString());
    jsonOutput.put("target", edge.getVertex(Direction.IN).getId().toString());
    jsonOutput.put("value", edge.getLabel());

    write(jsonOutput);
  }

  public void edgeRemoved(Edge edge) {
    ObjectNode jsonOutput = Json.newObject();

    jsonOutput.put("action", "removeLink");
    jsonOutput.put("id", edge.getId().toString());
    jsonOutput.put("source", edge.getVertex(Direction.OUT).getId().toString());
    jsonOutput.put("target", edge.getVertex(Direction.IN).getId().toString());

    write(jsonOutput);
  }

  public void edgePropertyChanged(Edge edge, String key, Object setValue) {
    ObjectNode jsonOutput = Json.newObject();

    jsonOutput.put("action", "linkPropertyChanged");
    jsonOutput.put("id", edge.getId().toString());
    jsonOutput.put("key", key);
    jsonOutput.put("value", setValue.toString());

    write(jsonOutput);
  }

  public void edgePropertyRemoved(Edge edge, String key) {
    ObjectNode jsonOutput = Json.newObject();

    jsonOutput.put("action", "linkPropertyRemoved");
    jsonOutput.put("id", edge.getId().toString());
    jsonOutput.put("key", key);

    write(jsonOutput);
  }

  public void send (String eventName, Object ...data) {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode json = Json.newObject();
    json.put("eventName", eventName);
    json.put("data", Json.toJson(data));
    // JsonNode json = mapper.convertValue(data, JsonNode.class);
    write(json);
  }

  public ObjectNode toJson() {
    ObjectNode client = Json.newObject();
    client.put("style", experimentInstance.experiment.getStyle());
    return client;
  }

  public String toString() {
    return "Client(" + id + ")";
  }

  /** Close every socket of this participant, the previews watching included (their session is over). */
  public void disconnect () {
    List<Connection> all;
    synchronized (seatLock) {
      all = connections();
      watchers.clear();
    }
    for (Connection connection : all) {
      connection.close(null);
    }
  }

}
