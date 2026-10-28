// One round: pick a color (and, if you like, cut one of your ties). The round ends when everyone
// has picked or when time runs out.
roundStep = stepFactory.createStep("RoundStep")

roundStep.run = {
  round++
  roundStep.duration = roundSeconds
  roundTimer = g.addTimer([time: roundSeconds as Integer, timerText: "Time left in this round"])
  g.V.each { p ->
    p.pick = ""
    if (isHuman(p)) {
      p.text = roundText(p, """<p>Pick a color. You score a point if it is the color most of your neighbors pick
this round.${round > 1 ? " The network shows what each of them picked last round." : ""}</p>""")
      // Cutting is limited to your own ties, one per round, and nobody may be left alone: those
      // cuts are offered greyed out, with the reason.
      edgeEdits.allow(p, [add: false, remove: true, scope: "own", maxRemoves: 1,
        candidates: { me ->
          me.both.toList().collect { other ->
            def alone = [me, other].find { it.both.count() <= 1 }
            [op: "remove", a: me, b: other, available: alone == null,
             reason: alone == null ? null : (alone == me ? "You would be left without any ties." : "They would be left without any ties.")]
          }
        }])
    }
    a.add(p,
      [name: "Blue", color: COLORS.Blue, result: { pick(p, "Blue") }],
      [name: "Orange", color: COLORS.Orange, result: { pick(p, "Orange") }])
  }
}

// The round's heading, last round's result, the score, then `body`.
roundText = { p, String body ->
  """<h2>Round ${round} of ${rounds}</h2>
${p.private.lastResult ? "<p>${p.private.lastResult}</p>" : ""}
${body}
<p class="bb-muted">Your score: ${p.score} of ${round - 1}</p>""".toString()
}

pick = { p, colour ->
  p.pick = colour
  // Everything recorded with eventTracker is in the session's data download.
  eventTracker.track("ColourPick", [player: p.id, round: round, colour: colour])
  if (isHuman(p)) {
    // (the computer plays the seat of someone who left; they see it when they come back)
    p.text = roundText(p, p.ai == 1
        ? "<p>While you were away, the computer picked <strong>${colour}</strong> for you. Waiting for the others…</p>"
        : "<p>You picked <strong>${colour}</strong>. Waiting for the others…</p>")
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
    boolean point = p.pick && p.pick == majority
    if (point) p.score = p.score + 1
    // (private: for this player only)
    p.private.lastResult = !p.pick ? "Last round you didn't pick in time: no point."
        : (majority == null ? "Last round you picked ${p.pick}; your neighbors were split evenly: no point."
        : "Last round you picked ${p.pick}; most of your neighbors picked ${majority}: ${point ? "<strong>+1 point</strong>" : "no point"}.").toString()
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
