// Network game: an example that runs from start to finish by itself.
//
//  1. People sign in and wait until enough are here (the waiting room below).
//  2. They are placed on a network, topped up with computer players to NETWORK_SIZE.
//  3. Each round everyone picks a color, and scores a point when it matches the color most of
//     their neighbors picked. The network shows what each neighbor picked last round. During a
//     round, everyone may also cut one of their ties, right on the network.
//  4. Everyone sees their score. The session ends, and the autopilot (if it is on) opens the next.
//
// The number of rounds and their length are launch parameters (see Parameters). Change anything
// here, or use it as the starting point of your own design.

NETWORK_SIZE = 8   // people on the network, computer players included
NEAREST = 2        // everyone starts tied to the NEAREST people on each side of a ring
COLORS = [Blue: "#2563eb", Orange: "#ea580c"]   // the choices, and the people on the network

// Start without a researcher: as soon as 2 people are here, wait 20 seconds for more (or start
// right away with NETWORK_SIZE), then count down 5 seconds. Give up after 10 minutes.
autopilot.waitingRoom([minPlayers: 2, maxPlayers: NETWORK_SIZE, waitSeconds: 20,
                       maxWaitSeconds: 600, countdownSeconds: 5])
// Someone who drops out is played by the computer after 30 seconds, until they come back.
autopilot.replaceDisconnected([afterSeconds: 30, returnToHuman: true])

// Participants see the ties among their neighbors too, not only their own.
g.setVisibility("egoTies")

// Computer players' ids start with "_".
isHuman = { v -> !v.id.toString().startsWith("_") }

initStep = stepFactory.createStep("InitStep")

initStep.run = {
  def people = g.V.toList()
  people.addAll(g.addAI(a, Math.max(0, NETWORK_SIZE - people.size())))
  Collections.shuffle(people)
  int n = people.size()
  people.eachWithIndex { p, i ->
    p.score = 0
    (1..NEAREST).each { k ->
      def other = people[(i + k) % n]
      if (other != p && !g.hasEdge(p, other)) g.addEdge(p, other)
    }
  }
  round = 0
}

initStep.done = {
  roundStep.start()
}
