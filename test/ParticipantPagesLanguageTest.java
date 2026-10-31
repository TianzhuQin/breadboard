import com.fasterxml.jackson.databind.JsonNode;
import models.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import play.libs.Json;
import play.mvc.Http;
import play.mvc.Result;
import security.ParticipantAuth;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * A participant in a session whose texts are in Chinese (its steps chose zho with c.setDefaultLanguage)
 * sees Breadboard's own pages in Chinese, from the sign-in page to the end: the sign-in page and its
 * errors, "you already have a seat", the participant page's loading text, the pages of a session that
 * isn't running, and the Prolific ones. Pages shown before or between sessions ("No session is open
 * right now", "already taken part") are in the language of the session they lead to, or else of the
 * experiment's most recent session; with nothing known, English. They used to be English only. A
 * session in English (or in a language Breadboard has no words in) gets them as before.
 */
public class ParticipantPagesLanguageTest extends BaseTest {

    private Experiment experiment;
    private String e;
    private Field engineSession;
    private Object previousEngineSession;
    private Field trackerField;
    private Object previousTracker;
    private EventTracker tracker;

    @Before
    public void setUp() throws Exception {
        ParticipantAuth.reset();
        Recruitment.reset();
        SessionLanguage.reset();
        experiment = createExperiment("Languages");
        e = experiment.id.toString();
        engineSession = ScriptBoard.class.getDeclaredField("engineGameListener");
        engineSession.setAccessible(true);
        previousEngineSession = engineSession.get(null);
        engineSession.set(null, null);
        trackerField = ScriptBoard.class.getDeclaredField("eventTracker");
        trackerField.setAccessible(true);
        previousTracker = trackerField.get(null);
        tracker = new EventTracker();
        trackerField.set(null, tracker);
    }

    @After
    public void restore() throws Exception {
        ParticipantAuth.reset();
        Recruitment.reset();
        SessionLanguage.reset();
        engineSession.set(null, previousEngineSession);
        trackerField.set(null, previousTracker);
    }

    /** A new session of the experiment, taking participants, as if the engine ran it, its texts in {@code language}. */
    private ExperimentInstance openSession(String name, String language) throws Exception {
        ExperimentInstance session = createInstance(name, experiment);
        session.start();
        session.save();
        GameListener running = new GameListener();
        running.experimentInstance = session;
        engineSession.set(null, running);
        tracker.enable();
        tracker.setExperimentInstance(session);
        // (as the steps' c.setDefaultLanguage records it once they have loaded: ScriptBoard.loadSteps)
        SessionLanguage.record(session.id, language);
        return session;
    }

    private static String page(String uri) {
        return contentAsString(route(fakeRequest(GET, uri)));
    }

    private Result signIn(ExperimentInstance session, String id) {
        Map<String, String> form = new HashMap<String, String>();
        form.put("id", id);
        return route(fakeRequest(POST, "/game/" + e + "/" + session.id + "/login").withFormUrlEncodedBody(form));
    }

    @Test
    public void theSignInPageAndItsErrorsAreInTheSessionsLanguage() throws Exception {
        ExperimentInstance chinese = openSession("中文", "zho");
        String login = page("/game/" + e + "/" + chinese.id + "/login");
        assertTrue(login, login.contains("<html lang=\"zh-CN\">"));
        assertTrue(login, login.contains("<title>登录</title>"));
        assertTrue(login, login.contains("<h1>加入实验</h1>"));
        assertTrue(login, login.contains("请输入你收到的 ID，加入实验。"));
        assertTrue(login, login.contains(">你的 ID</label>"));
        assertTrue(login, login.contains(">继续</button>"));
        assertFalse(login, login.contains("Join the session"));
        assertTrue(contentAsString(signIn(chinese, " ")).contains("请输入你的 ID。"));
        assertTrue(contentAsString(signIn(chinese, "_2")).contains("这个 ID 是系统保留的，不能使用。请使用你收到的 ID。"));
        assertEquals(SEE_OTHER, status(signIn(chinese, "p1")));
        assertTrue(contentAsString(signIn(chinese, "p1")).contains("这个 ID 已经有人在用了。"));

        // The participant page's own loading text, until the page has loaded.
        String token = ParticipantAuth.claim(chinese.id.toString(), "p2");
        String client = contentAsString(route(fakeRequest(GET, "/game/" + e + "/" + chinese.id + "/p2/connected")
            .withHeader(ParticipantAuth.HEADER, token)));
        assertTrue(client, client.contains("<html lang=\"zh-CN\">") && client.contains("正在加载…"));
        // and its state says the language, which Breadboard's own words on the page follow
        Result state = route(fakeRequest(GET, "/game/" + e + "/" + chinese.id + "/p2/state").withHeader(ParticipantAuth.HEADER, token));
        assertEquals(OK, status(state));
        assertEquals("zho", Json.parse(contentAsString(state)).get("language").asText());

        // A session in English: as before.
        ExperimentInstance english = openSession("English", "eng");
        String englishLogin = page("/game/" + e + "/" + english.id + "/login");
        assertTrue(englishLogin, englishLogin.contains("<html lang=\"en\">"));
        assertTrue(englishLogin, englishLogin.contains("<h1>Join the session</h1>"));
        assertTrue(englishLogin, englishLogin.contains("Enter the ID you were given to take your seat."));
        assertTrue(contentAsString(signIn(english, "")).contains("Please enter your ID."));
        String englishToken = ParticipantAuth.claim(english.id.toString(), "p3");
        Result englishState = route(fakeRequest(GET, "/game/" + e + "/" + english.id + "/p3/state").withHeader(ParticipantAuth.HEADER, englishToken));
        assertEquals("eng", Json.parse(contentAsString(englishState)).get("language").asText());
        assertTrue(contentAsString(route(fakeRequest(GET, "/game/" + e + "/" + english.id + "/p3/connected")
            .withHeader(ParticipantAuth.HEADER, englishToken))).contains("Loading…"));
    }

    @Test
    public void aSessionInALanguageBreadboardHasNoWordsInOrNoneGetsEnglish() throws Exception {
        ExperimentInstance spanish = openSession("Español", "spa");
        assertTrue(page("/game/" + e + "/" + spanish.id + "/login").contains("<h1>Join the session</h1>"));
        ExperimentInstance none = openSession("None", null);
        assertTrue(page("/game/" + e + "/" + none.id + "/login").contains("<h1>Join the session</h1>"));
        String token = ParticipantAuth.claim(none.id.toString(), "p1");
        JsonNode state = Json.parse(contentAsString(route(fakeRequest(GET, "/game/" + e + "/" + none.id + "/p1/state")
            .withHeader(ParticipantAuth.HEADER, token))));
        assertTrue(state.has("language") && state.get("language").isNull());
    }

    @Test
    public void aBrowserWithASeatIsAskedInTheSessionsLanguage() throws Exception {
        ExperimentInstance chinese = openSession("中文", "zho");
        Result entered = signIn(chinese, "王芳");
        Http.Cookie seat = cookie("bb_seat_" + e, entered);
        String asked = contentAsString(route(fakeRequest(GET, "/play/" + e + "/login").withCookies(seat)));
        assertTrue(asked, asked.contains("<h1>你已经加入了这场实验</h1>"));
        assertTrue(asked, asked.contains("这个浏览器已经以 <strong>王芳</strong> 的身份加入了这场实验。"));
        assertTrue(asked, asked.contains(">以 王芳 的身份继续</a>"));
        assertTrue(asked, asked.contains(">以其他身份加入</a>"));
    }

    @Test
    public void theProlificPagesAreInTheLanguageOfTheSessionTheyLeadTo() throws Exception {
        ExperimentInstance chinese = openSession("中文", "zho");
        String missing = page("/game/" + e + "/" + chinese.id + "/prolific");
        assertTrue(missing, missing.contains("<h1>缺少 Prolific ID</h1>") && missing.contains("这个链接要从 Prolific 打开。"));

        // Someone who took part comes back: their code again, in Chinese.
        Result entered = route(fakeRequest(GET, "/game/" + e + "/" + chinese.id + "/prolific?PROLIFIC_PID=PIDZH&STUDY_ID=S&SESSION_ID=X"));
        assertEquals(SEE_OTHER, status(entered));
        String seat = ParticipantAuth.prolificClientId(chinese.id.toString(), "PIDZH");
        java.util.LinkedHashMap<Object, Object> done = new java.util.LinkedHashMap<Object, Object>();
        done.put("player", seat);
        done.put("completionCode", "C7K2M9QX");
        tracker.track("prolificComplete", done);
        String back = page("/game/" + e + "/" + chinese.id + "/prolific?PROLIFIC_PID=PIDZH&STUDY_ID=S&SESSION_ID=X");
        assertTrue(back, back.contains("<h1>你已经参加过这项研究了</h1>"));
        assertTrue(back, back.contains("<span class=\"bb-code__label\">你的完成码</span>"));
        assertTrue(back, back.contains("<code>C7K2M9QX</code>"));
        assertTrue(back, back.contains(">回到 Prolific</a>"));

        // The session is over: the study's link, with no session open, in the language of the
        // experiment's most recent session.
        chinese.setHasStarted(Boolean.TRUE);
        chinese.finish();
        chinese.save();
        engineSession.set(null, null);
        String noSession = page("/play/" + e + "/login");
        assertTrue(noSession, noSession.contains("<h1>现在没有开放的实验</h1>"));
        assertTrue(noSession, noSession.contains("目前没有正在接收参与者的实验。请过几分钟再试，或者联系研究者。"));
        String noSessionProlific = page("/play/" + e + "/prolific?PROLIFIC_PID=PIDNEW&STUDY_ID=S&SESSION_ID=Y");
        assertTrue(noSessionProlific, noSessionProlific.contains("请在 Prolific 上退回这项研究。"));
        String takenPart = page("/play/" + e + "/prolific?PROLIFIC_PID=PIDZH&STUDY_ID=S&SESSION_ID=X");
        assertTrue(takenPart, takenPart.contains("<h1>你已经参加过这项研究了</h1>"));
        // and the finished session's own pages
        String over = page("/game/" + e + "/" + chinese.id + "/login");
        assertTrue(over, over.contains("<h1>这场实验现在没有在进行</h1>"));
        String notRunning = page("/game/" + e + "/" + chinese.id + "/prolific?PROLIFIC_PID=PIDNEW&STUDY_ID=S&SESSION_ID=Y");
        assertTrue(notRunning, notRunning.contains("<h1>这项研究现在没有在进行</h1>"));
    }

    @Test
    public void pagesBetweenSessionsFollowTheMostRecentSessionThatChoseALanguage() throws Exception {
        ExperimentInstance chinese = openSession("中文", "zho");
        chinese.finish();
        chinese.save();
        // a session whose steps never loaded records nothing: it doesn't count
        ExperimentInstance broken = createInstance("Broken", experiment);
        broken.finish();
        broken.save();
        engineSession.set(null, null);
        assertEquals("zho", SessionLanguage.ofExperiment(experiment.id));
        assertTrue(page("/play/" + e + "/login").contains("<h1>现在没有开放的实验</h1>"));

        // a later one in English: English
        ExperimentInstance english = openSession("English", "eng");
        english.finish();
        english.save();
        engineSession.set(null, null);
        String noSession = page("/play/" + e + "/login");
        assertTrue(noSession, noSession.contains("<h1>No session is open right now</h1>"));
        assertTrue(noSession, noSession.contains("<html lang=\"en\">"));

        // a later one whose steps chose no language: English too
        ExperimentInstance none = openSession("None", null);
        none.finish();
        none.save();
        engineSession.set(null, null);
        assertNull(SessionLanguage.ofExperiment(experiment.id));

        // an experiment with no session at all: English
        Experiment fresh = createExperiment("Fresh");
        assertTrue(page("/play/" + fresh.id + "/login").contains("No session is open right now"));
    }

    @Test
    public void theHoldingPageIsInTheLanguageOfTheMostRecentSession() throws Exception {
        ExperimentInstance chinese = openSession("中文", "zho");
        chinese.finish();
        chinese.save();
        engineSession.set(null, null);
        play.test.FakeRequest request = fakeRequest(GET, "/play/" + e + "/login");
        Http.Context.current.set(play.core.j.JavaHelpers$.MODULE$.createJavaContext(request.getWrappedRequest()));
        try {
            java.lang.reflect.Method holding = controllers.ClientLogin.class.getDeclaredMethod("holding", Long.class, boolean.class);
            holding.setAccessible(true);
            String waiting = contentAsString((Result) holding.invoke(null, experiment.id, true));
            assertTrue(waiting, waiting.contains("<html lang=\"zh-CN\">"));
            // (the next session "opens", 开放, as the guide says it, not "starts")
            assertTrue(waiting, waiting.contains("<h1>下一场实验马上开放</h1>") && waiting.contains("下一场开放后，这个页面会自动带你进入")
                || waiting.contains("<h1>有一场实验正在进行</h1>") && waiting.contains("这一场结束后，下一场就会开放，这个页面会自动带你进入"));
            assertTrue(waiting, waiting.contains("如果等不及，请在 Prolific 上退回这项研究。"));
            assertTrue(waiting, waiting.contains("每隔几秒检查一次…"));
        } finally {
            Http.Context.current.remove();
        }
    }

    // The heading of the page for a link that leads nowhere, in English (as the page escapes it).
    private static final String ENGLISH_INVALID = "<h1>This link isn&#x27;t valid</h1>";

    /** What Global's not-found handler answers a request for {@code path} with: a link that leads nowhere. */
    private static Result notFoundPage(String path) throws Exception {
        Http.RequestHeader request = play.core.j.JavaHelpers$.MODULE$.createJavaRequest(fakeRequest(GET, path).getWrappedRequest());
        return new Global().onHandlerNotFound(request).get(10000);
    }

    @Test
    public void aLinkCutOffOrMistypedIsInTheLanguageOfTheSessionOrStudyItStillNames() throws Exception {
        ExperimentInstance chinese = openSession("中文", "zho");
        String s = chinese.id.toString();
        // A study's link, or a session's, cut off (e.g. by a chat app) or mistyped: the session's language,
        // or the experiment's for a session it doesn't have. It was English whatever the study's language.
        for (String path : new String[]{"/play/" + e + "/logi", "/play/" + e, "/play/" + e + "/login/x",
            "/game/" + e + "/" + s + "/logi", "/game/" + e + "/" + s + "/p1/connecte", "/game/" + e + "/logi",
            "/game/" + e + "/999999/login/x"}) {
            Result cut = notFoundPage(path);
            assertEquals(path, NOT_FOUND, status(cut));
            String page = contentAsString(cut);
            assertTrue(path + ": " + page, page.contains("<html lang=\"zh-CN\">"));
            assertTrue(path + ": " + page, page.contains("<h1>" + SystemTexts.text("zho", "link.invalidTitle") + "</h1>"));
            assertFalse(path + ": " + page, page.contains(ENGLISH_INVALID));
        }
        // A session's address without /login still goes to its sign-in page.
        Result bare = notFoundPage("/game/" + e + "/" + s + "/");
        assertEquals(SEE_OTHER, status(bare));
        assertEquals("/game/" + e + "/" + s + "/login", header("Location", bare));

        // A session in English, of an experiment whose most recent session is in Chinese: the session's own.
        ExperimentInstance english = openSession("English", "eng");
        openSession("中文 2", "zho");
        String englishPage = contentAsString(notFoundPage("/game/" + e + "/" + english.id + "/logi"));
        assertTrue(englishPage, englishPage.contains("<html lang=\"en\">") && englishPage.contains(ENGLISH_INVALID));
        assertTrue(contentAsString(notFoundPage("/play/" + e + "/logi")).contains("<html lang=\"zh-CN\">"));

        // Nothing known: English, as before.
        Experiment fresh = createExperiment("Fresh");
        for (String path : new String[]{"/play/" + fresh.id + "/logi", "/game/" + fresh.id + "/1/logi", "/play/", "/game/x/1/login/x",
            "/play/999999/logi"}) {
            String page = contentAsString(notFoundPage(path));
            assertTrue(path + ": " + page, page.contains("<html lang=\"en\">") && page.contains(ENGLISH_INVALID));
        }
    }

    @Test
    public void theNoticeOfASessionCutShortByARestartIsInItsLanguage() throws Exception {
        ExperimentInstance chinese = openSession("中文", "zho");
        chinese.stop();
        chinese.save();
        tracker.track("sessionInterrupted", new java.util.LinkedHashMap<Object, Object>());
        engineSession.set(null, null);
        String page = page("/game/" + e + "/" + chinese.id + "/login");
        assertTrue(page, page.contains("<h1>本场实验已结束</h1>"));
        assertTrue(page, page.contains("研究的服务器停止或重启了，所以本场实验结束了。"));
        // and the page left open is told the same as it reconnects
        assertTrue(page, page.contains("<html lang=\"zh-CN\">"));
    }
}
