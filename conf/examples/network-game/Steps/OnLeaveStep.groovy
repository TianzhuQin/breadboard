// Runs when someone leaves. Here nothing needs doing: the autopilot gives a computer player their
// seat after a while (autopilot.replaceDisconnected in InitStep), and hands it back if they return.
onLeaveStep = stepFactory.createNoUserActionStep()

onLeaveStep.run = {
}
onLeaveStep.done = {
}
