// One round: pick a colour (and, if you like, cut one of your ties). The round ends when everyone
// has picked or when time runs out.
roundStep = stepFactory.createStep("RoundStep")

roundStep.run = {
  round++
  roundStep.duration = roundSeconds
  roundTimer = g.addTimer([time: roundSeconds as Integer, timerText: "Time left in this round"])
  g.V.each { p ->
    p.pick = ""
    if (isHuman(p)) {
      p.text = """<h2>Round ${round} of ${rounds}</h2>
<p>Pick a colour. You score a point if it is the colour most of your neighbors pick this round.</p>
${round > 1 ? "<p>The network shows what each of your neighbors picked last round.</p>" : ""}
<p>You may also cut one of your ties: click a line between you and someone.</p>
<p class="bb-muted">Your score so far: ${p.score}</p>""".toString()
      // Cutting is limited to your own ties, one per round, and nobody may be left alone.
      edgeEdits.allow(p, [add: false, remove: true, scope: "own", maxRemoves: 1,
        validate: { me, op, v1, v2 ->
          [v1, v2].every { it.both.count() > 1 } ?: "Nobody may be left without any ties."
        }])
    }
    a.add(p,
      [name: "Blue", result: { pick(p, "Blue") }],
      [name: "Orange", result: { pick(p, "Orange") }])
  }
}

pick = { p, colour ->
  p.pick = colour
  // Everything recorded with eventTracker is in the session's data download.
  eventTracker.track("ColourPick", [player: p.id, round: round, colour: colour])
  if (isHuman(p)) {
    p.text = """<h2>Round ${round} of ${rounds}</h2>
<p>You picked <strong>${colour}</strong>. Waiting for the others…</p>
<p class="bb-muted">Your score so far: ${p.score}</p>""".toString()
  }
}

roundStep.done = {
  roundTimer.cancel()
  g.V.each { p -> if (isHuman(p)) edgeEdits.disallow(p) }
  // Score against this round's picks, then show them on the network.
  g.V.each { p ->
    def picks = p.both.toList().collect { it.pick }.findAll { it }
    def blue = picks.count { it == "Blue" }
    def majority = blue * 2 > picks.size() ? "Blue" : (blue * 2 < picks.size() ? "Orange" : null)
    if (p.pick && p.pick == majority) p.score = p.score + 1
    eventTracker.track("RoundResult", [player: p.id, round: round, pick: p.pick ?: "none",
                                       neighborsMajority: majority ?: "tie", score: p.score])
  }
  // (grey: picked nothing in time)
  g.V.each { p -> p.color = COLORS[p.pick] ?: "#94a3b8" }
  if (round < rounds) {
    roundStep.start()
  } else {
    finishStep.start()
  }
}
