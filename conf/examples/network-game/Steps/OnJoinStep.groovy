// Runs for everyone who signs in. Until the game starts they see the waiting room (see InitStep).
onJoinStep = stepFactory.createNoUserActionStep()

onJoinStep.run = { playerId ->
}
onJoinStep.done = {
}
