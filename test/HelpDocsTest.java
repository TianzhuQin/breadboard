import models.User;
import org.junit.Test;
import play.mvc.Result;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/** Help reads the guides shipped with this server, without exposing other local files. */
public class HelpDocsTest extends BaseTest {
    @Test
    public void guidesRequireResearcherSignIn() {
        Result result = route(fakeRequest(GET, "/docs/console.md"));
        assertNotNull("a local guide route", result);
        assertEquals(UNAUTHORIZED, status(result));
    }

    @Test
    public void readsTheShippedEnglishAndChineseGuides() {
        User me = createUser("help@example.org", "Help", "unit-test-only", createLanguage("eng", "English"));
        for (String file : new String[] {"console.md", "zh-CN.md", "install.md", "whats-new.md", "development.md"}) {
            Result result = route(fakeRequest(GET, "/docs/" + file).withSession("uid", me.uid));
            assertNotNull("a local guide route", result);
            assertEquals(file, OK, status(result));
            assertTrue(file, contentAsString(result).startsWith("#"));
            assertEquals("no-store", header("Cache-Control", result));
            assertTrue(header("Content-Type", result).contains("utf-8"));
        }
    }

    @Test
    public void refusesOtherFilesAndPaths() {
        User me = createUser("help@example.org", "Help", "unit-test-only", createLanguage("eng", "English"));
        for (String file : new String[] {"README.md", "../conf/local.conf", "..%2Fconf%2Flocal.conf", "console.md/other", "missing.md"}) {
            Result result = route(fakeRequest(GET, "/docs/" + file).withSession("uid", me.uid));
            assertNotNull(result);
            assertEquals(file, NOT_FOUND, status(result));
        }
    }
}
