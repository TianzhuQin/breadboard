import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.impls.tg.TinkerGraph;
import com.tinkerpop.blueprints.util.wrappers.event.EventGraph;
import groovy.lang.Closure;
import models.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.libs.F;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.WebSocket;
import security.ParticipantAuth;
import security.PreviewTokens;

import javax.script.ScriptEngine;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * The server side of participants' connections, driven as pages would (ScriptBoard.addClient with
 * fake sockets, in a session launched from a console): two tabs of one participant don't fight over
 * the seat, connections are recorded once per real change, pings are answered and a page that falls
 * silent counts as gone, someone the game started without is told so, the same view isn't sent
 * twice, a researcher's preview of a connected player or of a computer player only watches, any
 * preview of someone whose own page has held the seat (however often the console opens a new one)
 * only stands in for them and never counts as them, test players are played through previews, pages
 * at their final screen are answered and let go, and the seat cookie lasts a day.
 */
public class ParticipantConnectionTest extends BaseTest {

    private final List<AdminSocketsTest.Tab> tabs = new ArrayList<>();
    private AdminSocketsTest.Tab console;
    private User researcher;
    private Experiment experiment;
    private ExperimentInstance session;
    private long silenceLimit;

    @Before
    public void launch() throws Exception {
        silenceLimit = (Long) field("silenceLimitMs").get(null);
        Language lang = Language.find.where().eq("code", "eng").findUnique();
        if (lang == null) lang = createLanguage("eng", "English");
        User user = createUser("conn-" + UUID.randomUUID() + "@test.org", "conn", "secret-1", lang);
        researcher = user;
        experiment = createExperiment("Connections");
        user.ownedExperiments.add(experiment);
        user.saveManyToManyAssociations("ownedExperiments");
        console = new AdminSocketsTest.Tab(user.uid);
        tabs.add(console);
        console.send("LogIn");
        await("LogIn", () -> console.count(AdminSocketsTest.userJson()) >= 1);
        console.send("SelectExperiment", "experimentId", experiment.id);
        await("selected", () -> experiment.id.equals(field("engineExperimentId").get(null)));
        console.send("LaunchGame", "name", "C", "parameters", Json.newObject());
        await("running", () -> {
            List<ExperimentInstance> found = ExperimentInstance.find.where().eq("experiment.id", experiment.id).findList();
            return found.size() == 1 && found.get(0).status == ExperimentInstance.Status.RUNNING;
        });
        session = ExperimentInstance.find.where().eq("experiment.id", experiment.id).findUnique();
    }

    @After
    @SuppressWarnings("unchecked")
    public void closeTabs() throws Throwable {
        field("silenceLimitMs").set(null, silenceLimit);
        field("clock").set(null, (LongSupplier) System::currentTimeMillis);
        for (AdminSocketsTest.Tab t : tabs) t.close();
        // (later tests' participants may have the same ids)
        Map<String, Client> clients = (Map<String, Client>) field("clients").get(null);
        for (Client c : clients.values()) c.disconnect();
        clients.clear();
        ((Set<?>) field("endedPages").get(null)).clear();
        ParticipantAuth.reset();
        PreviewTokens.reset();
        Recruitment.reset();
        ((EventTracker) field("eventTracker").get(null)).setExperimentInstance(null);
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** A participant's page, or a researcher's preview of one: its socket, as the server sees it. */
    private static final class Page {
        final WebSocket.In<JsonNode> in = new WebSocket.In<JsonNode>();
        final List<JsonNode> frames = new CopyOnWriteArrayList<>();
        volatile boolean closed;
        final WebSocket.Out<JsonNode> socketOut = new WebSocket.Out<JsonNode>() {
            public void write(JsonNode frame) { frames.add(frame); }
            public void close() { closed = true; }
        };
        final ThrottledWebSocketOut out = new ThrottledWebSocketOut(socketOut, 0L);

        void send(String action, Object... pairs) {
            ObjectNode m = Json.newObject();
            m.put("action", action);
            for (int i = 0; i < pairs.length; i += 2) {
                m.put((String) pairs[i], Json.toJson(pairs[i + 1]));
            }
            for (F.Callback<JsonNode> cb : in.callbacks) {
                try { cb.invoke(m); } catch (Throwable t) { throw new RuntimeException(t); }
            }
        }

        /** The browser closes the socket (or the connection drops and the server notices). */
        void hangUp() throws Throwable {
            for (F.Callback0 cb : in.closeCallbacks) cb.invoke();
        }

        int count(AdminSocketsTest.Matcher m) {
            int n = 0;
            for (JsonNode f : frames) if (m.matches(f)) n++;
            return n;
        }

        JsonNode last() {
            return frames.isEmpty() ? null : frames.get(frames.size() - 1);
        }
    }

    private Page connect(String clientId) throws Exception {
        return connect(clientId, false);
    }

    private Page connect(String clientId, boolean preview) throws Exception {
        Page page = new Page();
        ScriptBoard.addClient(experiment.id.toString(), session.id.toString(), clientId, page.in, page.out, preview);
        return page;
    }

    /**
     * A participant's page opening its socket with its seat token, as a page does: through the
     * handshake (ClientController.connectClient), with what else the page adds to the address
     * ({@code query}, e.g. "bbPage=1000").
     */
    private Page handshake(String clientId, String token, String query) throws Exception {
        String e = experiment.id.toString();
        String i = session.id.toString();
        play.test.FakeRequest request = fakeRequest(GET, "/game/" + e + "/" + i + "/" + clientId + "/connect?"
            + ParticipantAuth.QUERY_PARAM + "=" + token + (query.isEmpty() ? "" : "&" + query));
        Http.Context.current.set(play.core.j.JavaHelpers$.MODULE$.createJavaContext(request.getWrappedRequest()));
        Page page = new Page();
        try {
            controllers.ClientController.connectClient(e, i, clientId).onReady(page.in, page.socketOut);
        } finally {
            Http.Context.current.remove();
        }
        return page;
    }

    /** A test player, as an experiment's steps make one: in the network, with no page of theirs yet. */
    private static void testPlayer(String clientId) throws Exception {
        engine().eval("def v = g.addPlayer('" + clientId + "'); v.testmode = true; null");
    }

    private static final AdminSocketsTest.Matcher REPLACED = f -> "replaced".equals(f.path("bbClosed").asText());
    private static final AdminSocketsTest.Matcher WATCHING = f -> f.path("bbWatching").asBoolean(false);
    private static final AdminSocketsTest.Matcher NOT_WATCHING = f -> f.has("bbWatching") && !f.get("bbWatching").asBoolean();
    private static final AdminSocketsTest.Matcher PONG = f -> f.has("pong");
    private static final AdminSocketsTest.Matcher VIEW = f -> f.has("graph") && f.has("player");
    private static final AdminSocketsTest.Matcher ANSWER = f -> f.isObject() && f.size() == 0;

    private static Field field(String name) throws Exception {
        Field f = ScriptBoard.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static ScriptEngine engine() throws Exception {
        return (ScriptEngine) field("engine").get(null);
    }

    @SuppressWarnings("unchecked")
    private static Client client(String id) throws Exception {
        return ((Map<String, Client>) field("clients").get(null)).get(id);
    }

    private static void closeSilentPages(long now) throws Exception {
        Method m = ScriptBoard.class.getDeclaredMethod("closeSilentPages", long.class);
        m.setAccessible(true);
        m.invoke(null, now);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> system(String clientId) throws Exception {
        return (Map<String, Object>) engine().eval("g.getVertex('" + clientId + "')._system");
    }

    /** How many events of this name the session recorded for this participant. */
    private int events(String name, String clientId) {
        return eventsWith(name, "clientId", clientId);
    }

    /** How many events of this name the session recorded with this data (e.g. playerId = p1). */
    private int eventsWith(String name, String dataName, String value) {
        int n = 0;
        for (Event e : Event.find.where().eq("experimentInstance.id", session.id).eq("name", name).findList()) {
            for (EventData d : e.eventData) {
                if (dataName.equals(d.name) && value.equals(d.value)) n++;
            }
        }
        return n;
    }

    /** As events, counting only those a researcher's preview caused (preview = true), or only the others. */
    private int events(String name, String clientId, boolean byPreview) {
        int n = 0;
        for (Event e : Event.find.where().eq("experimentInstance.id", session.id).eq("name", name).findList()) {
            boolean theirs = false;
            boolean preview = false;
            for (EventData d : e.eventData) {
                if ("clientId".equals(d.name) && clientId.equals(d.value)) theirs = true;
                if ("preview".equals(d.name) && "true".equals(d.value)) preview = true;
            }
            if (theirs && preview == byPreview) n++;
        }
        return n;
    }

    /** What bb:connection announced (connected or not), while it listens. */
    private static final class Announcements extends Closure {
        final List<Object> heard = new CopyOnWriteArrayList<>();
        Announcements() { super(null); }
        public Object doCall(Object info) { heard.add(((Map<?, ?>) info).get("connected")); return null; }
    }

    private static void await(String what, Callable<Boolean> condition) throws Exception {
        long until = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < until) {
            if (Boolean.TRUE.equals(condition.call())) return;
            Thread.sleep(25);
        }
        fail("Timed out waiting for " + what);
    }

    /** Counts CustomEvents that reach the scripts, while it is on. */
    private static final class CustomEvents extends Closure {
        final List<Object> heard = new CopyOnWriteArrayList<>();
        CustomEvents() { super(null); }
        public Object doCall(Object input, Object from) { heard.add(from); return null; }
    }

    private static EventBus bus() throws Exception {
        return (EventBus) field("eventBus").get(null);
    }

    // ---- two tabs, connections recorded once per change --------------------------------------------

    @Test
    public void anotherTabTakesTheSeatAndTheFirstIsToldWhy() throws Throwable {
        Page first = connect("p1");
        assertTrue(first.count(VIEW) >= 1);
        Page second = connect("p1");
        assertEquals("told why, so it doesn't take the seat back", 1, first.count(REPLACED));
        assertTrue("{\"bbClosed\": \"replaced\"} is its last frame", REPLACED.matches(first.last()));
        assertTrue(first.closed);
        assertFalse(second.closed);
        assertSame(second.out, client("p1").out);
        assertEquals(1, second.count(VIEW));
        assertEquals(0, second.count(REPLACED));

        // What the replaced tab still sends is ignored; its socket closing is no disconnect.
        first.send("MakeChoice", "choiceUID", "no-such-choice");
        assertEquals(0, first.count(ANSWER));
        first.hangUp();
        assertEquals(Boolean.TRUE, system("p1").get("connected"));

        // Taking over from an open tab, three times: one connection, as the participant never left.
        connect("p1");
        Page last = connect("p1");
        assertEquals("connected once", 1, events("clientConnected", "p1"));
        assertEquals(0, events("clientDisconnected", "p1"));

        // Their last page closes: gone, and since when. A page that comes back is a new connection,
        // with nothing to tell the socket that died.
        last.hangUp();
        assertEquals(1, events("clientDisconnected", "p1"));
        assertEquals(Boolean.FALSE, system("p1").get("connected"));
        Object since = system("p1").get("disconnectedAt");
        assertTrue(String.valueOf(since), since instanceof Long && Math.abs(System.currentTimeMillis() - (Long) since) < 10000);
        Page back = connect("p1");
        assertEquals(0, last.count(REPLACED));
        assertEquals(2, events("clientConnected", "p1"));
        assertEquals(Boolean.TRUE, system("p1").get("connected"));
        assertNull("only while they are away", system("p1").get("disconnectedAt"));
        assertSame(back.out, client("p1").out);
    }

    // ---- the page opened last keeps the seat ------------------------------------------------------

    @Test
    public void anOlderPageReconnectingByItselfLeavesTheSeatToThePageOpenedSince() throws Throwable {
        String token = ParticipantAuth.claim(session.id.toString(), "p1");
        Page older = handshake("p1", token, "bbPage=1000");
        assertTrue(older.count(VIEW) >= 1);
        // They open the study again in a new tab, while the older one's connection is down.
        Page newer = handshake("p1", token, "bbPage=2000");
        assertEquals(1, older.count(REPLACED));

        // The older tab's network comes back, and it reconnects by itself: the newer page keeps the
        // seat, and the older one is told it has been taken.
        Page again = handshake("p1", token, "bbPage=1000");
        assertTrue(again.closed);
        assertEquals(again.frames.toString(), 1, again.frames.size());
        assertTrue(REPLACED.matches(again.frames.get(0)));
        assertFalse(newer.closed);
        assertEquals(0, newer.count(REPLACED));
        client("p1").send("still-here");
        assertTrue(newer.last().has("eventName"));
        assertEquals(1, again.frames.size());
        // What the refused socket sends, and its going, change nothing; nothing is recorded.
        again.send("MakeChoice", "choiceUID", "no-such-choice");
        again.hangUp();
        assertEquals(0, again.count(ANSWER));
        assertEquals(Boolean.TRUE, system("p1").get("connected"));
        assertEquals(1, events("clientConnected", "p1"));
        assertEquals(0, events("clientDisconnected", "p1"));

        // "Use it here" on the older page takes the seat, from the newer one.
        Page here = handshake("p1", token, "bbPage=1000&bbTake=1");
        assertFalse(here.closed);
        assertEquals(1, newer.count(REPLACED));
        assertTrue(newer.closed);
        assertTrue(here.count(VIEW) >= 1);
        // A page that doesn't say when it was loaded (from an older server) takes it as before.
        Page unknown = handshake("p1", token, "");
        assertEquals(1, here.count(REPLACED));
        assertFalse(unknown.closed);
        assertEquals(1, events("clientConnected", "p1"));
        assertEquals(0, events("clientDisconnected", "p1"));
    }

    @Test
    public void anOlderPageIsSeatedOnceThePageOpenedSinceHasGone() throws Throwable {
        String token = ParticipantAuth.claim(session.id.toString(), "p1");
        handshake("p1", token, "bbPage=1000");
        Page newer = handshake("p1", token, "bbPage=2000");
        newer.hangUp();
        Page older = handshake("p1", token, "bbPage=1000");
        assertFalse(older.closed);
        assertEquals(0, older.count(REPLACED));
        assertTrue(older.count(VIEW) >= 1);
        assertEquals(Boolean.TRUE, system("p1").get("connected"));
        assertEquals(2, events("clientConnected", "p1"));
        assertEquals(1, events("clientDisconnected", "p1"));
    }

    @Test
    public void ofTwoPagesOfTheParticipantsTheOneLoadedLastHoldsTheSeat() {
        Client client = new Client("p1", session);
        assertEquals(Client.Arrival.SEATED, client.connect(socket(1000L, false, false)));
        Client.Connection newer = socket(2000L, false, false);
        assertEquals(Client.Arrival.TOOK_OVER, client.connect(newer));
        Client.Connection older = socket(1000L, false, false);
        assertEquals(Client.Arrival.REFUSED, client.connect(older));
        assertFalse(older.isOpen());
        assertTrue(client.holdsSeat(newer));
        // the same page connecting again, or one loaded at the same moment, takes it
        Client.Connection samePage = socket(2000L, false, false);
        assertEquals(Client.Arrival.TOOK_OVER, client.connect(samePage));
        // a researcher's preview is none of this: it watches their page, whenever it was loaded
        assertEquals(Client.Arrival.WATCHING, client.connect(socket(9000L, true, true)));
        assertEquals(Client.Arrival.WATCHING, client.connect(socket(1L, true, false)));
        assertTrue(client.holdsSeat(samePage));
        // the participant asks for it on the older page
        Client.Connection asked = socket(1000L, false, true);
        assertEquals(Client.Arrival.TOOK_OVER, client.connect(asked));
        assertTrue(client.holdsSeat(asked));
    }

    @Test
    public void aPageOfTheirsThatLostTheSeatCanAskWhetherAnotherOneStillHoldsIt() throws Throwable {
        String token = ParticipantAuth.claim(session.id.toString(), "p1");
        assertFalse("nobody's page yet", seatHeld("p1", token));
        Page older = handshake("p1", token, "bbPage=1000");
        Page newer = handshake("p1", token, "bbPage=2000");
        assertTrue(older.closed);
        assertTrue("the newer page holds it", seatHeld("p1", token));
        // A researcher's preview watching changes nothing; standing in for them, it is nobody's page.
        Page preview = connect("p1", true);
        assertTrue(seatHeld("p1", token));
        newer.hangUp();
        assertEquals(1, preview.count(NOT_WATCHING));
        assertFalse("the newer page has gone", seatHeld("p1", token));
        // Only the participant's own token may ask.
        Result stranger = route(fakeRequest(GET, "/game/" + experiment.id + "/" + session.id + "/p1/state?seat=1"));
        assertEquals(UNAUTHORIZED, status(stranger));
    }

    @Test
    public void aResearchersPageThatLostTheSeatCountsAnyPageThatHoldsIt() throws Throwable {
        // The previous console's Testing panel opens a participant's page without ?preview=1, signed in
        // by the researcher's cookie, and the console's Preview page opens one with a preview token.
        // Such a page that another one took the seat from takes it back by itself only once no page
        // holds it. It used to be told the seat was free whenever a preview held it, so it took the seat
        // from the console's preview within 20 s, and two of them took it from each other every 20 s.
        String token = ParticipantAuth.claim(session.id.toString(), "p1");
        String previewToken = PreviewTokens.issue(researcher.email, experiment.id.toString(), session.id.toString(), "p1").token;
        handshake("p1", token, "bbPage=1000").hangUp();
        // They have left. A page of the Testing panel stands in, and the console's preview takes the
        // seat from it.
        Page testingPanel = connect("p1", true);
        Page preview = connect("p1", true);
        assertEquals(1, testingPanel.count(REPLACED));
        assertTrue("the preview holds it", seatHeld("p1", null, researcher.uid));
        assertTrue(seatHeld("p1", previewToken, null));
        // The participant's own page counts only theirs, also when it is signed in as the researcher
        // too: it takes the seat, and the preview watches.
        assertFalse(seatHeld("p1", token, null));
        assertFalse(seatHeld("p1", token, researcher.uid));
        // Another page of the Testing panel takes the seat from the preview: held all the same.
        Page another = connect("p1", true);
        assertEquals(1, preview.count(REPLACED));
        assertTrue(seatHeld("p1", null, researcher.uid));
        // Their own page comes back and holds it, and goes again: the page that watched stands in.
        Page own = handshake("p1", token, "bbPage=2000");
        assertEquals(1, another.count(WATCHING));
        assertTrue(seatHeld("p1", null, researcher.uid));
        assertTrue(seatHeld("p1", token, null));
        own.hangUp();
        assertEquals(1, another.count(NOT_WATCHING));
        assertTrue(seatHeld("p1", null, researcher.uid));
        assertFalse(seatHeld("p1", token, null));
        // Nobody holds it.
        another.hangUp();
        assertFalse(seatHeld("p1", null, researcher.uid));
        assertFalse(seatHeld("p1", previewToken, null));
    }

    /** What /state?seat=1 answers the participant's page: whether a page of theirs holds the seat. */
    private boolean seatHeld(String clientId, String token) {
        return seatHeld(clientId, token, null);
    }

    /**
     * What /state?seat=1 answers a page that presents this token, signed in as the researcher with this
     * uid (either null: none).
     */
    private boolean seatHeld(String clientId, String token, String researcherUid) {
        play.test.FakeRequest request = fakeRequest(GET, "/game/" + experiment.id + "/" + session.id + "/" + clientId + "/state?seat=1");
        if (token != null) request = request.withHeader(ParticipantAuth.HEADER, token);
        if (researcherUid != null) request = request.withSession("uid", researcherUid);
        Result result = route(request);
        assertEquals(OK, status(result));
        JsonNode answer = Json.parse(contentAsString(result));
        assertEquals("that, and nothing else", 1, answer.size());
        return answer.get("seatHeld").asBoolean();
    }

    private static Client.Connection socket(long pageLoadedAt, boolean preview, boolean takesSeat) {
        return new Client.Connection(null, new ThrottledWebSocketOut(new WebSocketTest.TestWebSocketOut(), 0L), preview,
            pageLoadedAt, takesSeat);
    }

    // ---- the heartbeat -----------------------------------------------------------------------------

    @Test
    public void pingsAreAnsweredOnTheSocketThatSentThem() throws Throwable {
        Page page = connect("p1");
        Page watcher = connect("p1", true);
        int seen = watcher.frames.size();
        long before = System.currentTimeMillis();
        page.send("Ping");
        assertEquals(1, page.count(PONG));
        long pong = page.last().get("pong").asLong();
        assertTrue(pong >= before && pong <= System.currentTimeMillis());
        assertEquals("not passed on to the preview watching", seen, watcher.frames.size());
        watcher.send("Ping");
        assertEquals(1, watcher.count(PONG));
        assertEquals(1, page.count(PONG));
    }

    @Test
    public void aPageThatPingedAndFellSilentCountsAsGone() throws Throwable {
        Page quiet = connect("p1");
        quiet.send("Ping");
        Page old = connect("p2");      // a page from before 3.1: it never pings
        Page replacedTab = connect("p3");
        replacedTab.send("Ping");
        Page newer = connect("p3");    // took the seat: the older tab's silence is not theirs

        long now = System.currentTimeMillis();
        closeSilentPages(now + 29000);
        assertFalse(quiet.closed);

        closeSilentPages(now + 31000);
        assertTrue("closed after 30 s without a word", quiet.closed);
        assertEquals(Boolean.FALSE, system("p1").get("connected"));
        assertNotNull(system("p1").get("disconnectedAt"));
        assertEquals(1, events("clientDisconnected", "p1"));
        assertFalse("never pinged: left alone", old.closed);
        assertEquals(Boolean.TRUE, system("p2").get("connected"));
        assertFalse(newer.closed);
        assertEquals(Boolean.TRUE, system("p3").get("connected"));
        assertEquals(0, events("clientDisconnected", "p3"));

        // The socket's own close, when it comes, changes nothing more; their page coming back does.
        quiet.hangUp();
        closeSilentPages(now + 60000);
        assertEquals(1, events("clientDisconnected", "p1"));
        Page back = connect("p1");
        assertEquals(Boolean.TRUE, system("p1").get("connected"));
        assertEquals(2, events("clientConnected", "p1"));
        assertFalse(back.closed);
    }

    @Test
    public void everyMessageCountsAsHearingFromThePage() throws Throwable {
        field("silenceLimitMs").set(null, 300L);
        final long[] now = {System.currentTimeMillis()};
        field("clock").set(null, (LongSupplier) () -> now[0]);
        Page page = connect("p1");
        page.send("Ping");
        now[0] += 200;
        page.send("CustomEvent", "eventName", "still-here");
        now[0] += 200;
        closeSilentPages(now[0]);
        assertFalse("heard from 200 ms ago", page.closed);
        now[0] += 400;
        closeSilentPages(now[0]);
        assertTrue(page.closed);
    }

    // ---- gaps in a connection ----------------------------------------------------------------------

    /** How many connectionGap events this participant has, and the awayMs of the last one ("" if none). */
    private String gaps(String clientId) {
        int n = 0;
        String last = "";
        for (Event e : Event.find.where().eq("experimentInstance.id", session.id).eq("name", "connectionGap").order("id").findList()) {
            String player = null;
            String away = null;
            for (EventData d : e.eventData) {
                if ("player".equals(d.name)) player = d.value;
                if ("awayMs".equals(d.name)) away = d.value;
            }
            if (clientId.equals(player)) {
                n++;
                last = away;
            }
        }
        return n + (n > 0 ? ":" + last : "");
    }

    @Test
    public void aPageThatReconnectsAfterADropSaysHowLongItWasOutOfTouch() throws Throwable {
        Page page = connect("p1");
        page.send("LogIn", "clientId", "p1");
        assertEquals(1, events("clientLogIn", "p1"));
        // Its connection drops; its page connects again and signs in with how long it was away.
        Page back = connect("p1");
        back.send("LogIn", "clientId", "p1", "awayMs", 24900);
        assertEquals("1:24900", gaps("p1"));
        assertEquals("no new sign-in", 1, events("clientLogIn", "p1"));
        // Not for a socket that no longer holds the seat, nor for a researcher's preview.
        page.send("LogIn", "clientId", "p1", "awayMs", 5000);
        Page preview = connect("p1", true);
        preview.send("LogIn", "clientId", "p1", "awayMs", 7000);
        assertEquals("1:24900", gaps("p1"));
        assertEquals(1, events("clientLogIn", "p1"));
    }

    @Test
    public void aShortSilentOutageIsRecordedTheSameWayWhicheverSocketComesFirst() throws Throwable {
        final long[] now = {System.currentTimeMillis()};
        field("clock").set(null, (LongSupplier) () -> now[0]);
        Page page = connect("p1");
        page.send("Ping");
        Announcements announced = new Announcements();
        bus().on("bb:connection", announced);
        try {
            // Away 25 s, without a word: the page noticed after 15 s and gave its socket up. As the
            // network comes back, what it sent meanwhile (its pings) comes through, then that socket's
            // close, and its new socket 2 ms later. That used to be recorded as a disconnection and a
            // connection 2 ms apart.
            now[0] += 25000;
            page.send("Ping");
            page.send("Ping");
            now[0] += 1;
            page.hangUp();
            assertEquals(Boolean.TRUE, system("p1").get("connected"));
            assertEquals(0, events("clientDisconnected", "p1"));
            now[0] += 2;
            Page back = connect("p1");
            back.send("LogIn", "clientId", "p1", "awayMs", 25003);
            back.send("Ping");
            closeSilentPages(now[0] + 10000);
            assertEquals(0, events("clientDisconnected", "p1"));
            assertEquals(1, events("clientConnected", "p1"));
            assertEquals("1:25003", gaps("p1"));
            assertFalse("never announced as gone", announced.heard.contains(false));

            // The same again, with the new socket first (the old one's pings and close come after):
            // the same record. It used to be no record at all.
            now[0] += 25000;
            Page again = connect("p1");
            again.send("LogIn", "clientId", "p1", "awayMs", 25000);
            back.send("Ping");
            back.hangUp();
            closeSilentPages(now[0] + 10000);
            assertEquals(0, events("clientDisconnected", "p1"));
            assertEquals(1, events("clientConnected", "p1"));
            assertEquals("2:25000", gaps("p1"));

            // A close after a silence, with nothing before it: the same.
            again.send("Ping");
            now[0] += 12000;
            again.hangUp();
            Page third = connect("p1");
            third.send("LogIn", "clientId", "p1", "awayMs", 12000);
            assertEquals(0, events("clientDisconnected", "p1"));
            assertEquals("3:12000", gaps("p1"));

            // Out of touch and not back: gone once the silence would have closed the page.
            third.send("Ping");
            final long heard = now[0];
            now[0] += 20000;
            third.send("Ping");
            third.hangUp();
            assertEquals(Boolean.TRUE, system("p1").get("connected"));
            closeSilentPages(heard + 29000);
            assertEquals(0, events("clientDisconnected", "p1"));
            closeSilentPages(heard + 31000);
            assertEquals(1, events("clientDisconnected", "p1"));
            assertEquals(Boolean.FALSE, system("p1").get("connected"));
            assertEquals(Arrays.<Object>asList(false), announced.heard.subList(announced.heard.size() - 1, announced.heard.size()));
            closeSilentPages(heard + 60000);
            assertEquals("once", 1, events("clientDisconnected", "p1"));
        } finally {
            bus().off("bb:connection", announced);
        }

        // A page in touch whose socket closes (a tab closed) is gone at once, also some time after
        // it was out of touch for a while.
        Page tab = connect("p2");
        tab.send("Ping");
        now[0] += 3000;
        tab.hangUp();
        assertEquals(1, events("clientDisconnected", "p2"));
        assertEquals(Boolean.FALSE, system("p2").get("connected"));
        Page laptop = connect("p3");
        laptop.send("Ping");
        now[0] += 12000;
        laptop.send("Ping");
        for (int k = 0; k < 4; k++) {
            now[0] += 5000;
            laptop.send("Ping");
        }
        now[0] += 4000;
        laptop.hangUp();
        assertEquals(1, events("clientDisconnected", "p3"));
        // and a page from before 3.1, which never pings, whatever its silence
        Page old = connect("p4");
        now[0] += 20000;
        old.hangUp();
        assertEquals(1, events("clientDisconnected", "p4"));
    }

    @Test
    public void silencesOfAPageAreTimedFromWhatItSent() {
        Client.Connection c = socket(0L, false, false);
        assertEquals("never pinged: its silences mean nothing", 0L, c.outOfTouchSince(System.currentTimeMillis() + 60000));
        c.heard(true, 1000L);
        assertEquals(0L, c.outOfTouchSince(10999L));
        assertEquals(1000L, c.outOfTouchSince(11000L));
        // what it sent during a silence comes through at once: out of touch for a moment after that
        c.heard(true, 26000L);
        c.heard(false, 26001L);
        assertEquals(1000L, c.outOfTouchSince(26002L));
        assertEquals(1000L, c.outOfTouchSince(29000L));
        assertEquals(0L, c.outOfTouchSince(29001L));
        // pings every few seconds are no silence
        c.heard(true, 31000L);
        c.heard(true, 36000L);
        assertEquals(0L, c.outOfTouchSince(36500L));
    }

    // ---- answers that come too late -----------------------------------------------------------------

    /** The ageMs of each answerDropped of this participant, in order. */
    private List<String> dropped(String clientId) {
        List<String> ages = new ArrayList<>();
        for (Event e : Event.find.where().eq("experimentInstance.id", session.id).eq("name", "answerDropped").order("id").findList()) {
            String player = null;
            String age = null;
            for (EventData d : e.eventData) {
                if ("player".equals(d.name)) player = d.value;
                if ("ageMs".equals(d.name)) age = d.value;
            }
            if (clientId.equals(player)) ages.add(age);
        }
        return ages;
    }

    @Test
    public void anAnswerThatReachesTheServerTooLateIsDroppedAndThePageIsShownWhatTheServerHolds() throws Throwable {
        final long[] now = {System.currentTimeMillis()};
        field("clock").set(null, (LongSupplier) () -> now[0]);
        Page page = connect("p1");
        // The page reckons the server's time from its pongs.
        page.send("Ping");
        assertEquals(now[0], page.last().get("pong").asLong());
        engine().eval("picked = java.util.Collections.synchronizedList([]); "
            + "a.add(g.getVertex('p1'), [name: 'Orange', result: { picked << 'Orange' }]); null");
        String uid = String.valueOf(engine().eval("g.getVertex('p1').choices[0].uid"));
        CustomEvents customEvents = new CustomEvents();
        bus().on("CustomEvent", customEvents);
        try {
            // Clicked into a connection that had died without closing, and delivered 24.5 s later, as
            // the network came back: dropped, and the page is sent the view again (the choice still on
            // offer), so it doesn't show the pick as made. It used to be recorded 24.5 s late.
            int views = page.count(VIEW);
            page.send("MakeChoice", "choiceUID", uid, "bbSentAt", now[0] - 24500);
            assertEquals(0, page.count(ANSWER));
            assertEquals(Arrays.asList(), engine().eval("new ArrayList(picked)"));
            assertEquals(views + 1, page.count(VIEW));
            assertEquals(uid, page.last().get("player").get("choices").get(0).get("uid").asText());
            page.send("CustomEvent", "eventName", "bb:edge-edit", "bbSentAt", now[0] - 11000);
            assertEquals(0, customEvents.heard.size());
            assertEquals(Arrays.asList("24500", "11000"), dropped("p1"));

            // In time: taken, also when the page's reckoning puts it a little ahead, or it doesn't say.
            page.send("CustomEvent", "eventName", "bb:edge-edit", "bbSentAt", now[0] - 9000);
            page.send("CustomEvent", "eventName", "bb:edge-edit", "bbSentAt", now[0] + 2000);
            page.send("CustomEvent", "eventName", "screen-tracker");
            assertEquals(3, customEvents.heard.size());
            page.send("MakeChoice", "choiceUID", uid, "bbSentAt", now[0] - 300);
            assertEquals(1, page.count(ANSWER));
            assertEquals(Arrays.asList("Orange"), engine().eval("new ArrayList(picked)"));
            // A sign-in is never dropped.
            page.send("LogIn", "clientId", "p1", "bbSentAt", now[0] - 60000);
            assertEquals(1, events("clientLogIn", "p1"));
            assertEquals(2, dropped("p1").size());
        } finally {
            bus().off("CustomEvent", customEvents);
        }
    }

    // ---- the game started without them -----------------------------------------------------------

    // The autopilot's launch, as it leaves out someone who has been away too long.
    private void leftOutAtStart(String clientId) throws Exception {
        engine().eval("g.removePlayer('" + clientId + "')");
        ScriptBoard.leftOutAtStart(clientId);
        ExperimentInstance.findById(session.id).setHasStarted(Boolean.TRUE);
    }

    @Test
    public void someoneTheGameStartedWithoutIsToldSoAndThatIsTheirLastScreen() throws Throwable {
        Page page = connect("p1");
        page.hangUp();
        leftOutAtStart("p1");
        assertEquals("recorded as it happens", 1, events("missedStart", "p1"));
        assertEquals("missedStart", client("p1").lastFrame.path("player").path("ended").asText());

        Page back = connect("p1");
        assertEquals(back.frames.toString(), 1, back.frames.size());
        JsonNode player = back.frames.get(0).get("player");
        assertTrue(player.get("finished").asBoolean());
        assertEquals("missedStart", player.get("ended").asText());
        String text = player.get("text").asText();
        assertTrue(text, text.contains("started while your connection was lost"));
        assertTrue(text, text.contains("contact the researcher"));
        assertFalse(text, text.contains("Prolific"));
        assertTrue("nothing more comes for them", back.closed);
        assertEquals(1, events("missedStart", "p1"));
        assertEquals("not counted as back in the game", 1, events("clientConnected", "p1"));
        assertEquals(back.frames.get(0), client("p1").lastFrame);
        assertNull(engine().eval("g.getVertex('p1')"));

        // Again later: the same screen, recorded once.
        Page again = connect("p1");
        assertEquals("missedStart", again.frames.get(0).get("player").get("ended").asText());
        assertEquals(1, events("missedStart", "p1"));
    }

    @Test
    public void someoneTakenOutOfTheNetworkAnotherWayGetsTheirLastScreenBack() throws Throwable {
        // A researcher takes a player out of the network mid-game (the console's snippet), or the
        // experiment does: nothing tells them the game started without them.
        Page page = connect("p1");
        engine().eval("g.getVertex('p1').text = '<p>Round 2</p>'; null");
        page.hangUp();
        engine().eval("g.removePlayer('p1')");
        ExperimentInstance.findById(session.id).setHasStarted(Boolean.TRUE);
        final JsonNode last = client("p1").lastFrame;
        assertTrue(last.path("player").path("text").asText().contains("Round 2"));

        Page back = connect("p1");
        assertEquals(last, back.frames.get(0));
        assertFalse(back.closed);
        assertSame(last, client("p1").lastFrame);
        assertEquals(0, events("missedStart", "p1"));
    }

    @Test
    public void someoneTheAutopilotLeftOutIsToldSoAfterTheEngineWasRebuilt() throws Throwable {
        engine().eval("initStep = stepFactory.createStep('InitStep'); initStep.run = { }; initStep.done = { }; "
            + "autopilot.waitingRoom([minPlayers: 2, maxPlayers: 2, waitSeconds: 60, countdownSeconds: 0]); null");
        Page gone = connect("p1");
        gone.hangUp();
        // (away for longer than the autopilot keeps a seat)
        engine().eval("g.getVertex('p1')._system.disconnectedAt = System.currentTimeMillis() - 120000; null");
        connect("p2");
        connect("p3");
        await("the game started", () -> Boolean.TRUE.equals(ExperimentInstance.findById(session.id).hasStarted));
        assertNull("left out", engine().eval("g.getVertex('p1')"));
        assertEquals(1, events("missedStart", "p1"));

        // The engine is rebuilt for the next session before they come back: their page, reconnecting
        // to this one, is told what happened, as their last screen, and stops there.
        console.send("LaunchGame", "name", "Next", "parameters", Json.newObject());
        await("the next session", () -> ExperimentInstance.find.where().eq("experiment.id", experiment.id).eq("name", "Next")
            .eq("status", ExperimentInstance.Status.RUNNING).findRowCount() == 1);
        assertNull(client("p1"));
        Page back = connect("p1");
        JsonNode player = back.frames.get(0).get("player");
        assertEquals(back.frames.toString(), "missedStart", player.path("ended").asText());
        assertTrue(player.path("finished").asBoolean());
        assertEquals(1, events("missedStart", "p1"));
    }

    @Test
    public void aProlificParticipantTheGameStartedWithoutIsAskedToReturnTheStudy() throws Throwable {
        Recruitment.prolificEntry("pp-1", "a-prolific-id", "study", "session");
        Page page = connect("pp-1");
        assertEquals("prolific", client("pp-1").lastFrame.path("player").path("_system").path("recruitment").path("source").asText());
        // Known from the session's entries too (here their screens never had their Prolific details).
        java.util.LinkedHashMap<Object, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("clientId", "pp-2");
        ScriptBoard.trackEvent("prolificEntry", entry);
        Page other = connect("pp-2");
        page.hangUp();
        other.hangUp();
        leftOutAtStart("pp-1");
        leftOutAtStart("pp-2");

        String text = connect("pp-1").frames.get(0).get("player").get("text").asText();
        assertTrue(text, text.contains("return the study on Prolific"));
        text = connect("pp-2").frames.get(0).get("player").get("text").asText();
        assertTrue(text, text.contains("return the study on Prolific"));
    }

    @Test
    public void someoneAtTheirOwnEndKeepsThatScreen() throws Throwable {
        // The experiment finishes them, with a code; they leave, and are taken out of the network.
        Page page = connect("p1");
        engine().eval("def v = g.getVertex('p1'); v.finished = true; v.text = '<p>Your code is ABC123</p>'; null");
        page.hangUp();
        leftOutAtStart("p1");
        Page back = connect("p1");
        JsonNode player = back.frames.get(0).get("player");
        assertTrue(player.get("finished").asBoolean());
        assertTrue(player.get("text").asText().contains("ABC123"));
        assertFalse(back.closed);
        assertEquals(0, events("missedStart", "p1"));
    }

    // ---- the same view isn't sent twice ----------------------------------------------------------

    @Test
    public void theSameViewIsNotSentTwiceToASocket() {
        WebSocketTest.TestWebSocketOut seen = new WebSocketTest.TestWebSocketOut();
        Client client = new Client("p1", session, null, new ThrottledWebSocketOut(seen, 0L));
        EventGraph<TinkerGraph> graph = new EventGraph<>(new TinkerGraph());
        Vertex p1 = graph.addVertex("p1");
        p1.setProperty("score", 1);
        Vertex p2 = graph.addVertex("p2");
        graph.addEdge(null, p1, p2, "connected");

        client.updateGraph(p1);
        client.updateGraph(p1);
        client.updateGraph(p1);
        assertEquals("unchanged: sent once", 1, seen.messages.size());
        // A pong in between changes nothing on the screen.
        client.seat().writeAside(Json.newObject().put("pong", 1L));
        client.updateGraph(p1);
        assertEquals(2, seen.messages.size());
        p1.setProperty("score", 2);
        client.updateGraph(p1);
        assertEquals(3, seen.messages.size());
        assertEquals(2, seen.last().get("player").get("score").asInt());
        client.updateGraph(p1);
        assertEquals(3, seen.messages.size());
        // Another frame in between: the view is sent again, as the page's screen may have changed.
        client.send("note");
        client.updateGraph(p1);
        assertEquals(5, seen.messages.size());
        assertTrue(seen.last().has("graph"));

        // A page that just reconnected, and a preview that starts watching, get the whole view.
        WebSocketTest.TestWebSocketOut again = new WebSocketTest.TestWebSocketOut();
        assertEquals(Client.Arrival.SEATED, connectAfterClose(client, again));
        WebSocketTest.TestWebSocketOut watching = new WebSocketTest.TestWebSocketOut();
        Client.Connection watcher = new Client.Connection(null, new ThrottledWebSocketOut(watching, 0L), true);
        assertEquals(Client.Arrival.WATCHING, client.connect(watcher));
        client.updateGraph(p1);
        assertEquals(1, again.messages.size());
        assertEquals(2, watching.messages.size());
        assertTrue(watching.messages.get(0).get("bbWatching").asBoolean());
        assertEquals(2, watching.last().get("player").get("score").asInt());
        client.updateGraph(p1);
        assertEquals(1, again.messages.size());
        assertEquals(2, watching.messages.size());
    }

    // The seat holder's socket closed; a new page of the participant takes the seat.
    private static Client.Arrival connectAfterClose(Client client, WebSocketTest.TestWebSocketOut to) {
        client.closed(client.seat());
        return client.connect(new Client.Connection(null, new ThrottledWebSocketOut(to, 0L), false));
    }

    // ---- researchers' previews -------------------------------------------------------------------

    @Test
    public void aPreviewOfAConnectedPlayerOnlyWatches() throws Throwable {
        Page participant = connect("p1");
        final int theirs = participant.frames.size();
        Page preview = connect("p1", true);

        assertTrue("told first", WATCHING.matches(preview.frames.get(0)));
        assertEquals("then the player's view", 1, preview.count(VIEW));
        assertTrue(preview.last().has("style"));
        assertFalse(preview.closed);
        assertFalse("the participant keeps their seat", participant.closed);
        assertEquals("and is sent nothing", theirs, participant.frames.size());
        assertSame(participant.out, client("p1").out);
        assertEquals("no connection recorded for it", 1, events("clientConnected", "p1"));
        assertNull("their own page holds the seat", system("p1").get("preview"));

        // Its sign-in is recorded, as a preview's.
        preview.send("LogIn");
        int previewLogIns = 0;
        for (Event e : Event.find.where().eq("experimentInstance.id", session.id).eq("name", "clientLogIn").findList()) {
            for (EventData d : e.eventData) if ("preview".equals(d.name) && "true".equals(d.value)) previewLogIns++;
        }
        assertEquals(1, previewLogIns);

        // It sees every frame the participant gets.
        engine().eval("g.getVertex('p1').score = 7");
        assertEquals(7, participant.last().get("player").get("score").asInt());
        assertEquals(participant.last(), preview.last());

        // What it sends doesn't act for the player; what the participant sends does.
        CustomEvents customEvents = new CustomEvents();
        bus().on("CustomEvent", customEvents);
        try {
            preview.send("CustomEvent", "eventName", "from-the-preview");
            preview.send("MakeChoice", "choiceUID", "no-such-choice");
            assertEquals(0, customEvents.heard.size());
            assertEquals(0, preview.count(ANSWER));
            participant.send("CustomEvent", "eventName", "from-the-participant");
            participant.send("MakeChoice", "choiceUID", "no-such-choice");
            assertEquals(1, customEvents.heard.size());
            assertEquals(1, participant.count(ANSWER));
        } finally {
            bus().off("CustomEvent", customEvents);
        }

        // Its going changes nothing else.
        int frames = participant.frames.size();
        preview.hangUp();
        assertTrue(client("p1").watchers().isEmpty());
        assertEquals(Boolean.TRUE, system("p1").get("connected"));
        assertEquals(0, events("clientDisconnected", "p1"));
        engine().eval("g.getVertex('p1').score = 8");
        assertEquals(frames + 1, participant.frames.size());
        assertEquals(7, preview.last().get("player").get("score").asInt());

        // A preview that pinged and fell silent is closed and dropped, and that is all.
        Page silent = connect("p1", true);
        silent.send("Ping");
        closeSilentPages(System.currentTimeMillis() + 31000);
        assertTrue(silent.closed);
        assertTrue(client("p1").watchers().isEmpty());
        assertFalse(participant.closed);
        assertEquals(Boolean.TRUE, system("p1").get("connected"));
        assertEquals(0, events("clientDisconnected", "p1"));
    }

    @Test
    public void theParticipantComingBackTakesTheSeatFromAPreviewWhichThenWatches() throws Throwable {
        testPlayer("p1");
        Page preview = connect("p1", true);
        assertEquals("the participant is away: it holds the seat", 0, preview.count(WATCHING));
        assertTrue(preview.count(VIEW) >= 1);
        final int previewViews = preview.count(VIEW);

        Page participant = connect("p1");
        assertEquals(1, preview.count(WATCHING));
        assertFalse("not closed", preview.closed);
        assertEquals(0, preview.count(REPLACED));
        assertSame(participant.out, client("p1").out);
        assertEquals(1, participant.count(VIEW));
        assertEquals("only that the seat is no longer a preview's", previewViews + 1, preview.count(VIEW));
        assertEquals(participant.frames.get(0), preview.frames.get(preview.frames.size() - 1));
        assertFalse(participant.frames.get(0).path("player").path("_system").has("preview"));
        assertEquals(1, client("p1").watchers().size());

        engine().eval("g.getVertex('p1').score = 3");
        assertEquals(participant.last(), preview.last());
        CustomEvents customEvents = new CustomEvents();
        bus().on("CustomEvent", customEvents);
        try {
            preview.send("CustomEvent", "eventName", "from-the-preview");
            assertEquals(0, customEvents.heard.size());
        } finally {
            bus().off("CustomEvent", customEvents);
        }

        // Their page going: the preview takes the seat until it is back, and then watches again.
        participant.hangUp();
        assertEquals(1, preview.count(NOT_WATCHING));
        assertSame(preview.out, client("p1").out);
        Page back = connect("p1");
        assertEquals(2, preview.count(WATCHING));
        engine().eval("g.getVertex('p1').score = 4");
        assertEquals(back.last(), preview.last());
        assertEquals(4, preview.last().get("player").get("score").asInt());
    }

    @Test
    public void aPreviewHoldingAFreeSeatIsConnectedAndMarkedSoInTheData() throws Throwable {
        // A preview of someone away holds their seat: connected (that is how test players are
        // played), and marked as a preview's, for the console, which their neighbours never see.
        Page neighbour = connect("p2");
        testPlayer("p1");
        Page preview = connect("p1", true);
        assertEquals(Boolean.TRUE, system("p1").get("connected"));
        assertEquals(Boolean.TRUE, system("p1").get("preview"));
        assertEquals("recorded as a preview's", 1, events("clientConnected", "p1", true));
        assertEquals(0, events("clientConnected", "p1", false));
        engine().eval("g.addEdge(g.getVertex('p1'), g.getVertex('p2'), 'connected'); null");
        JsonNode nodes = neighbour.last().get("graph").get("nodes");
        assertEquals(nodes.toString(), 2, nodes.size());
        for (JsonNode node : nodes) assertFalse(nodes.toString(), node.has("_system") && !"p2".equals(node.path("id").asText()));

        // Their own page comes back: the seat is theirs, the flag goes, the preview watches. The
        // data says the preview went and they came.
        Page participant = connect("p1");
        assertEquals(1, preview.count(WATCHING));
        assertEquals(Boolean.TRUE, system("p1").get("connected"));
        assertNull(system("p1").get("preview"));
        assertEquals(1, events("clientDisconnected", "p1", true));
        assertEquals(1, events("clientConnected", "p1", false));
        assertEquals(0, events("clientDisconnected", "p1", false));

        // The preview going while it only watches changes nothing.
        Page second = connect("p1", true);
        second.hangUp();
        assertEquals(Boolean.TRUE, system("p1").get("connected"));
        assertEquals(1, events("clientConnected", "p1", true));
        assertEquals(1, events("clientDisconnected", "p1", true));

        // Their page goes, and so does the preview, and a new preview opens: now that their own page
        // has held the seat, a preview only stands in for them. Nothing is recorded for it.
        participant.hangUp();
        preview.hangUp();
        Page tile = connect("p1", true);
        assertSame(tile.out, client("p1").out);
        assertEquals(Boolean.FALSE, system("p1").get("connected"));
        assertEquals(Boolean.TRUE, system("p1").get("preview"));
        assertEquals(1, events("clientConnected", "p1", true));
        tile.hangUp();
        assertEquals(Boolean.FALSE, system("p1").get("connected"));
        assertNull(system("p1").get("preview"));
        assertEquals(1, events("clientDisconnected", "p1", true));
        assertEquals(1, events("clientDisconnected", "p1", false));
    }

    @Test
    public void aPreviewThatTakesTheSeatAsTheirPageGoesOnlyStandsInForThem() throws Throwable {
        Page participant = connect("p1");
        Page preview = connect("p1", true);
        Announcements announced = new Announcements();
        bus().on("bb:connection", announced);
        try {
            participant.hangUp();
            // The preview takes the seat and is told so: its choices count from now on...
            assertEquals(1, preview.count(NOT_WATCHING));
            assertSame(preview.out, client("p1").out);
            assertEquals(Boolean.TRUE, system("p1").get("preview"));
            // ...but nobody is there: they have left, as for anyone whose page goes.
            assertEquals(Boolean.FALSE, system("p1").get("connected"));
            assertNotNull(system("p1").get("disconnectedAt"));
            assertEquals(1, events("clientDisconnected", "p1"));
            assertEquals("no connection recorded for the preview", 1, events("clientConnected", "p1"));
            assertEquals(Arrays.<Object>asList(false), announced.heard);
            CustomEvents customEvents = new CustomEvents();
            bus().on("CustomEvent", customEvents);
            try {
                preview.send("CustomEvent", "eventName", "from-the-preview");
                assertEquals(1, customEvents.heard.size());
            } finally {
                bus().off("CustomEvent", customEvents);
            }

            // Its going takes the flag with it, and records nothing.
            preview.hangUp();
            assertNull(system("p1").get("preview"));
            assertEquals(Boolean.FALSE, system("p1").get("connected"));
            assertEquals(1, events("clientDisconnected", "p1"));
            assertEquals(Arrays.<Object>asList(false), announced.heard);
        } finally {
            bus().off("bb:connection", announced);
        }
    }

    @Test
    public void aWatchedParticipantWhoLeavesIsHandedToTheComputerAndThePreviewCanStillChooseForThem() throws Throwable {
        Page participant = connect("p1");
        Page preview = connect("p1", true);
        // The game starts.
        engine().eval("autopilot.replaceDisconnected([afterSeconds: 1]); autopilot.onGameStart(); "
            + "picked = java.util.Collections.synchronizedList([]); null");
        ExperimentInstance.findById(session.id).setHasStarted(Boolean.TRUE);
        participant.hangUp();
        assertEquals(1, preview.count(NOT_WATCHING));
        assertEquals("no connection recorded for the preview", 1, events("clientConnected", "p1"));

        // The preview chooses for them: it counts, as a preview's choices always did.
        engine().eval("a.add(g.getVertex('p1'), [name: 'Left', result: { picked << 'Left' }]); null");
        String uid = String.valueOf(engine().eval("g.getVertex('p1').choices[0].uid"));
        preview.send("MakeChoice", "choiceUID", uid);
        assertEquals(1, preview.count(ANSWER));
        assertEquals(Arrays.asList("Left"), engine().eval("new ArrayList(picked)"));

        // The computer takes their seat after afterSeconds, as for anyone who leaves. It used to wait
        // for as long as the preview was open.
        // (the event is recorded right after the seat is handed over: awaited, not the seat)
        await("the computer's takeover", () -> eventsWith("aiTakeover", "playerId", "p1") == 1);
        assertEquals(1, engine().eval("g.getVertex('p1').ai"));
        assertEquals(Boolean.FALSE, system("p1").get("connected"));

        // Their page comes back: the seat is theirs again, from the computer, and the preview watches.
        Page back = connect("p1");
        assertEquals(2, preview.count(WATCHING));
        assertSame(back.out, client("p1").out);
        assertEquals(Boolean.TRUE, system("p1").get("connected"));
        assertNull(system("p1").get("preview"));
        assertEquals(2, events("clientConnected", "p1"));
        await("the seat given back", () -> eventsWith("aiReturned", "playerId", "p1") == 1);
        assertNull(engine().eval("g.getVertex('p1').ai"));
        engine().eval("autopilot.stop(); null");
    }

    @Test
    public void aWatchedParticipantWhoLeavesTheWaitingRoomIsNoLongerCounted() throws Throwable {
        engine().eval("autopilot.waitingRoom([minPlayers: 2, maxPlayers: 5, waitSeconds: 60, departureGraceSeconds: 0.3]); null");
        Page first = connect("p1");
        Page participant = connect("p2");
        Page preview = connect("p2", true);
        assertNotNull("enough are here: the wait for more began", engine().eval("autopilot.startAt"));
        participant.hangUp();
        assertEquals(1, preview.count(NOT_WATCHING));
        assertEquals("the waiting room counts only who is there", 1, engine().eval("autopilot.connectedPlayers().size()"));
        assertEquals(1, waitingRoom(first).path("here").asInt());
        // The console reloads the tile, then opens it full size: new previews, still not counted.
        preview.hangUp();
        connect("p2", true);
        connect("p2", true);
        assertEquals(1, engine().eval("autopilot.connectedPlayers().size()"));
        assertEquals(1, waitingRoom(first).path("here").asInt());
        assertNotNull("their departure counts after the grace", engine().eval("autopilot.startAt"));
        await("the start called off", () -> engine().eval("autopilot.startAt") == null);
        assertEquals(1, events("clientConnected", "p2"));
        assertEquals(1, events("clientDisconnected", "p2"));
        engine().eval("autopilot.stop(); null");
    }

    @Test
    public void aPreviewOfAComputerPlayerWatchesWhatTheComputerDoes() throws Throwable {
        engine().eval("autopilot.replaceDisconnected([afterSeconds: 0.2, behavior: { p -> }]); autopilot.onGameStart(); "
            + "bot = g.addAI(a, 1, { p -> })[0]; botBehavior = a.ai.behaviors[bot]; null");
        ExperimentInstance.findById(session.id).setHasStarted(Boolean.TRUE);
        final String id = String.valueOf(engine().eval("bot.id"));
        assertTrue(id, id.startsWith("_"));
        Announcements announced = new Announcements();
        bus().on("bb:connection", announced);
        try {
            Page preview = connect(id, true);
            assertTrue("told first", WATCHING.matches(preview.frames.get(0)));
            assertEquals("then the computer's view", 1, preview.count(VIEW));
            assertFalse(preview.closed);
            assertEquals("the computer still plays it", 1, engine().eval("bot.ai"));
            assertNull("nothing recorded on it", system(id).get("connected"));
            assertNull(system(id).get("preview"));
            assertTrue(announced.heard.isEmpty());

            // It sees what the computer's seat gets; what it sends doesn't act for it.
            engine().eval("bot.score = 5; null");
            assertEquals(5, preview.last().get("player").get("score").asInt());
            CustomEvents customEvents = new CustomEvents();
            bus().on("CustomEvent", customEvents);
            try {
                preview.send("CustomEvent", "eventName", "from-the-preview");
                preview.send("MakeChoice", "choiceUID", "no-such-choice");
                assertEquals(0, customEvents.heard.size());
                assertEquals(0, preview.count(ANSWER));
            } finally {
                bus().off("CustomEvent", customEvents);
            }

            // Its going leaves the seat to the computer, with the experiment's own behaviour.
            preview.hangUp();
            Thread.sleep(400);
            assertEquals(1, engine().eval("bot.ai"));
            assertEquals(Boolean.TRUE, engine().eval("a.ai.behaviors[bot].is(botBehavior)"));
            assertEquals(Boolean.FALSE, engine().eval("autopilot.takeoverTimers.containsKey('" + id + "')"));
            assertTrue(announced.heard.isEmpty());
            assertEquals(0, events("clientConnected", id) + events("clientDisconnected", id));
            assertEquals(0, eventsWith("aiReturned", "playerId", id) + eventsWith("aiTakeover", "playerId", id));
            assertTrue(client(id).watchers().isEmpty());
        } finally {
            bus().off("bb:connection", announced);
            engine().eval("autopilot.stop(); null");
        }
    }

    // ---- a preview of someone whose own page has held the seat only stands in for them ---------------

    @Test
    public void aPreviewOpenedAfterTheStandInClosedOnlyStandsInAndTheComputerGoesOnPlaying() throws Throwable {
        Page participant = connect("p1");
        Page tile = connect("p1", true);
        engine().eval("autopilot.replaceDisconnected([afterSeconds: 0.5]); autopilot.onGameStart(); null");
        ExperimentInstance.findById(session.id).setHasStarted(Boolean.TRUE);
        Announcements announced = new Announcements();
        bus().on("bb:connection", announced);
        try {
            participant.hangUp();
            assertEquals(1, tile.count(NOT_WATCHING));
            // The stand-in closes, and a new preview of them opens (the console reopens the tile).
            tile.hangUp();
            Page again = connect("p1", true);
            assertSame(again.out, client("p1").out);
            assertEquals("still away", Boolean.FALSE, system("p1").get("connected"));
            assertEquals(Boolean.TRUE, system("p1").get("preview"));
            assertEquals("nothing recorded for it", 1, events("clientConnected", "p1"));
            assertEquals(Arrays.<Object>asList(false), announced.heard);
            // Its choices count, as a preview's always did...
            CustomEvents customEvents = new CustomEvents();
            bus().on("CustomEvent", customEvents);
            try {
                again.send("CustomEvent", "eventName", "from-the-preview");
                assertEquals(1, customEvents.heard.size());
            } finally {
                bus().off("CustomEvent", customEvents);
            }
            // ...and the computer takes their seat after afterSeconds, with it open.
            await("the computer's takeover", () -> eventsWith("aiTakeover", "playerId", "p1") == 1);
            assertEquals(1, engine().eval("g.getVertex('p1').ai"));

            // A preview of the seat the computer took over (the Preview page's "AI players" pick): the
            // computer goes on playing it.
            again.hangUp();
            Page aiPick = connect("p1", true);
            assertSame(aiPick.out, client("p1").out);
            Thread.sleep(700);
            assertEquals(1, engine().eval("g.getVertex('p1').ai"));
            assertEquals(0, eventsWith("aiReturned", "playerId", "p1"));
            assertEquals(Boolean.FALSE, system("p1").get("connected"));
            assertEquals(1, events("clientConnected", "p1"));
            assertEquals(1, events("clientDisconnected", "p1"));
            assertEquals(Arrays.<Object>asList(false), announced.heard);

            // Their own page comes back: the seat is theirs, from the computer, and the preview watches.
            Page back = connect("p1");
            assertSame(back.out, client("p1").out);
            assertEquals(1, aiPick.count(WATCHING));
            assertEquals(Boolean.TRUE, system("p1").get("connected"));
            await("the seat given back", () -> eventsWith("aiReturned", "playerId", "p1") == 1);
            assertEquals(Arrays.<Object>asList(false, true), announced.heard);
        } finally {
            bus().off("bb:connection", announced);
            engine().eval("autopilot.stop(); null");
        }
    }

    @Test
    public void aTileOpenedFullSizeAndClosedAgainWhileTheParticipantIsAwayOnlyStandsIn() throws Throwable {
        Page participant = connect("p1");
        Page tile = connect("p1", true);
        engine().eval("autopilot.replaceDisconnected([afterSeconds: 0.5]); autopilot.onGameStart(); null");
        ExperimentInstance.findById(session.id).setHasStarted(Boolean.TRUE);
        Announcements announced = new Announcements();
        bus().on("bb:connection", announced);
        try {
            participant.hangUp();
            assertEquals(1, tile.count(NOT_WATCHING));
            // Opened full size: the dialog's preview comes while the tile's is still open.
            Page focus = connect("p1", true);
            assertEquals(1, tile.count(REPLACED));
            assertTrue(tile.closed);
            assertSame(focus.out, client("p1").out);
            assertEquals(Boolean.FALSE, system("p1").get("connected"));
            assertEquals(Boolean.TRUE, system("p1").get("preview"));
            // (the tile's own close comes later, and changes nothing)
            tile.hangUp();
            assertSame(focus.out, client("p1").out);
            // Closed again: the tile reloads, then the dialog's socket closes.
            Page reloaded = connect("p1", true);
            focus.hangUp();
            assertSame(reloaded.out, client("p1").out);
            assertEquals(Boolean.FALSE, system("p1").get("connected"));
            assertEquals(Boolean.TRUE, system("p1").get("preview"));
            assertEquals(1, events("clientConnected", "p1"));
            assertEquals(0, events("clientConnected", "p1", true));
            assertEquals(1, events("clientDisconnected", "p1"));
            assertEquals(Arrays.<Object>asList(false), announced.heard);
            // The computer takes their seat after afterSeconds all the same.
            await("the computer's takeover", () -> eventsWith("aiTakeover", "playerId", "p1") == 1);
            assertEquals(1, engine().eval("g.getVertex('p1').ai"));
            assertEquals(0, eventsWith("aiReturned", "playerId", "p1"));
        } finally {
            bus().off("bb:connection", announced);
            engine().eval("autopilot.stop(); null");
        }
    }

    // ---- a preview adds nobody to a real session, and the rule outlives a rebuild of the engine -------

    private static final AdminSocketsTest.Matcher NOT_IN_GAME = f -> "notInGame".equals(f.path("player").path("ended").asText());

    @Test
    public void aPreviewOfSomeoneWhoIsntInTheGameAddsNobody() throws Throwable {
        engine().eval("autopilot.waitingRoom([minPlayers: 2, maxPlayers: 5, waitSeconds: 60]); null");
        Page participant = connect("p1");
        Page preview = connect("p9", true);
        assertEquals(preview.frames.toString(), 1, preview.frames.size());
        assertTrue(NOT_IN_GAME.matches(preview.frames.get(0)));
        assertTrue(preview.frames.get(0).path("player").path("text").asText().contains("p9 isn't in this session's game"));
        assertNull("no player", engine().eval("g.getVertex('p9')"));
        assertNull("no seat", client("p9"));
        assertEquals("the waiting room counts nobody else", 1, engine().eval("autopilot.connectedPlayers().size()"));
        assertNull("nor does the wait for more begin", engine().eval("autopilot.startAt"));
        assertEquals(0, events("clientConnected", "p9"));
        // nor after the start (it used to say the session had started, as to a newcomer)
        ExperimentInstance.findById(session.id).setHasStarted(Boolean.TRUE);
        Page later = connect("p8", true);
        assertTrue(later.frames.toString(), NOT_IN_GAME.matches(later.frames.get(0)));
        assertNull(client("p8"));
        assertFalse(participant.closed);
        engine().eval("autopilot.stop(); null");
    }

    /**
     * Stop and Resume: the engine is rebuilt for the same session, and every Client goes with the old
     * one. The console's Preview page keeps its tiles, which load again: a preview of a former
     * participant made them a new Client, counted as connected, and, before the start, added their
     * player again (OnJoinStep and all), so the waiting room could start a game with nobody but
     * previews in it.
     */
    @Test
    public void aPreviewOfAFormerParticipantAfterAResumeNeitherAddsThemNorCountsAsThem() throws Throwable {
        Page participant = connect("p1");
        participant.hangUp();
        console.send("StopGame", "id", session.id);
        await("stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);
        console.send("SelectInstance", "id", session.id);
        await("resumed", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.RUNNING
            && engineRuns(session) && client("p1") == null);
        engine().eval("autopilot.waitingRoom([minPlayers: 2, maxPlayers: 5, waitSeconds: 60]); null");
        Page other = connect("p2");
        assertEquals(1, engine().eval("autopilot.connectedPlayers().size()"));

        Page tile = connect("p1", true);
        assertTrue(tile.frames.toString(), NOT_IN_GAME.matches(tile.frames.get(0)));
        assertNull("no player re-created", engine().eval("g.getVertex('p1')"));
        assertNull(client("p1"));
        assertEquals("the waiting room's count is the same", 1, engine().eval("autopilot.connectedPlayers().size()"));
        assertNull(engine().eval("autopilot.startAt"));
        assertEquals("no connection recorded since the one before the stop", 1, events("clientConnected", "p1"));
        assertEquals(0, events("clientConnected", "p1", true));

        // Even with their player in the network again (a script added it), a preview only stands in for
        // them: their own page held the seat before the stop, as the session's data says (read again
        // here, as after a restart of the server).
        engine().eval("g.addPlayer('p1'); null");
        ((Map<?, ?>) field("ownPagesHeld").get(null)).clear();
        Page again = connect("p1", true);
        assertSame(again.out, client("p1").out);
        assertEquals(0, again.count(WATCHING));
        assertEquals(Boolean.FALSE, system("p1").get("connected"));
        assertEquals(Boolean.TRUE, system("p1").get("preview"));
        assertEquals(1, engine().eval("autopilot.connectedPlayers().size()"));
        assertNull(engine().eval("autopilot.startAt"));
        assertEquals(1, events("clientConnected", "p1"));
        assertEquals(0, events("clientConnected", "p1", true));
        // Their own page coming back counts, as ever.
        Page back = connect("p1");
        assertEquals(1, again.count(WATCHING));
        assertEquals(Boolean.TRUE, system("p1").get("connected"));
        assertEquals(2, engine().eval("autopilot.connectedPlayers().size()"));
        assertEquals(2, events("clientConnected", "p1"));
        assertFalse(other.closed);
        assertFalse(back.closed);
        engine().eval("autopilot.stop(); null");
    }

    @Test
    public void aPreviewOfAFormerParticipantAfterAReloadThatCarriesTheSessionOnNeverCountsAsThem() throws Throwable {
        Page participant = connect("p1");
        final Client before = client("p1");
        console.send("ReloadEngine");
        await("rebuilt", () -> participant.closed && client("p1") == null);
        assertEquals("carried on", ExperimentInstance.Status.RUNNING, ExperimentInstance.findById(session.id).status);
        assertTrue(engineRuns(session));
        engine().eval("autopilot.waitingRoom([minPlayers: 1, maxPlayers: 5, waitSeconds: 60]); null");

        // The tile reconnects before their page does.
        Page tile = connect("p1", true);
        assertTrue(tile.frames.toString(), NOT_IN_GAME.matches(tile.frames.get(0)));
        assertNull(engine().eval("g.getVertex('p1')"));
        assertNull(client("p1"));
        assertEquals(0, engine().eval("autopilot.connectedPlayers().size()"));
        assertNull("no start for a preview", engine().eval("autopilot.startAt"));

        engine().eval("g.addPlayer('p1'); null");
        Page again = connect("p1", true);
        assertNotSame(before, client("p1"));
        assertEquals(Boolean.FALSE, system("p1").get("connected"));
        assertEquals(Boolean.TRUE, system("p1").get("preview"));
        assertEquals(0, engine().eval("autopilot.connectedPlayers().size()"));
        assertNull(engine().eval("autopilot.startAt"));
        assertEquals(1, events("clientConnected", "p1"));
        assertEquals(0, events("clientConnected", "p1", true));
        // their page reconnects: it takes the seat, and the preview watches it
        Page back = connect("p1");
        assertEquals(1, again.count(WATCHING));
        assertEquals(Boolean.TRUE, system("p1").get("connected"));
        assertEquals(2, events("clientConnected", "p1"));
        assertFalse(back.closed);
        engine().eval("autopilot.stop(); null");
    }

    /** Whether the engine runs this session now (its game listener's). */
    private static boolean engineRuns(ExperimentInstance session) throws Exception {
        Method m = ScriptBoard.class.getDeclaredMethod("engineSession");
        m.setAccessible(true);
        ExperimentInstance running = (ExperimentInstance) m.invoke(null);
        return running != null && session.id.equals(running.id);
    }

    @Test
    public void testPlayersOfATestSessionArePlayedThroughPreviews() throws Throwable {
        // The researcher's test session (0) runs once no real one does: they create test players by
        // previewing new ids, and play them there. No page of theirs ever holds the seat.
        console.send("StopGame", "id", session.id);
        await("stopped", () -> ExperimentInstance.findById(session.id).status == ExperimentInstance.Status.STOPPED);
        assertTrue(ScriptBoard.runsSession(experiment.id.toString(), "0"));
        engine().eval("autopilot.replaceDisconnected([afterSeconds: 0.3]); autopilot.onGameStart(); null");
        Announcements announced = new Announcements();
        bus().on("bb:connection", announced);
        try {
            Page t1 = previewInTestSession("t1");
            Page t2 = previewInTestSession("t2");
            assertNotNull(engine().eval("g.getVertex('t1')"));
            assertEquals(Boolean.TRUE, system("t1").get("connected"));
            assertEquals(Boolean.TRUE, system("t1").get("preview"));
            assertEquals(Boolean.TRUE, system("t2").get("connected"));
            assertEquals(0, t1.count(WATCHING));
            assertEquals(Arrays.<Object>asList(true, true), announced.heard);
            // Played through the preview: what it sends acts for them.
            CustomEvents customEvents = new CustomEvents();
            bus().on("CustomEvent", customEvents);
            try {
                t1.send("CustomEvent", "eventName", "from-the-preview");
                assertEquals(1, customEvents.heard.size());
            } finally {
                bus().off("CustomEvent", customEvents);
            }
            // Its tile reopened: connected again at once, and the computer never takes them over.
            t1.hangUp();
            Page again = previewInTestSession("t1");
            assertSame(again.out, client("t1").out);
            assertEquals(Boolean.TRUE, system("t1").get("connected"));
            Thread.sleep(800);
            assertNull(engine().eval("g.getVertex('t1').ai"));
            assertNull(engine().eval("g.getVertex('t2').ai"));
            assertEquals(Arrays.<Object>asList(true, true, false, true), announced.heard);
        } finally {
            bus().off("bb:connection", announced);
            engine().eval("autopilot.stop(); null");
        }
    }

    /** A researcher's preview of a player of the experiment's test session (0). */
    private Page previewInTestSession(String clientId) throws Exception {
        Page page = new Page();
        ScriptBoard.addClient(experiment.id.toString(), Experiment.TEST_INSTANCE_ID.toString(), clientId, page.in, page.out, true);
        return page;
    }

    // ---- a page that takes the seat from an open one ---------------------------------------------

    /** The waiting room's state on the last view this page got: player._system.autopilot. */
    private static JsonNode waitingRoom(Page page) {
        for (int i = page.frames.size() - 1; i >= 0; i--) {
            JsonNode state = page.frames.get(i).path("player").path("_system").path("autopilot");
            if (state.isObject()) return state;
        }
        return null;
    }

    @Test
    public void aTabThatTakesTheSeatIsShownTheWaitingRoomAsItIsNow() throws Throwable {
        engine().eval("autopilot.waitingRoom([minPlayers: 2, maxPlayers: 3, waitSeconds: 60]); null");
        connect("p1");
        connect("p2");
        // Twenty seconds into the wait for more, p2 opens the study in another tab.
        engine().eval("autopilot.startAt -= 20000; null");
        Page tab = connect("p2");
        int startsIn = waitingRoom(tab).path("startsIn").asInt();
        assertTrue("startsIn " + startsIn, startsIn >= 39 && startsIn <= 40);

        // And during the countdown.
        engine().eval("autopilot.waitingRoom([minPlayers: 2, maxPlayers: 3, countdownSeconds: 30]); null");
        connect("p3");
        engine().eval("autopilot.countdownEndsAt -= 12000; null");
        Page late = connect("p3");
        JsonNode countdown = waitingRoom(late);
        assertEquals(countdown.toString(), "starting", countdown.path("state").asText());
        assertEquals(18, countdown.path("seconds").asInt());
        assertEquals("connected once each", 1, events("clientConnected", "p3"));
        assertEquals(1, events("clientConnected", "p2"));
    }

    @Test
    public void aPlayerAddedAgainIsMarkedConnectedByTheTabThatTakesTheSeat() throws Throwable {
        engine().eval("autopilot.waitingRoom([minPlayers: 3, waitSeconds: 60]); null");
        final List<Object> announced = new CopyOnWriteArrayList<>();
        Closure listener = new Closure(null) {
            public Object doCall(Object info) { announced.add(((Map<?, ?>) info).get("connected")); return null; }
        };
        bus().on("bb:connection", listener);
        try {
            connect("p1");
            engine().eval("g.removePlayer('p1'); g.addPlayer('p1'); null");
            assertNull(system("p1").get("connected"));
            connect("p1");
            assertEquals(Boolean.TRUE, system("p1").get("connected"));
            assertEquals("the waiting room counts them", 1, engine().eval("autopilot.connectedPlayers().size()"));
            connect("p1");
            assertEquals("announced each time a page took the seat", 3, announced.size());
            assertFalse(announced.contains(Boolean.FALSE));
            assertEquals("recorded once", 1, events("clientConnected", "p1"));
            assertEquals(0, events("clientDisconnected", "p1"));
        } finally {
            bus().off("bb:connection", listener);
        }
    }

    @Test
    public void aPreviewOverAPreviewTakesTheSeat() throws Throwable {
        testPlayer("p1");
        Page tile = connect("p1", true);
        Page tab = connect("p1", true);
        assertEquals(1, tile.count(REPLACED));
        assertTrue(tile.closed);
        assertSame(tab.out, client("p1").out);
        assertEquals(0, tab.count(WATCHING));
        assertTrue(client("p1").watchers().isEmpty());
    }

    @Test
    public void watchersGoWithTheirSession() throws Throwable {
        Page participant = connect("p1");
        Page preview = connect("p1", true);
        console.send("StopGame", "id", session.id);
        await("stopped", () -> preview.closed && participant.closed);
        assertEquals("they see the session's end too", "stopped", preview.last().path("player").path("ended").asText());
        assertNull(client("p1"));

        // And when the engine is rebuilt for another session.
        console.send("LaunchGame", "name", "D", "parameters", Json.newObject());
        await("running again", () -> ExperimentInstance.find.where().eq("experiment.id", experiment.id).eq("name", "D")
            .eq("status", ExperimentInstance.Status.RUNNING).findRowCount() == 1);
        session = ExperimentInstance.find.where().eq("experiment.id", experiment.id).eq("name", "D").findUnique();
        Page next = connect("p2");
        Page watching = connect("p2", true);
        final Client before = client("p2");
        console.send("ReloadEngine");
        await("rebuilt", () -> watching.closed && next.closed);
        assertTrue(before.watchers().isEmpty());
        assertNotSame(before, client("p2"));
    }

    // ---- pages at their final screen -----------------------------------------------------------

    @Test
    public void aPageJoiningAFinishedSessionGetsItsScreenAndPongsAndIsDroppedWhenSilent() throws Throwable {
        @SuppressWarnings("unchecked")
        Set<Object> endedPages = (Set<Object>) field("endedPages").get(null);
        Page page = connect("p1");
        engine().eval("def v = g.getVertex('p1'); v.finished = true; v.text = '<p>Thank you</p>'; null");
        // The session finishes, then the next one opens, as the autopilot does, restarting the engine.
        // (Finished first: a launch over a session still running stops it, and a page that comes back to
        // it then is told it was stopped, as after Stop.)
        ExperimentInstance finished = ExperimentInstance.findById(session.id);
        finished.finish();
        finished.update();
        assertEquals(ExperimentInstance.Status.FINISHED, ExperimentInstance.findById(session.id).status);
        console.send("LaunchGame", "name", "Next", "parameters", Json.newObject());
        await("the next session", () -> ExperimentInstance.find.where().eq("experiment.id", experiment.id).eq("name", "Next")
            .eq("status", ExperimentInstance.Status.RUNNING).findRowCount() == 1);
        await("their page let go", () -> page.closed);
        assertEquals(ExperimentInstance.Status.FINISHED, ExperimentInstance.findById(session.id).status);

        // Their page, still on its thank-you screen, comes back to the session that is over.
        Page back = connect("p1");
        assertEquals(back.frames.toString(), 1, back.frames.size());
        assertTrue(back.frames.get(0).path("player").path("text").asText().contains("Thank you"));
        assertFalse(back.closed);
        back.send("Ping");
        assertEquals("its heartbeat is answered", 1, back.count(PONG));
        // So is one of a page given the general notice, which never pings (from before 3.1).
        Page old = connect("p2");
        assertTrue(old.frames.get(0).path("player").path("text").asText().contains("This session has ended"));
        assertEquals(2, endedPages.size());

        // A page that falls silent is closed and dropped; one that never pinged is left alone.
        long now = System.currentTimeMillis();
        closeSilentPages(now + 29000);
        assertFalse(back.closed);
        closeSilentPages(now + 31000);
        assertTrue(back.closed);
        assertFalse(old.closed);
        assertEquals(1, endedPages.size());
        // A page that closes is dropped too.
        old.hangUp();
        assertTrue(endedPages.isEmpty());

        // A newcomer after the start of the running session: its screen, and its pings answered.
        session = ExperimentInstance.find.where().eq("experiment.id", experiment.id).eq("name", "Next").findUnique();
        session.setHasStarted(Boolean.TRUE);
        Page late = connect("p9");
        assertEquals("notRunning", late.frames.get(0).path("player").path("ended").asText());
        late.send("Ping");
        assertEquals(1, late.count(PONG));
        assertNull(client("p9"));
    }

    // ---- the seat cookie --------------------------------------------------------------------------

    @Test
    public void theSeatCookieLastsADayLikeTheSharedLinksOne() {
        Map<String, String> form = new HashMap<String, String>();
        form.put("id", "P1");
        form.put("password", "");
        Result result = route(fakeRequest(POST, "/game/" + experiment.id + "/" + session.id + "/login").withFormUrlEncodedBody(form));
        assertEquals(SEE_OTHER, status(result));
        Http.Cookie token = cookie(ParticipantAuth.cookieName(session.id.toString()), result);
        assertNotNull(token);
        assertEquals("a browser that was closed finds its seat again", Integer.valueOf(24 * 3600), token.maxAge());
        assertEquals(token.maxAge(), cookie("bb_seat_" + experiment.id, result).maxAge());
        assertTrue(token.httpOnly());
    }
}
