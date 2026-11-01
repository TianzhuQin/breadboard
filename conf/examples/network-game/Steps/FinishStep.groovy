// The end: everyone sees their score, and the session finishes.
finishStep = stepFactory.createNoUserActionStep()

finishStep.run = {
  g.V.each { p ->
    if (isHuman(p)) {
      boolean prolific = p._system?.recruitment?.source == "prolific" && prolificCode
      // (texts on the Content page)
      p.text = c.get(prolific ? "FinishedProlific" : "Finished", p.private.lastResult, p.score, rounds)
      // The new participant page shows its finished screen (no network).
      p.private.finished = true
      // Someone who came from Prolific gets the study's completion code (the prolificCode launch
      // parameter), with a button back to Prolific, so they can submit and be paid.
      if (p._system?.recruitment?.source == "prolific") {
        if (prolificCode) {
          recruitment.completeProlific(p, [completionCode: prolificCode])
        } else {
          // (set prolificCode on the Parameters page: the autopilot's sessions use its default)
          eventTracker.track("noCompletionCode", [player: p.id])
        }
      }
    }
  }
}

finishStep.done = {
  finishStep.finish()
}
