// Tests for graph.groovy: players and ties changed from several threads at once, as participants
// join on their own socket threads. Run via:  bin/sbt8 "testOnly GroovyScriptTests"
//
// Groovy 1.8.9 target: no lambdas / method refs.

import com.tinkerpop.blueprints.impls.tg.TinkerGraph
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Runs body(t) on `threads` threads of the pool, t = 0..threads-1, all starting at the same moment,
// and waits for them; an exception in any of them fails the test.
def atOnce(ExecutorService pool, int threads, Closure body) {
  def start = new CyclicBarrier(threads)
  def running = (0..<threads).collect { t ->
    pool.submit({ start.await(); body(t); null } as Callable)
  }
  running.each { it.get(10, TimeUnit.SECONDS) }
}

test("players who join at the same moment are all in the network") {
  // Two participants joining in the same millisecond used to lose one of them for good, now and
  // then: TinkerGraph keeps the players in a plain HashMap.
  int threads = 8
  def pool = Executors.newFixedThreadPool(threads)
  try {
    300.times { round ->
      def graph = new BreadboardGraph(new TinkerGraph(), null)
      atOnce(pool, threads) { t ->
        4.times { k -> graph.addPlayer("p" + t + "-" + k) }
        // and every thread joins as the same few ids too, as the tabs of one participant would
        4.times { k -> graph.addPlayer("same-" + k) }
      }
      def ids = graph.getVertices().collect { it.id }
      assert ids.size() == threads * 4 + 4 : "round ${round}: ${ids.size()} players in the network"
      assert ids.toSet().size() == ids.size()
    }
  } finally {
    pool.shutdownNow()
  }
}

test("ties added at the same moment are all kept") {
  int threads = 8
  def pool = Executors.newFixedThreadPool(threads)
  try {
    200.times { round ->
      def graph = new BreadboardGraph(new TinkerGraph(), null)
      def hub = graph.addPlayer("hub")
      def players = (0..<threads).collect { t -> (0..<4).collect { k -> graph.addPlayer("p" + t + "-" + k) } }
      atOnce(pool, threads) { t ->
        players[t].each { p -> graph.addEdge(hub, p, "connected") }
      }
      assert graph.getEdges().collect { it }.size() == threads * 4 : "round ${round}"
      assert hub.getEdges(com.tinkerpop.blueprints.Direction.BOTH).collect { it }.size() == threads * 4 : "round ${round}"
    }
  } finally {
    pool.shutdownNow()
  }
}

test("players leaving and joining at the same moment leave the network as it should be") {
  int threads = 8
  def pool = Executors.newFixedThreadPool(threads)
  try {
    200.times { round ->
      def graph = new BreadboardGraph(new TinkerGraph(), null)
      def hub = graph.addPlayer("hub")
      (0..<threads).each { t -> graph.addEdge(hub, graph.addPlayer("leaving-" + t), "connected") }
      atOnce(pool, threads) { t ->
        graph.removePlayer("leaving-" + t)
        graph.addEdge(hub, graph.addPlayer("joining-" + t), "connected")
      }
      def ids = graph.getVertices().collect { it.id.toString() }.toSet()
      assert ids == (["hub"] + (0..<threads).collect { "joining-" + it }).toSet() : "round ${round}: ${ids}"
      assert hub.getEdges(com.tinkerpop.blueprints.Direction.BOTH).collect { it }.size() == threads : "round ${round}"
    }
  } finally {
    pool.shutdownNow()
  }
}
