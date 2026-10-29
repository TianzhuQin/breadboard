// One round: pick a color (and, if you like, cut one of your ties). The round ends when everyone
// has picked or when time runs out.
//
// What participants read is on the Content page (c.get("RoundPrompt"), ...), where it can be reworded
// or translated without changing this code; {0}, {1} there are filled in from here.
roundStep = stepFactory.createStep("RoundStep")

roundStep.run = {
  round++
  roundStep.duration = roundSeconds
  roundTimer = g.addTimer([time: roundSeconds as Integer, timerText: c.get("RoundTimer")])
  g.V.each { p ->
    p.pick = ""
    if (isHuman(p)) {
      p.text = roundText(p, c.get(round > 1 ? "RoundPromptLater" : "RoundPrompt"))
      // Cutting is limited to your own ties, one per round, and nobody may be left alone: those
      // cuts are offered greyed out, with the reason.
      edgeEdits.allow(p, [add: false, remove: true, scope: "own", maxRemoves: 1,
        candidates: { me ->
          me.both.toList().collect { other ->
            def alone = [me, other].find { it.both.count() <= 1 }
            [op: "remove", a: me, b: other, available: alone == null,
             reason: alone == null ? null : c.get(alone == me ? "CutLeavesYouAlone" : "CutLeavesThemAlone")]
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
  [c.get("RoundHeading", round, rounds),
   p.private.lastResult ? "<p>${p.private.lastResult}</p>" : "",
   body,
   c.get("Score", p.score, round - 1)].findAll { it }.join("\n").toString()
}

pick = { p, colour ->
  p.pick = colour
  // Everything recorded with eventTracker is in the session's data download.
  eventTracker.track("ColourPick", [player: p.id, round: round, colour: colour])
  if (isHuman(p)) {
    // (the computer plays the seat of someone who left; they see it when they come back)
    p.text = roundText(p, c.get(p.ai == 1 ? "PickedForYou" : "Picked", colour))
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
    p.private.lastResult = !p.pick ? c.get("ResultNoPick")
        : (majority == null ? c.get("ResultTie", p.pick)
        : c.get(point ? "ResultPoint" : "ResultNoPoint", p.pick, majority))
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
