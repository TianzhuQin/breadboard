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
        assertEquals(6, saved.getParameters().size());
        assertEquals(Experiment.defaultClientHTML(), saved.getClientHtml());
        assertEquals(1, saved.languages.size());

        assertNull(ExperimentController.exampleExperiment(user, "no-such-example"));
        assertNull(ExperimentController.exampleExperiment(user, "../defaults"));
    }

    @Test
    public void itsStepsPlayAWholeGameByThemselves() throws Exception {
        // the scripts a release loads (an experimental-only script missing there used to break the end)
        harness = new ScriptTestHarness(false);
        // launch parameters, as a session would bind them
        harness.put("networkSize", 5);
        harness.put("minPlayers", 2);
        harness.put("waitSeconds", 20);
        harness.put("rounds", 2);
        harness.put("roundSeconds", 1);
        harness.put("prolificCode", "C0DE1234");
        for (String name : stepNames()) {
            harness.eval(FileUtils.readFileToString(new File(EXAMPLE, "Steps/" + name + ".groovy")) + "\n;null;");
        }
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
    public void itsStepsLoadWithoutASessionUsingTheDefaults() throws Exception {
        // a reload of the engine with no session open: no launch parameters are bound
        harness = new ScriptTestHarness(false);
        for (String name : stepNames()) {
            harness.eval(FileUtils.readFileToString(new File(EXAMPLE, "Steps/" + name + ".groovy")) + "\n;null;");
        }
        assertEquals(8, ((Number) harness.eval("NETWORK_SIZE")).intValue());
        assertEquals(2, ((Number) harness.eval("autopilot.room.minPlayers")).intValue());
        assertEquals(20, ((Number) harness.eval("autopilot.room.waitSeconds")).intValue());
    }

    private static List<String> stepNames() throws Exception {
        JsonNode manifest = Json.parse(FileUtils.readFileToString(new File(EXAMPLE, "example.json")));
        List<String> names = new ArrayList<String>();
        for (JsonNode n : manifest.path("steps")) names.add(n.asText());
        return names;
    }
}
