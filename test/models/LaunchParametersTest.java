package models;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * LaunchGame checks every parameter before it resets the engine. A null value (a cleared number
 * field, NaN serialised by the browser) used to throw after the running session had already been
 * ended, and no reply followed.
 */
public class LaunchParametersTest {

    private static Parameter param(String name, String type, String defaultVal) {
        Parameter p = new Parameter();
        p.name = name;
        p.type = type;
        p.defaultVal = defaultVal;
        return p;
    }

    private static Map<String, Object> sent(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    private final List<Parameter> defined = Arrays.asList(
        param("rounds", "Integer", "5"),
        param("rate", "Decimal", "0.5"),
        param("chat", "Boolean", "true"),
        param("greeting", "Text", "hi"));

    @Test
    public void sentValuesAreUsedAsText() {
        LinkedHashMap<String, String> v = ScriptBoard.launchParameters(
            sent("rounds", 7, "rate", 0.25, "chat", false, "greeting", "hello"), defined);
        assertEquals("7", v.get("rounds"));
        assertEquals("0.25", v.get("rate"));
        assertEquals("false", v.get("chat"));
        assertEquals("hello", v.get("greeting"));
    }

    @Test
    public void nullOrMissingValuesTakeTheDefault() {
        LinkedHashMap<String, String> v = ScriptBoard.launchParameters(sent("rounds", null, "rate", ""), defined);
        assertEquals("5", v.get("rounds"));
        assertEquals("0.5", v.get("rate"));
        assertEquals("true", v.get("chat"));
        assertEquals("hi", v.get("greeting"));
        assertEquals(4, v.size());
    }

    @Test
    public void noParametersSentMeansAllDefaults() {
        LinkedHashMap<String, String> v = ScriptBoard.launchParameters(null, defined);
        assertEquals("5", v.get("rounds"));
        assertEquals("hi", v.get("greeting"));
    }

    @Test
    public void wholeNumbersSentAsDecimalsCount() {
        assertEquals("6", ScriptBoard.launchParameters(sent("rounds", 6.0), defined).get("rounds"));
        assertEquals("6", ScriptBoard.launchParameters(sent("rounds", " 6 "), defined).get("rounds"));
    }

    @Test
    public void anEmptyTextIsAValue() {
        assertEquals("", ScriptBoard.launchParameters(sent("greeting", ""), defined).get("greeting"));
    }

    @Test
    public void aMissingValueWithoutDefaultIsRefusedByName() {
        List<Parameter> noDefaults = Arrays.asList(param("rounds", "Integer", null));
        try {
            ScriptBoard.launchParameters(sent("rounds", null), noDefaults);
            fail("a missing number was accepted");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("rounds"));
        }
    }

    @Test
    public void booleansAndTextsWithoutDefaultGetFalseAndEmpty() {
        // A Boolean parameter saved without touching its default has defaultVal null.
        List<Parameter> noDefaults = Arrays.asList(param("chat", "Boolean", null), param("note", "Text", null));
        LinkedHashMap<String, String> v = ScriptBoard.launchParameters(Collections.<String, Object>emptyMap(), noDefaults);
        assertEquals("false", v.get("chat"));
        assertEquals("", v.get("note"));
    }

    @Test
    public void notANumberIsRefused() {
        Object[][] cases = {{"rounds", "abc"}, {"rounds", 2.5}, {"rate", "NaN"}, {"rate", "x.y"}};
        for (Object[] c : cases) {
            String name = (String) c[0];
            try {
                ScriptBoard.launchParameters(sent(name, c[1]), defined);
                fail(c[1] + " was accepted for " + name);
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains(name));
            }
        }
    }

    @Test
    public void valuesForUndefinedNamesAreKeptAsBefore() {
        LinkedHashMap<String, String> v = ScriptBoard.launchParameters(sent("extra", 3, "nothing", null), defined);
        assertEquals("3", v.get("extra"));
        assertFalse(v.containsKey("nothing"));
    }
}
