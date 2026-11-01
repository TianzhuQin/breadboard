import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** New arrivals are turned away before they see a countdown for a game with no seat for them. */
public class AutopilotCapacityTest {
    private ScriptTestHarness harness;

    @Before public void setUp() throws Exception {
        harness = new ScriptTestHarness();
        harness.eval("full = []; starts = []; "
            + "ap = new Autopilot(g:g, a:a, eventTracker:null, gameListener:[experimentInstance:[hasStarted:false]], "
            + "startGame:{ starts << true }, sessionFull:{ id -> full << id }); "
            + "arrive = { id -> def v=g.getVertex(id) ?: g.addPlayer(id); v._system.connected=true; ap.onConnection(id,true); v }; null");
    }

    @After public void tearDown() { if (harness != null) harness.close(); }

    @Test public void aLateArrivalIsToldAtOnceWhileTheFullRoomCountsDown() {
        harness.runSync("{ -> ap.waitingRoom([minPlayers:2,maxPlayers:2,countdownSeconds:30]); "
            + "arrive('p1'); arrive('p2'); arrive('p3'); "
            + "assert full == ['p3']; assert g.getVertex('p3') == null; "
            + "assert starts.isEmpty(); assert ap.roomFull(); "
            + "assert g.getVertex('p1')._system.autopilot.state == 'starting'; ap.stop() }");
    }

    @Test public void participantsAlreadyThereWhenTheMaximumChangesAreToldAtOnce() {
        harness.runSync("{ -> ap.waitingRoom([minPlayers:4,waitSeconds:60]); "
            + "arrive('p1'); arrive('p2'); arrive('p3'); "
            + "ap.replaceWaitingRoom([minPlayers:2,maxPlayers:2,countdownSeconds:30]); "
            + "assert full == ['p3']; assert g.getVertex('p3') == null; assert starts.isEmpty(); ap.stop() }");
    }

    @Test public void aReconnectedParticipantKeepsTheirSeatWhileANewArrivalIsRefused() {
        harness.runSync("{ -> ap.waitingRoom([minPlayers:2,maxPlayers:2,countdownSeconds:30]); "
            + "ap.replaceDisconnected([afterSeconds:30]); arrive('p1'); arrive('p2'); "
            + "def p=g.getVertex('p1'); p._system.connected=false; p._system.disconnectedAt=System.currentTimeMillis(); "
            + "ap.onConnection('p1',false); arrive('p3'); arrive('p1'); "
            + "assert full == ['p3']; assert g.getVertex('p1') != null; assert starts.isEmpty(); ap.stop() }");
    }
}
