import com.fasterxml.jackson.databind.JsonNode;
import controllers.LanguageController;
import models.Language;
import org.junit.Test;
import play.libs.Json;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * The languages a new database is given (LanguageController.seedLanguages) are named in English,
 * whatever the language of the server's computer: the console's language bar, its Copy from button
 * and the docs then read the same everywhere. On a Chinese system they used to be named in Chinese
 * (Java's default display language), for good.
 */
public class LanguageNamesTest extends BaseTest {

    @Test
    public void aNewDatabaseNamesTheLanguagesInEnglishOnAChineseSystemToo() {
        Locale before = Locale.getDefault();
        Locale display = Locale.getDefault(Locale.Category.DISPLAY);
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        try {
            // What -Duser.language=zh -Duser.country=CN, a Chinese system's, gives Java.
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            assertNotEquals("(Java names languages in Chinese now)", "Chinese", Locale.CHINESE.getDisplayLanguage());
            assertTrue("a new database has no languages yet", Language.findAll().isEmpty());

            LanguageController.seedLanguages();
            assertEquals("English", Language.findByIso3("eng").getName());
            assertEquals("Chinese", Language.findByIso3("zho").getName());
            assertEquals("Spanish", Language.findByIso3("spa").getName());
            assertEquals("German", Language.findByIso3("deu").getName());

            // as the console lists them
            JsonNode listed = Json.parse(contentAsString(route(fakeRequest(GET, "/languages")))).get("languages");
            Map<String, String> names = new HashMap<String, String>();
            for (JsonNode l : listed) {
                names.put(l.get("code").asText(), l.get("name").asText());
            }
            assertEquals("English", names.get("eng"));
            assertEquals("Chinese", names.get("zho"));
        } finally {
            Locale.setDefault(before);
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, format);
        }
    }
}
