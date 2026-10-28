// The end: everyone sees their score, and the session finishes.
finishStep = stepFactory.createNoUserActionStep()

finishStep.run = {
  g.V.each { p ->
    if (isHuman(p)) {
      p.text = """<h2>Thank you!</h2>
<p>You scored <strong>${p.score}</strong> out of ${rounds}.</p>
<p>The study is over. You can close this page.</p>""".toString()
    }
  }
}

finishStep.done = {
  finishStep.finish()
}
