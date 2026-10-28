// Network game: an example that runs from start to finish by itself.
//
//  1. People sign in and wait until enough are here (the waiting room below).
//  2. They are placed on a network, topped up with computer players to NETWORK_SIZE.
//  3. Each round everyone picks a color, and scores a point when it matches the color most of
//     their neighbors picked. The network shows what each neighbor picked last round. During a
//     round, everyone may also cut one of their ties, right on the network.
//  4. Everyone sees their score. The session ends, and the autopilot (if it is on) opens the next.
//
// The size of the network, the waiting room, and the number and length of rounds are launch
// parameters: change them on the Parameters page. Change anything else here, or use it as the
// starting point of your own design.

// A launch parameter's value. They exist once a session opens; the steps also load without one
// (a reload of the engine), and then the default here is used.
paramOr = { String name, fallback ->
  def value = null
  try { value = binding.getVariable(name) } catch (MissingPropertyException noSessionOpen) { }
  value != null ? value : fallback
}

NETWORK_SIZE = paramOr("networkSize", 8) as Integer   // people on the network, computer players included
NEAREST = 2        // everyone starts tied to the NEAREST people on each side of a ring
COLORS = [Blue: "#2563eb", Orange: "#ea580c"]   // the choices, and the people on the network

// Start without a researcher: as soon as minPlayers people are here, wait waitSeconds for more (or
// start right away with NETWORK_SIZE), then count down 5 seconds. Give up after 10 minutes.
autopilot.waitingRoom([minPlayers: paramOr("minPlayers", 2) as Integer, maxPlayers: NETWORK_SIZE,
                       waitSeconds: paramOr("waitSeconds", 20) as Integer,
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
