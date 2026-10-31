import com.fasterxml.jackson.databind.JsonNode;
import controllers.ExperimentController;
import models.*;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Test;
import play.libs.Json;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The ready-to-run example "New experiment" offers (conf/examples/network-game): it is created with
 * its steps, parameters and the current participant page, and its steps play a whole game in the
 * real script engine (ScriptTestHarness) without anyone at the console.
 */
public class ExampleExperimentTest extends BaseTest {

    private static final File EXAMPLE = new File("conf/examples/network-game");
    private ScriptTestHarness harness;

    /** As the engine's {@code c} (ContentFetcher): the example's texts, read from its Content folder. */
    public static class ExampleTexts {
        /** The language the steps chose (setDefaultLanguage). */
        public String language;
        /** Texts as a researcher rewrote or translated them on the Content page, in place of the folder's. */
        public final java.util.Map<String, String> edited = new java.util.HashMap<String, String>();

        public void setDefaultLanguage(String language) {
            this.language = language;
        }

        /** As ContentFetcher's: Breadboard's own words in the engine follow it (util.groovy's bbText). */
        public String languageCode() {
            return language;
        }

        public String get(String name, Object... parameters) throws java.io.IOException {
            String html = edited.containsKey(name) ? edited.get(name)
                : FileUtils.readFileToString(new File(EXAMPLE, "Content/" + name + ".html")).trim();
            for (int i = 0; i < parameters.length; i++) {
                html = html.replace("{" + i + "}", String.valueOf(parameters[i]));
            }
            return html;
        }
    }

    @After
    public void closeEngine() throws Exception {
        if (harness != null) {
            harness.eval("autopilot.stop(); g.setVisibility('ego'); null");
            harness.close();
        }
        ClientView.reset();
    }

    @Test
    public void theNetworkGameIsCreatedWithItsStepsParametersAndThePage() throws Exception {
        Language english = createLanguage("eng", "English");
        User user = createUser("example@test.org", "Example", "password1234", english);

        Experiment experiment = ExperimentController.exampleExperiment(user, "network-game");
        experiment.name = "Network game";
        experiment.save();

        Experiment saved = Experiment.findById(experiment.id);
        List<String> names = new ArrayList<String>();
        for (Step s : saved.getSteps()) names.add(s.name);
        // (the console lists steps in its own order)
        List<String> expected = stepNames();
        java.util.Collections.sort(expected);
        java.util.Collections.sort(names);
        assertEquals(expected, names);
        assertEquals(7, saved.getParameters().size());
        // participants read the account's language, here English
        assertEquals("eng", parameter(saved, "language").defaultVal);
        // what participants read is on the Content page, in English
        List<String> texts = new ArrayList<String>();
        for (Content c : saved.getContent()) texts.add(c.name);
        assertTrue(texts.toString(), texts.containsAll(java.util.Arrays.asList("RoundPrompt", "Finished", "RoundTimer")));
        assertEquals(17, texts.size());
        for (Content c : saved.getContent()) {
            assertEquals(c.name, 1, c.translations.size());
            assertEquals("eng", c.translations.get(0).language.getCode());
            assertFalse(c.name, c.translations.get(0).getHtml().trim().isEmpty());
        }
        // the colours' names too, to translate like the rest
        assertEquals("Blue", saved.contentFetcher.get("ColorBlue"));
        assertEquals("Orange", saved.contentFetcher.get("ColorOrange"));
        assertEquals(Experiment.defaultClientHTML(), saved.getClientHtml());
        assertEquals(1, saved.languages.size());

        assertNull(ExperimentController.exampleExperiment(user, "no-such-example"));
        assertNull(ExperimentController.exampleExperiment(user, "../defaults"));
    }

    /**
     * An account whose default language isn't English gets the example's English texts as English (they
     * were filed under the account's language), with its language next to English for translations,
     * and participants read that language: each text once it is translated, English until then.
     */
    @Test
    public void anAccountInAnotherLanguageGetsTheEnglishTextsAsEnglishWithItsLanguageNextToThem() throws Exception {
        createLanguage("eng", "English");
        Language chinese = createLanguage("zho", "Chinese");
        User user = createUser("zh@test.org", "Zh", "password1234", chinese);

        Experiment experiment = ExperimentController.exampleExperiment(user, "network-game");
        experiment.name = "网络小游戏";
        experiment.save();

        Experiment saved = Experiment.findById(experiment.id);
        assertEquals(17, saved.getContent().size());
        for (Content c : saved.getContent()) {
            assertEquals(c.name, 1, c.translations.size());
            assertEquals(c.name, "eng", c.translations.get(0).language.getCode());
        }
        List<String> codes = new ArrayList<String>();
        for (Language l : saved.languages) codes.add(l.getCode());
        java.util.Collections.sort(codes);
        assertEquals(java.util.Arrays.asList("eng", "zho"), codes);
        assertEquals("zho", parameter(saved, "language").defaultVal);

        // The researcher translates one text; the steps choose the language parameter's language.
        Content prompt = null;
        for (Content c : saved.getContent()) if (c.name.equals("RoundPrompt")) prompt = c;
        Translation chineseText = new Translation();
        chineseText.setLanguage(chinese);
        chineseText.setHtml("<p>选一个颜色。</p>");
        chineseText.setContent(prompt);
        chineseText.save();
        ContentFetcher c = Experiment.findById(experiment.id).contentFetcher;
        c.setDefaultLanguage("zho");
        assertEquals("<p>选一个颜色。</p>", c.get("RoundPrompt"));
        assertEquals("<h2>Round 1 of 3</h2>", c.get("RoundHeading", 1, 3));
    }

    @Test
    public void itsStepsPlayAWholeGameByThemselves() throws Exception {
        // the scripts a release loads (an experimental-only script missing there used to break the end)
        harness = new ScriptTestHarness(false);
        ExampleTexts texts = new ExampleTexts();
        harness.put("c", texts);
        // launch parameters, as a session would bind them
        harness.put("networkSize", 5);
        harness.put("minPlayers", 2);
        harness.put("waitSeconds", 20);
        harness.put("rounds", 2);
        harness.put("roundSeconds", 1);
        harness.put("prolificCode", "C0DE1234");
        harness.put("language", "zho");
        for (String name : stepNames()) {
            harness.eval(FileUtils.readFileToString(new File(EXAMPLE, "Steps/" + name + ".groovy")) + "\n;null;");
        }
        assertEquals("the steps choose the language parameter's language", "zho", texts.language);
        harness.runAsync("{ done ->\n"
            + "  ['p1', 'p2'].each { g.addPlayer(it) }\n"
            + "  recruitment.registerProlific(g.getVertex('p2'))\n"
            + "  initStep.start()\n"
            + "  def poll\n"
            + "  poll = {\n"
            + "    if (!gameListener.finished()) { timers.newTimer().runAfter(100) { poll() }; return }\n"
            + "    done {\n"
            + "      assert g.V.count() == 5\n"
            + "      ['p1', 'p2'].each { id ->\n"
            + "        def p = g.getVertex(id)\n"
            + "        assert p.text.contains('Thank you')\n"
            + "        assert p.text.contains('out of 2')\n"
            + "        assert p.score >= 0 && p.score <= 2\n"
            + "        assert p.private.edgeEdits.enabled == false\n"
            + "        assert p.private.finished == true\n"
            + "        assert p.both.count() >= 1\n"
            + "      }\n"
            + "      assert g.getVertex('p2')._system.recruitment.completionCode == 'C0DE1234'\n"
            + "      assert !g.getVertex('p1')._system.recruitment?.completionCode\n"
            + "    }\n"
            + "  }\n"
            + "  poll()\n"
            + "}", 20000);
    }

    @Test
    public void withItsLanguageSetToChineseItsWaitingRoomSpeaksChinese() throws Exception {
        // the example's language launch parameter: Breadboard's own words in the engine follow it too
        harness = new ScriptTestHarness(false);
        ExampleTexts texts = new ExampleTexts();
        harness.put("c", texts);
        harness.put("minPlayers", 3);
        harness.put("language", "zho");
        for (String name : stepNames()) {
            harness.eval(FileUtils.readFileToString(new File(EXAMPLE, "Steps/" + name + ".groovy")) + "\n;null;");
        }
        harness.runSync("{ ->\n"
            + "  def v = g.addPlayer('zh-1')\n"
            + "  v._system.connected = true\n"
            + "  autopilot.onConnection('zh-1', true)\n"
            + "  assert v.text == '<h3>正在等待其他参与者</h3><p>目前已有 1 人加入。人数达到 3–8 人后，实验就会开始。</p><p>请保持这个页面打开。</p>'\n"
            + "  assert v._system.autopilot.state == 'waiting'\n"
            + "}");
    }

    @Test
    public void itsStepsLoadWithoutASessionUsingTheDefaults() throws Exception {
        // a reload of the engine with no session open: no launch parameters are bound
        harness = new ScriptTestHarness(false);
        harness.put("c", new ExampleTexts());
        for (String name : stepNames()) {
            harness.eval(FileUtils.readFileToString(new File(EXAMPLE, "Steps/" + name + ".groovy")) + "\n;null;");
        }
        assertEquals(8, ((Number) harness.eval("NETWORK_SIZE")).intValue());
        assertEquals(2, ((Number) harness.eval("autopilot.room.minPlayers")).intValue());
        assertEquals(20, ((Number) harness.eval("autopilot.room.waitSeconds")).intValue());
        assertEquals("eng", ((ExampleTexts) harness.get("c")).language);
    }

    /** The score is shown from the second round on: the first one said "Your score: 0 of 0". */
    @Test
    public void theFirstRoundShowsNoScoreYet() throws Exception {
        harness = loadedGame(600);
        harness.runSync("{ ->\n"
            + "  ['p1', 'p2'].each { g.addPlayer(it) }\n"
            + "  initStep.start()\n"
            + "  def p1 = g.getVertex('p1')\n"
            + "  assert round == 1\n"
            + "  assert p1.text.contains('Round 1 of 2') && !p1.text.contains('Your score')\n"
            + "  round = 2\n"
            + "  assert roundText(p1, 'body').contains('Your score: 0 of 1')\n"
            + "}");
    }

    /**
     * A participant's cut is recorded as a TieCut event (who, whom, which round), not as Breadboard's
     * Disconnected, easy to mistake for someone's connection dropping; their page still says the tie
     * was cut.
     */
    @Test
    public void aCutTieIsRecordedAsTieCut() throws Exception {
        harness = loadedGame(600);
        harness.runSync("{ ->\n"
            + "  def tracked = []\n"
            + "  def recorder = [track: { name, data -> tracked << [name: name, data: data] }]\n"
            + "  eventTracker = recorder\n"
            + "  g.eventTracker = recorder\n"
            + "  edgeEdits.eventTracker = recorder\n"
            + "  ['p1', 'p2'].each { g.addPlayer(it) }\n"
            + "  initStep.start()\n"
            + "  def p1 = g.getVertex('p1')\n"
            + "  def other = p1.both.toList()[0]\n"
            + "  def sent = []\n"
            + "  def listener = { id, name, args -> if (name == 'bb:edge-edit-result') sent << args[0] }\n"
            + "  events.on('__send-event', listener)\n"
            + "  try {\n"
            + "    edgeEdits.handle(p1, [op: 'remove', a: g.clientIdOf(p1), b: g.clientIdOf(other)])\n"
            + "  } finally {\n"
            + "    events.off('__send-event', listener)\n"
            + "  }\n"
            + "  assert !g.hasEdge(p1, other)\n"
            + "  assert sent.size() == 1 && sent[0].ok == true && sent[0].applied == true\n"
            + "  def names = tracked*.name\n"
            + "  assert names.contains('TieCut') && !names.contains('Disconnected') : names\n"
            + "  def cut = tracked.find { it.name == 'TieCut' }.data\n"
            + "  assert cut.player == 'p1' && cut.other == other.id && cut.round == 1\n"
            + "  assert p1.private.edgeEdits.remainingRemoves == 0\n"
            + "}");
    }

    /**
     * The colours' names are texts on the Content page (ColorBlue, ColorOrange): translated, they are what
     * the buttons and the texts say, while scoring, the network's colours and the data keep Blue and
     * Orange. The names used to be in the steps, and translating them there, as the Chinese guide hinted,
     * scored no one and turned the network grey, without an error: every pick counted as Orange's.
     */
    @Test
    public void itsColoursTranslatedOnTheContentPageScoreAndColourAsInEnglish() throws Exception {
        ExampleTexts texts = new ExampleTexts();
        // as the Content page's Visual editor saves a text (in a paragraph), and as its HTML editor does
        texts.edited.put("ColorBlue", "<p>蓝色</p>");
        texts.edited.put("ColorOrange", "橙色");
        texts.edited.put("Picked", "<p>你选了 <strong>{0}</strong>。正在等待其他人…</p>");
        texts.edited.put("ResultPoint", "上一轮你选了 {0}，大多数邻居也选了 {1}：<strong>得 1 分</strong>。");
        texts.edited.put("ResultNoPoint", "上一轮你选了 {0}，大多数邻居选了 {1}，不得分。");
        texts.edited.put("ResultTie", "上一轮你选了 {0}，邻居们选两种颜色的人一样多，不得分。");
        // three people, each tied to the other two, and no computer players
        harness = loadedGame(600, 3, 3, "zho", texts);
        harness.runSync("{ ->\n"
            + "  def tracked = []\n"
            + "  eventTracker = [track: { name, data -> tracked << [name: name, data: data] }]\n"
            + "  def people = ['p1', 'p2', 'p3'].collect { g.addPlayer(it) }\n"
            + "  initStep.start()\n"
            + "  assert g.V.count() == 3\n"
            + "  def choose = { p, String name -> a.choose(p.choices.find { it.name == name }.uid) }\n"
            // the buttons: named as translated, in the colours of the code
            + "  people.each { p ->\n"
            + "    assert p.choices*.name == ['蓝色', '橙色']\n"
            + "    assert p.choices*.color == [COLORS.Blue, COLORS.Orange]\n"
            + "  }\n"
            // round 1: everyone picks 蓝色, and scores
            + "  choose(people[0], '蓝色')\n"
            + "  assert people[0].text.contains('<p>你选了 <strong>蓝色</strong>。正在等待其他人…</p>')\n"
            + "  choose(people[1], '蓝色')\n"
            + "  choose(people[2], '蓝色')\n"
            + "  assert round == 2\n"
            + "  people.each { p ->\n"
            + "    assert p.score == 1\n"
            + "    assert p.color == COLORS.Blue\n"
            + "    assert p.private.lastResult == '上一轮你选了 蓝色，大多数邻居也选了 蓝色：<strong>得 1 分</strong>。'\n"
            + "    assert p.text.contains(p.private.lastResult)\n"
            + "  }\n"
            // round 2: p3 picks 橙色; p1 and p2 see their neighbors split
            + "  choose(people[0], '蓝色')\n"
            + "  choose(people[1], '蓝色')\n"
            + "  choose(people[2], '橙色')\n"
            + "  assert round == 3\n"
            + "  assert people*.score == [1, 1, 1]\n"
            + "  assert people*.color == [COLORS.Blue, COLORS.Blue, COLORS.Orange]\n"
            + "  assert people[2].private.lastResult == '上一轮你选了 橙色，大多数邻居选了 蓝色，不得分。'\n"
            + "  assert people[0].private.lastResult == '上一轮你选了 蓝色，邻居们选两种颜色的人一样多，不得分。'\n"
            // the data has Blue and Orange
            + "  assert tracked.findAll { it.name == 'ColourPick' }*.data*.colour == ['Blue', 'Blue', 'Blue', 'Blue', 'Blue', 'Orange']\n"
            + "  def results = tracked.findAll { it.name == 'RoundResult' }*.data\n"
            + "  assert results.findAll { it.round == 1 }.every { it.pick == 'Blue' && it.neighborsMajority == 'Blue' && it.score == 1 }\n"
            + "  assert results.find { it.round == 2 && it.player == 'p3' } == [player: 'p3', round: 2, pick: 'Orange', neighborsMajority: 'Blue', score: 1]\n"
            + "  assert results.find { it.round == 2 && it.player == 'p1' }.neighborsMajority == 'tie'\n"
            // the computer picks for someone who left: they read its name too
            + "  people[0].ai = 1\n"
            + "  pick(people[0], 'Orange')\n"
            + "  assert people[0].text.contains('the computer picked <strong>橙色</strong> for you')\n"
            + "}");
    }

    /** A colour whose text is gone or empty (a step copied into an experiment without it) is named as in the code. */
    @Test
    public void aColourWithoutItsTextIsNamedAsInTheCode() throws Exception {
        ExampleTexts texts = new ExampleTexts();
        // (ContentFetcher gives "" for a text the experiment doesn't have)
        texts.edited.put("ColorBlue", "");
        texts.edited.put("ColorOrange", "<p>&nbsp;</p>");
        harness = loadedGame(600, 3, 2, "eng", texts);
        harness.runSync("{ ->\n"
            + "  ['p1', 'p2', 'p3'].each { g.addPlayer(it) }\n"
            + "  initStep.start()\n"
            + "  assert g.getVertex('p1').choices*.name == ['Blue', 'Orange']\n"
            + "  assert colorName('Blue') == 'Blue'\n"
            + "}");
    }

    /** The example's steps in the engine with a session's launch parameters (rounds of {@code roundSeconds}). */
    private ScriptTestHarness loadedGame(int roundSeconds) throws Exception {
        return loadedGame(roundSeconds, 5, 2, "eng", new ExampleTexts());
    }

    /** loadedGame, on a network of {@code networkSize}, for {@code rounds}, in {@code language}, with these texts. */
    private ScriptTestHarness loadedGame(int roundSeconds, int networkSize, int rounds, String language, ExampleTexts texts)
            throws Exception {
        ScriptTestHarness h = new ScriptTestHarness(false);
        h.put("c", texts);
        h.put("networkSize", networkSize);
        h.put("minPlayers", 2);
        h.put("waitSeconds", 20);
        h.put("rounds", rounds);
        h.put("roundSeconds", roundSeconds);
        h.put("prolificCode", "");
        h.put("language", language);
        for (String name : stepNames()) {
            h.eval(FileUtils.readFileToString(new File(EXAMPLE, "Steps/" + name + ".groovy")) + "\n;null;");
        }
        return h;
    }

    private static Parameter parameter(Experiment experiment, String name) {
        for (Parameter p : experiment.getParameters()) {
            if (p.name.equals(name)) return p;
        }
        fail("no parameter " + name);
        return null;
    }

    private static List<String> stepNames() throws Exception {
        JsonNode manifest = Json.parse(FileUtils.readFileToString(new File(EXAMPLE, "example.json")));
        List<String> names = new ArrayList<String>();
        for (JsonNode n : manifest.path("steps")) names.add(n.asText());
        return names;
    }
}
