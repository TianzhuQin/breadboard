package models;

import akka.actor.ActorRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Graph;
import com.tinkerpop.blueprints.Vertex;
import controllers.D3Utils;
import play.libs.Json;
import play.Logger;

import java.util.Map;

/**
 * One open admin console socket (one browser tab) watching the engine's network.
 *
 * <p>Every socket has its own Admin, registered on LogIn and removed when the socket closes
 * (ScriptBoard.registerAdmin / removeAdmin). It only forwards graph changes to its own socket:
 * OnJoinStep / OnLeaveStep are run once per player by ScriptBoard's step trigger, not by each Admin,
 * so they no longer run once per open tab, nor not at all when nobody is watching.
 */
public class Admin implements ClientListener {
  private volatile User user;
  private final ActorRef scriptBoardController;
  private final ThrottledWebSocketOut out;

  public Admin(User user, ActorRef scriptBoardController, ThrottledWebSocketOut out) {
    this.user = user;
    this.scriptBoardController = scriptBoardController;
    this.out = out;
  }

  /** Send this account's current User JSON to this socket. */
  public void update() {
    User current = user;
    if (current == null) {
      return;
    }
    User fresh = User.findByEmail(current.email);
    write((fresh != null ? fresh : current).toJson());
  }

  /** Write one frame to this socket. A failing socket must not stop the others from being updated. */
  public void write(JsonNode frame) {
    try {
      out.write(frame);
    } catch (Exception e) {
      Logger.debug("Could not write to an admin socket: " + e);
    }
  }

  public void graphChanged(Graph wholeGraph) {
    ObjectNode jsonOutput = Json.newObject();

    ObjectNode graph = D3Utils.graphToJsonString(wholeGraph);
    jsonOutput.put("graph", graph);

    write(jsonOutput);
  }

  /** A player was added to the live graph: {@code addNode}; its properties follow as changes. */
  public void vertexAdded(Vertex vertex) {
    ObjectNode jsonOutput = Json.newObject();
    jsonOutput.put("action", "addNode");
    jsonOutput.put("id", vertex.getId().toString());
    write(jsonOutput);
  }

  /** {@code addNode} followed by one {@code nodePropertyChanged} per property, for a replay. */
  public void replayVertex(Vertex vertex) {
    vertexAdded(vertex);
    for (String key : vertex.getPropertyKeys()) {
      vertexPropertyChanged(vertex, key, null, vertex.getProperty(key));
    }
  }

  /**
   * Kept for callers of the old signature. {@code runOnJoin == false} (a replay) sends the
   * properties too. The Admin no longer runs OnJoinStep itself either way.
   */
  public void vertexAdded(Vertex vertex, Boolean runOnJoin) {
    if (Boolean.TRUE.equals(runOnJoin)) {
      vertexAdded(vertex);
    } else {
      replayVertex(vertex);
    }
  }

  public void vertexRemoved(Vertex vertex) {
    ObjectNode jsonOutput = Json.newObject();
    jsonOutput.put("action", "removeNode");
    jsonOutput.put("id", vertex.getId().toString());
    write(jsonOutput);
  }

  /** Kept for callers of the old signature; the Admin no longer runs OnLeaveStep itself. */
  public void vertexRemoved(Vertex vertex, Boolean runOnLeave) {
    vertexRemoved(vertex);
  }

  public void vertexPropertyChanged(Vertex vertex, String key, Object oldValue, Object setValue) {
    if (key.equals("private")) {
      Object privateValue = vertex.getProperty("private");
      if (privateValue instanceof Map) {
        Map newMap = (Map) privateValue;
        // Private keys are flattened into the player's own keys (the admin sees everything).
        for (Object k : newMap.keySet()) {
          ObjectNode jsonOutput = Json.newObject();
          jsonOutput.put("action", "nodePropertyChanged");
          jsonOutput.put("id", vertex.getId().toString());
          jsonOutput.put("key", k.toString());
          // Using gson here leads to a StackOverflowError due to a circular reference
          jsonOutput.put("value", Json.toJson(newMap.get(k)));
          write(jsonOutput);
        }
      }
    } else {
      ObjectNode jsonOutput = Json.newObject();
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

    // If there are properties on the edge, notify the listener
    for (String key : edge.getPropertyKeys()) {
      edgePropertyChanged(edge, key, edge.getProperty(key));
    }
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
    // equals, not ==: the key is not always an interned literal. A null value is sent as null; its
    // toString() used to throw back into the script that set it.
    if (("inProps".equals(key) || "outProps".equals(key)) && edge.getProperty(key) instanceof Map) {
      Map props = (Map) edge.getProperty(key);
      for (Object propKey : props.keySet()) {
        ObjectNode jsonOutput = Json.newObject();
        jsonOutput.put("action", "linkPropertyChanged");
        jsonOutput.put("id", edge.getId().toString());
        jsonOutput.put("key", propKey.toString());
        jsonOutput.put("value", asText(props.get(propKey)));
        write(jsonOutput);
      }
    } else {
      ObjectNode jsonOutput = Json.newObject();
      jsonOutput.put("action", "linkPropertyChanged");
      jsonOutput.put("id", edge.getId().toString());
      jsonOutput.put("key", key);
      jsonOutput.put("value", asText(setValue));
      write(jsonOutput);
    }
  }

  private static String asText(Object value) {
    return value == null ? null : value.toString();
  }

  public void edgePropertyRemoved(Edge edge, String key) {
    ObjectNode jsonOutput = Json.newObject();

    jsonOutput.put("action", "linkPropertyRemoved");
    jsonOutput.put("id", edge.getId().toString());
    jsonOutput.put("key", key);

    write(jsonOutput);
  }

  public ThrottledWebSocketOut getOut() {
    return this.out;
  }

  public User getUser() {
    return this.user;
  }

  /** The account signed in on this socket, refreshed when it logs in again. */
  void setUser(User user) {
    this.user = user;
  }

  public ActorRef getScriptBoardController() {
    return scriptBoardController;
  }
}
