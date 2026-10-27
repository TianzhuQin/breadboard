import com.tinkerpop.blueprints.Direction
import com.tinkerpop.blueprints.Vertex
import java.util.concurrent.ConcurrentHashMap

// Participants (and researchers) editing the network directly: cutting a tie by clicking it,
// or tying two people together by clicking them in turn.
//
//   edgeEdits.allow(player, [
//     add: true,               // may create ties
//     remove: true,            // may cut ties
//     scope: "amongNeighbors", // which pairs:
//                              //   "amongNeighbors" - two of the player's neighbors (never the player)
//                              //   "own"            - the player and one of their neighbors
//                              //   "visible"        - any two people the player can see
//     maxAdds: 1,              // per allow(); null = unlimited
//     maxRemoves: 1,
//     confirm: true,           // the client asks "are you sure?" before sending
//     candidates: { player -> [[op: "remove", a: v1, b: v2, available: true],
//                              [op: "add", a: v3, b: v4, available: false, reason: "..."]] },
//                              // optional: the exact moves on offer. Only these can be made, and
//                              // unavailable ones are shown greyed out with their reason.
//     validate: { player, op, v1, v2 -> true },  // optional: false or a reason string refuses
//     onEdit: { player, op, v1, v2 -> },         // optional: replaces applying the edit at once,
//                                                // e.g. to collect proposals and apply them later
//   ])
//   edgeEdits.disallow(player)
//   edgeEdits.adminEdit("add" | "remove", id1, id2)   // a researcher's edit from the admin graph
//
// Ties among neighbors are only visible to participants with g.setVisibility("egoTies").
//
// The client reads what it may do from player.edgeEdits (private, never sent to neighbors) and
// sends {op, a, b} using the ids it received (aliases are translated here). Every edit, accepted
// or refused, is logged as an "edgeEdit" event naming the acting player. The edge log used to have
// no actor at all.

final EDGE_EDIT_EVENT = "bb:edge-edit"
final EDGE_EDIT_RESULT = "bb:edge-edit-result"

class EdgeEdits {
  def g
  def eventTracker
  String editEvent
  String resultEvent
  Map<String, Map> rules = new ConcurrentHashMap<String, Map>()

  def allow(Vertex player, Map opts = [:]) {
    def rule = [
      add       : opts.add == null ? true : opts.add as boolean,
      remove    : opts.remove == null ? true : opts.remove as boolean,
      scope     : opts.scope ?: "amongNeighbors",
      remainingAdds   : opts.maxAdds,
      remainingRemoves: opts.maxRemoves,
      confirm   : opts.confirm == null ? true : opts.confirm as boolean,
      candidates: opts.candidates,
      validate  : opts.validate,
      onEdit    : opts.onEdit,
    ]
    if (!(rule.scope in ["amongNeighbors", "own", "visible"])) {
      throw new IllegalArgumentException("edgeEdits scope must be amongNeighbors, own or visible, not ${rule.scope}")
    }
    rules[player.id.toString()] = rule
    player.off(editEvent)
    player.on(editEvent, { Vertex p, data -> handle(p, data) })
    publish(player)
  }

  def disallow(Vertex player) {
    rules.remove(player.id.toString())
    player.off(editEvent)
    player.private.edgeEdits = [enabled: false]
  }

  def disallowAll() {
    rules.keySet().toList().each { id ->
      def v = g.getVertex(id)
      if (v != null) {
        disallow(v)
      } else {
        rules.remove(id)
      }
    }
  }

  // A researcher's edit from the admin graph view: no participant rules apply.
  def adminEdit(String op, String id1, String id2) {
    def v1 = g.getVertex(id1)
    def v2 = g.getVertex(id2)
    if (v1 == null || v2 == null || v1 == v2) {
      throw new IllegalArgumentException("adminEdit needs two different existing players")
    }
    def reason = applyDefault(op, v1, v2)
    log("admin", op, v1, v2, reason == null, reason)
    if (reason != null) {
      throw new IllegalStateException(reason)
    }
    refreshAll()
    return true
  }

  // Tell the player's client what it may do, including the current candidate moves.
  def publish(Vertex player) {
    def rule = rules[player.id.toString()]
    if (rule == null) {
      player.private.edgeEdits = [enabled: false]
      return
    }
    def state = [
      enabled         : true,
      add             : rule.add && (rule.remainingAdds == null || rule.remainingAdds > 0),
      remove          : rule.remove && (rule.remainingRemoves == null || rule.remainingRemoves > 0),
      scope           : rule.scope,
      remainingAdds   : rule.remainingAdds,
      remainingRemoves: rule.remainingRemoves,
      confirm         : rule.confirm,
    ]
    if (rule.candidates != null) {
      state.candidates = currentCandidates(player, rule).collect { c ->
        [op: c.op, a: g.clientIdOf(c.a), b: g.clientIdOf(c.b), available: c.available != false, reason: c.reason]
      }
    }
    player.private.edgeEdits = state
  }

  def refreshAll() {
    rules.keySet().each { id ->
      def v = g.getVertex(id)
      if (v != null) publish(v)
    }
  }

  def handle(Vertex player, data) {
    String op = data?.op
    def v1 = data?.a == null ? null : g.fromClientId(data.a.toString())
    def v2 = data?.b == null ? null : g.fromClientId(data.b.toString())
    def reason = check(player, op, v1, v2)
    if (reason == null) {
      def rule = rules[player.id.toString()]
      if (rule.onEdit != null) {
        rule.onEdit(player, op, v1, v2)
      } else {
        reason = applyDefault(op, v1, v2)
      }
      if (reason == null) {
        if (op == "add" && rule.remainingAdds != null) rule.remainingAdds -= 1
        if (op == "remove" && rule.remainingRemoves != null) rule.remainingRemoves -= 1
      }
    }
    log(player.id.toString(), op, v1, v2, reason == null, reason)
    player.send(resultEvent, [ok: reason == null, op: op, a: data?.a, b: data?.b, reason: reason])
    refreshAll()
  }

  // null if the edit is allowed, otherwise the reason it isn't.
  def check(Vertex player, String op, Vertex v1, Vertex v2) {
    def rule = rules[player.id.toString()]
    if (rule == null) return "Editing ties is not possible right now."
    if (!(op in ["add", "remove"])) return "Unknown edit."
    if (op == "add" && !rule.add) return "Adding ties is not possible."
    if (op == "remove" && !rule.remove) return "Cutting ties is not possible."
    if (op == "add" && rule.remainingAdds != null && rule.remainingAdds <= 0) return "No more ties can be added."
    if (op == "remove" && rule.remainingRemoves != null && rule.remainingRemoves <= 0) return "No more ties can be cut."
    if (v1 == null || v2 == null || v1 == v2) return "Choose two different people."

    if (rule.candidates != null) {
      def match = currentCandidates(player, rule).find { c ->
        c.op == op && ((c.a == v1 && c.b == v2) || (c.a == v2 && c.b == v1))
      }
      if (match == null) return "That change is not on offer."
      if (match.available == false) return match.reason ?: "That change is not available."
    } else {
      def neighbors = player.getVertices(Direction.BOTH, "connected").collect { it.id.toString() } as Set
      def visible = neighbors + [player.id.toString()]
      def ids = [v1.id.toString(), v2.id.toString()]
      switch (rule.scope) {
        case "amongNeighbors":
          if (!(neighbors.containsAll(ids))) return "Both people must be among your connections."
          break
        case "own":
          if (!ids.contains(player.id.toString())) return "You can only change your own ties."
          if (!visible.containsAll(ids)) return "You can only change ties with people you can see."
          break
        case "visible":
          if (!visible.containsAll(ids)) return "You can only change ties between people you can see."
          break
      }
    }

    boolean tied = g.hasEdge(v1, v2)
    if (op == "add" && tied) return "They are already connected."
    if (op == "remove" && !tied) return "They are not connected."

    if (rule.validate != null) {
      def verdict = rule.validate(player, op, v1, v2)
      if (verdict == false) return "That change is not allowed."
      if (verdict instanceof String) return verdict
    }
    return null
  }

  def currentCandidates(Vertex player, Map rule) {
    def list = rule.candidates(player)
    return list == null ? [] : list
  }

  // Apply an edit to the graph; null on success, otherwise why it couldn't be applied.
  def applyDefault(String op, Vertex v1, Vertex v2) {
    if (op == "add") {
      if (g.hasEdge(v1, v2)) return "They are already connected."
      g.addTrackedEdge(v1, v2, "connected")
      return null
    }
    if (op == "remove") {
      def e = g.getEdge(v1, v2)
      if (e == null) return "They are not connected."
      return g._removeEdge(e, true) ? null : "They are not connected."
    }
    return "Unknown edit."
  }

  def log(String actor, String op, Vertex v1, Vertex v2, boolean accepted, String reason) {
    if (eventTracker == null) return
    def data = [
      [name: "actor", value: actor],
      [name: "op", value: op],
      [name: "playerId1", value: v1?.id?.toString()],
      [name: "playerId2", value: v2?.id?.toString()],
      [name: "accepted", value: accepted.toString()],
    ]
    if (reason != null) data << [name: "reason", value: reason]
    eventTracker.track("edgeEdit", data)
  }
}

edgeEdits = new EdgeEdits(g: g, eventTracker: eventTracker, editEvent: EDGE_EDIT_EVENT, resultEvent: EDGE_EDIT_RESULT)
