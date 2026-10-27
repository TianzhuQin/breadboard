package models;

import com.tinkerpop.blueprints.Direction;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Graph;
import com.tinkerpop.blueprints.Vertex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EventGraphChangedListener implements BreadboardGraphChangedListener {
  private Graph graph;
  private final java.util.concurrent.CopyOnWriteArrayList<ClientListener> adminListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
  // Instance-scoped (was `static`): a static map was shared by every listener / every
  // ScriptBoard, so clients from one game/user leaked into another's dispatch and the
  // map could never be safely cleared. Per-instance state is cleared on reload via
  // ScriptBoard.disconnectClients -> removeClientListener.
  private final Map<String, Client> clientListeners = new java.util.concurrent.ConcurrentHashMap<>();

  public EventGraphChangedListener(Graph graph) {
    this.graph = graph;
  }

  public void addAdminListener(ClientListener adminListener) {
    adminListeners.addIfAbsent(adminListener);
  }

  @Override
  public void removeAdminListener(ClientListener adminListener) {
    adminListeners.remove(adminListener);
  }

  public List<ClientListener> getAdminListeners() {
    return this.adminListeners;
  }

  @Override
  public void setGraph(Graph g) {
    this.graph = g;
  }

  public void addClientListener(Client clientListener) {
    clientListeners.put(clientListener.id, clientListener);
  }

  @Override
  public void removeClientListener(Client clientListener) {
    if (clientListener != null) {
      clientListeners.remove(clientListener.id);
    }
  }

  // Exposed for tests/diagnostics (mirrors IteratedBreadboardGraphChangedListener).
  public Map<String, Client> getClientListeners() {
    return this.clientListeners;
  }

  @Override
  public void edgeAdded(Edge edge) {
    //Logger.debug("EventGraphChangedListener edgeAdded");
    for (ClientListener al : adminListeners)
      al.edgeAdded(edge);

    clientEdgeChanged(edge);
  }

  @Override
  public void edgePropertyChanged(Edge edge, String key, Object oldValue, Object setValue) {
    for (ClientListener al : adminListeners)
      al.edgePropertyChanged(edge, key, setValue);

    clientEdgePropertyChanged(edge, key, setValue);
  }

  @Override
  public void edgePropertyRemoved(Edge edge, String key, Object removedValue) {
    for (ClientListener al : adminListeners)
      al.edgePropertyRemoved(edge, key);

    clientEdgePropertyChanged(edge, key, removedValue);
  }

  @Override
  public void edgeRemoved(Edge edge, Map<String, Object> props) {
    for (ClientListener al : adminListeners)
      al.edgeRemoved(edge);

    clientEdgeChanged(edge);
  }

  @Override
  public void vertexAdded(Vertex vertex) {
    for (ClientListener al : adminListeners)
      al.vertexAdded(vertex);

    clientVertexChanged(vertex, false);
  }

  @Override
  public void vertexPropertyChanged(Vertex vertex, String key, Object oldValue, Object setValue) {
    for (ClientListener al : adminListeners)
      al.vertexPropertyChanged(vertex, key, oldValue, setValue);

    clientVertexChanged(vertex, onlyPlayerSees(key));
  }

  @Override
  public void vertexPropertyRemoved(Vertex vertex, String key, Object removedValue) {
    for (ClientListener al : adminListeners)
      al.vertexPropertyRemoved(vertex, key);

    clientVertexChanged(vertex, onlyPlayerSees(key));
  }

  @Override
  public void vertexRemoved(Vertex vertex, Map<String, Object> props) {
    for (ClientListener al : adminListeners)
      al.vertexRemoved(vertex);

    clientVertexChanged(vertex, false);
  }

  // A property neighbors never receive (private, text, choices, and what ClientView hides, such as
  // _system) changes only the player's own page, so the neighbors aren't sent a new frame for it.
  // _system.connected changes on every connect and disconnect.
  static boolean onlyPlayerSees(String key) {
    return !ClientView.neighborMaySee(key);
  }

  private void clientEdgePropertyChanged(Edge edge, String key, Object value) {
    // inProps are only visible by the inVertex and outProps are only visible by the outVertex
    if (key.equals("inProps")) {
      Vertex inVertex = edge.getVertex(Direction.IN);
      String inId = (String) inVertex.getId();
      if (clientListeners.containsKey(inId)) {
        Client inClient = clientListeners.get(inId);
        inClient.updateGraph(inVertex);
      }
    } else if (key.equals("outProps")) {
      Vertex outVertex = edge.getVertex(Direction.OUT);
      String outId = (String) outVertex.getId();
      if (clientListeners.containsKey(outId)) {
        Client outClient = clientListeners.get(outId);
        outClient.updateGraph(outVertex);
      }
    } else {
      clientEdgeChanged(edge);
    }
  }

  private void clientEdgeChanged(Edge edge) {
    Vertex[] vertices = getVerticesByEdge(edge);

    String id1 = (String) vertices[0].getId();
    String id2 = (String) vertices[1].getId();

    if (clientListeners.containsKey(id1)) {
      Client c1 = clientListeners.get(id1);
      c1.updateGraph(vertices[0]);
    }

    if (clientListeners.containsKey(id2)) {
      Client c2 = clientListeners.get(id2);
      c2.updateGraph(vertices[1]);
    }

    // With ties among neighbors visible, everyone next to both endpoints sees this edge too.
    if (ClientView.getVisibility() == ClientView.Visibility.EGO_TIES) {
      java.util.Set<Object> updated = new java.util.HashSet<Object>(java.util.Arrays.asList(id1, id2));
      for (Vertex endpoint : vertices) {
        for (Vertex neighbor : endpoint.getVertices(com.tinkerpop.blueprints.Direction.BOTH, "connected")) {
          Object id = neighbor.getId();
          if (updated.add(id) && clientListeners.containsKey(id)) {
            clientListeners.get(id).updateGraph(neighbor);
          }
        }
      }
    }
  }

  private void clientVertexChanged(Vertex vertex, boolean pvt) {
    // The vertex itself
    String id = (String) vertex.getId();
    if (clientListeners.containsKey(id)) {
      clientListeners.get(id).updateGraph(vertex);
    }
    // And all neighbors, if it isn't a private property
    if (!pvt) {
      for (Vertex v : vertex.getVertices(Direction.BOTH)) {
        id = (String) v.getId();
        if (clientListeners.containsKey(id)) {
          clientListeners.get(id).updateGraph(v);
        }
      }
    }
  }

  private Vertex[] getVerticesByEdge(Edge edge) {
    Vertex[] returnArray = new Vertex[2];
    returnArray[0] = edge.getVertex(Direction.IN);
    returnArray[1] = edge.getVertex(Direction.OUT);
    return returnArray;
  }
}
