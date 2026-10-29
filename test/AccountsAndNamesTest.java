import com.fasterxml.jackson.databind.node.ObjectNode;
import models.*;
import org.junit.Test;
import play.libs.Json;
import play.mvc.Result;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * Researcher accounts and experiment names: sign-in ignores the email's case and doesn't sign out
 * the account's other browsers; the first account needs a real email address; experiments can be
 * renamed; no experiment is created, imported or renamed with a "/" or "\" in its name.
 */
public class AccountsAndNamesTest extends BaseTest {

    private Result signIn(String email, String password) {
        return route(fakeRequest(POST, "/login").withFormUrlEncodedBody(
            java.util.Collections.unmodifiableMap(new java.util.HashMap<String, String>() {{
                put("email", email);
                put("password", password);
            }})));
    }

    @Test
    public void signingInIgnoresTheEmailsCaseAndKeepsOtherBrowsersSignedIn() {
        Language lang = createLanguage("eng", "English");
        createUser("lab@test.org", "Lab", "password123", lang);

        Result first = signIn("lab@test.org", "password123");
        assertEquals(OK, status(first));
        String firstUid = Json.parse(contentAsString(first)).get("uid").asText();

        Result second = signIn("  Lab@Test.ORG ", "password123");
        assertEquals(OK, status(second));
        assertEquals("the second browser shares the token", firstUid, Json.parse(contentAsString(second)).get("uid").asText());
        assertEquals("lab@test.org", Json.parse(contentAsString(second)).get("email").asText());

        Result state = route(fakeRequest(GET, "/state").withSession("uid", firstUid));
        assertEquals("the first browser is still signed in", OK, status(state));

        assertEquals(BAD_REQUEST, status(signIn("lab@test.org", "wrong-password")));
    }

    @Test
    public void signingOutEndsEveryBrowsersSessionAndSaysWhy() {
        Language lang = createLanguage("eng", "English");
        createUser("out@test.org", "Out", "password123", lang);
        String uid = Json.parse(contentAsString(signIn("out@test.org", "password123"))).get("uid").asText();

        route(fakeRequest(POST, "/logout").withSession("uid", uid));
        Result state = route(fakeRequest(GET, "/state").withSession("uid", uid));
        assertEquals(UNAUTHORIZED, status(state));
        assertEquals("ended", Json.parse(contentAsString(state)).get("reason").asText());
    }

    @Test
    public void theFirstAccountNeedsARealEmailAndALongEnoughPassword() {
        Language lang = createLanguage("eng", "English");
        ObjectNode body = Json.newObject();
        body.put("email", "not-an-email");
        body.put("password", "password123");
        body.put("defaultLanguageId", lang.getId());
        assertEquals(BAD_REQUEST, status(route(fakeRequest(POST, "/createFirstUser").withJsonBody(body))));

        body.put("email", "first@test.org");
        body.put("password", "short");
        assertEquals(BAD_REQUEST, status(route(fakeRequest(POST, "/createFirstUser").withJsonBody(body))));

        body.put("email", " first@test.org ");
        body.put("password", "password123");
        assertEquals(OK, status(route(fakeRequest(POST, "/createFirstUser").withJsonBody(body))));
        assertNotNull(User.findByEmail("first@test.org"));

        Result again = route(fakeRequest(POST, "/createFirstUser").withJsonBody(body));
        assertEquals(BAD_REQUEST, status(again));
        assertTrue(contentAsString(again).contains("Sign in instead"));
    }

    @Test
    public void theDefaultLanguageCanBeChanged() {
        Language english = createLanguage("eng", "English");
        Language german = createLanguage("deu", "German");
        User user = createUser("lang@test.org", "Lang", "password123", english);
        ObjectNode body = Json.newObject();
        body.put("languageId", german.getId());
        assertEquals(OK, status(route(fakeRequest(POST, "/defaultLanguage").withSession("uid", user.uid).withJsonBody(body))));
        assertEquals("deu", User.findByEmail("lang@test.org").defaultLanguage.getCode());
        body.put("languageId", 987654);
        assertEquals(BAD_REQUEST, status(route(fakeRequest(POST, "/defaultLanguage").withSession("uid", user.uid).withJsonBody(body))));
        assertEquals(UNAUTHORIZED, status(route(fakeRequest(POST, "/defaultLanguage").withJsonBody(body))));
    }

    @Test
    public void anExperimentCanBeRenamedButNotToAnotherOnesName() {
        Language lang = createLanguage("eng", "English");
        User user = createUser("names@test.org", "Names", "password123", lang);
        Experiment a = createExperiment("Pilot");
        Experiment b = createExperiment("Main study");
        user.ownedExperiments.add(a);
        user.ownedExperiments.add(b);
        user.update();
        user.saveManyToManyAssociations("ownedExperiments");

        ObjectNode body = Json.newObject();
        body.put("name", "  Pilot, wave 2 ");
        Result ok = route(fakeRequest(POST, "/experiment/" + a.id + "/name").withSession("uid", user.uid).withJsonBody(body));
        assertEquals(contentAsString(ok), OK, status(ok));
        assertEquals("Pilot, wave 2", Experiment.findById(a.id).name);

        body.put("name", "main STUDY");
        assertEquals(BAD_REQUEST, status(route(fakeRequest(POST, "/experiment/" + a.id + "/name").withSession("uid", user.uid).withJsonBody(body))));
        body.put("name", "a/b");
        assertEquals(BAD_REQUEST, status(route(fakeRequest(POST, "/experiment/" + a.id + "/name").withSession("uid", user.uid).withJsonBody(body))));
        assertEquals("Pilot, wave 2", Experiment.findById(a.id).name);

        body.put("name", "Not mine");
        assertEquals(UNAUTHORIZED, status(route(fakeRequest(POST, "/experiment/" + a.id + "/name").withJsonBody(body))));
    }

    @Test
    public void aNameWithASlashIsRefusedWhereverAnExperimentIsNamed() {
        Language lang = createLanguage("eng", "English");
        User user = createUser("slashes@test.org", "Slashes", "password123", lang);
        Experiment a = createExperiment("Pilot");
        user.ownedExperiments.add(a);
        user.update();
        user.saveManyToManyAssociations("ownedExperiments");
        final String why = "Names can't contain / or \\ or line breaks.";

        // Created (from the template, as a copy, from an example), as renaming refuses them: the name
        // makes folders in file mode, dev/Pilot-A/B_<id>/.
        for (String name : new String[]{"Pilot A/B", "Pilot A\\B", "Two\nline\nbreaks"}) {
            for (String copy : new String[]{null, String.valueOf(a.id)}) {
                ObjectNode body = Json.newObject();
                body.put("newExperimentName", name);
                if (copy != null) body.put("copyExperimentId", Long.valueOf(copy));
                Result made = route(fakeRequest(PUT, "/experiment").withSession("uid", user.uid).withJsonBody(body, PUT));
                assertEquals(name, BAD_REQUEST, status(made));
                assertEquals(why, contentAsString(made));
            }
        }
        ObjectNode example = Json.newObject();
        example.put("newExperimentName", "Game 1/2");
        example.put("example", "network-game");
        assertEquals(BAD_REQUEST, status(route(fakeRequest(PUT, "/experiment").withSession("uid", user.uid).withJsonBody(example, PUT))));
        // Imported (the name is a path segment, so a "/" in it comes encoded)
        Result imported = route(fakeRequest(POST, "/experiment/import/Pilot%20A%2FB").withSession("uid", user.uid));
        assertEquals(BAD_REQUEST, status(imported));
        assertEquals(why, contentAsString(imported));
        // Renamed: line breaks anywhere in it, not only one
        ObjectNode rename = Json.newObject();
        rename.put("name", "Two\nline\nbreaks");
        Result renamed = route(fakeRequest(POST, "/experiment/" + a.id + "/name").withSession("uid", user.uid).withJsonBody(rename));
        assertEquals(BAD_REQUEST, status(renamed));
        assertEquals(why, contentAsString(renamed));
        assertEquals("nothing was made", 1, Experiment.findAll().size());
        assertEquals("Pilot", Experiment.findById(a.id).name);
    }

    @Test
    public void fromAnotherComputerTheFirstAccountNeedsTheSetupCodeTheServerPrinted() {
        Language lang = createLanguage("eng", "English");
        ObjectNode body = Json.newObject();
        body.put("email", "remote@test.org");
        body.put("password", "password123");
        body.put("defaultLanguageId", lang.getId());

        // the console, opened through a proxy, is told before it asks
        Result state = route(fakeRequest(GET, "/state").withHeader("X-Forwarded-For", "203.0.113.5"));
        assertEquals(UNAUTHORIZED, status(state));
        assertTrue(Json.parse(contentAsString(state)).get("setupCodeRequired").asBoolean());
        Result local = route(fakeRequest(GET, "/state"));
        assertFalse(Json.parse(contentAsString(local)).get("setupCodeRequired").asBoolean());

        Result refused = route(fakeRequest(POST, "/createFirstUser").withHeader("X-Forwarded-For", "203.0.113.5").withJsonBody(body));
        assertEquals(FORBIDDEN, status(refused));
        assertEquals(0, User.findRowCount());
        body.put("setupCode", "WRONG-CODE");
        assertEquals(FORBIDDEN, status(route(fakeRequest(POST, "/createFirstUser").withHeader("X-Forwarded-For", "203.0.113.5").withJsonBody(body))));

        // typed in lower case, with a space for the dash
        body.put("setupCode", security.SetupCode.current().toLowerCase().replace("-", " "));
        Result made = route(fakeRequest(POST, "/createFirstUser").withHeader("X-Forwarded-For", "203.0.113.5").withJsonBody(body));
        assertEquals(contentAsString(made), OK, status(made));
        assertNotNull(User.findByEmail("remote@test.org"));
    }
}
