import com.fasterxml.jackson.databind.JsonNode;
import groovy.lang.GString;
import groovy.lang.GroovyShell;
import models.JsonSetup;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class JsonSetupTest {

    @Test
    public void interpolatedGroovyStringsBecomeText() {
        Object text = new GroovyShell().evaluate("def n = 2; \"${n} joined\"");
        assertEquals(true, text instanceof GString);
        JsonNode json = JsonSetup.mapper().valueToTree(Collections.singletonMap("text", text));
        assertEquals("{\"text\":\"2 joined\"}", json.toString());
    }
}
