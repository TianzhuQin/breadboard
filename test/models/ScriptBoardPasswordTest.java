package models;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The participant password is the experiment's {@code password} binding. Unset or blank means
 * there is none: the login page asks for no password and everyone is let in. Otherwise only that
 * exact password is accepted.
 *
 * <p>ScriptBoard keeps its engine in a private static field, set when the actor builds it, so these
 * tests put a bare engine there by reflection and restore the previous one afterwards. No
 * {@code FakeApplication} is needed.
 */
public class ScriptBoardPasswordTest {

    private Field engineField;
    private Object previousEngine;
    private ScriptEngine engine;

    @Before
    public void useBareEngine() throws Exception {
        engineField = ScriptBoard.class.getDeclaredField("engine");
        engineField.setAccessible(true);
        previousEngine = engineField.get(null);
        engine = new ScriptEngineManager().getEngineByName("gremlin-groovy");
        engineField.set(null, engine);
    }

    @After
    public void restoreEngine() throws Exception {
        engineField.set(null, previousEngine);
    }

    @Test
    public void withNoEngineThereIsNoPassword() throws Exception {
        engineField.set(null, null);
        assertFalse(ScriptBoard.hasPassword());
        assertTrue(ScriptBoard.checkPassword(null));
        assertTrue(ScriptBoard.checkPassword("anything"));
    }

    @Test
    public void anUnsetPasswordLetsEveryoneIn() {
        assertFalse(ScriptBoard.hasPassword());
        assertTrue(ScriptBoard.checkPassword(null));
        assertTrue(ScriptBoard.checkPassword(""));
        assertTrue(ScriptBoard.checkPassword("anything"));
    }

    @Test
    public void aBlankPasswordCountsAsNone() {
        engine.put("password", "");
        assertFalse(ScriptBoard.hasPassword());
        assertTrue(ScriptBoard.checkPassword(null));
        assertTrue(ScriptBoard.checkPassword("anything"));

        engine.put("password", "   ");
        assertFalse(ScriptBoard.hasPassword());
        assertTrue(ScriptBoard.checkPassword(null));
    }

    @Test
    public void aSetPasswordAcceptsOnlyThatPassword() {
        engine.put("password", "secret");
        assertTrue(ScriptBoard.hasPassword());
        assertFalse("a missing password was accepted", ScriptBoard.checkPassword(null));
        assertFalse(ScriptBoard.checkPassword(""));
        assertFalse(ScriptBoard.checkPassword("wrong"));
        assertFalse(ScriptBoard.checkPassword("Secret"));
        assertTrue(ScriptBoard.checkPassword("secret"));
    }

    @Test
    public void aPasswordThatIsNotAStringIsComparedAsText() {
        engine.put("password", 1234);
        assertTrue(ScriptBoard.hasPassword());
        assertFalse(ScriptBoard.checkPassword("123"));
        assertTrue(ScriptBoard.checkPassword("1234"));
    }
}
