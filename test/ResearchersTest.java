import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.*;
import org.junit.Test;
import play.libs.Json;
import play.mvc.Result;

import static org.junit.Assert.*;
import static play.test.Helpers.*;

/**
 * Researcher accounts (controllers.ResearchersController): any researcher can add another, give
 * another a new password, or remove another, and every account works on every experiment.
 */
public class ResearchersTest extends BaseTest {

    private Result post(String url, User as, String email) {
        ObjectNode body = Json.newObject();
        body.put("email", email);
        return route(fakeRequest(POST, url).withSession("uid", as.uid).withSession("email", as.email).withJsonBody(body));
    }

    private Result signIn(String email, String password) {
        return route(fakeRequest(POST, "/login").withFormUrlEncodedBody(
            java.util.Collections.unmodifiableMap(new java.util.HashMap<String, String>() {{
                put("email", email);
                put("password", password);
            }})));
    }

    @Test
    public void theListShowsEveryAccountAndWhichOneIsYou() {
        Language lang = createLanguage("eng", "English");
        User me = createUser("pi@lab.org", "PI", "password123", lang);
        createUser("ra@lab.org", "RA", "password123", lang);

        Result list = route(fakeRequest(GET, "/researchers").withSession("uid", me.uid));
        assertEquals(OK, status(list));
        JsonNode rows = Json.parse(contentAsString(list)).get("researchers");
        assertEquals(2, rows.size());
        assertEquals("pi@lab.org", rows.get(0).get("email").asText());
        assertTrue(rows.get(0).get("you").asBoolean());
        assertFalse(rows.get(1).get("you").asBoolean());

        assertEquals(UNAUTHORIZED, status(route(fakeRequest(GET, "/researchers"))));
    }

    @Test
    public void anAddedAccountSignsInWithThePasswordShownOnceAndSeesEveryExperiment() {
        Language lang = createLanguage("eng", "English");
        User me = createUser("pi@lab.org", "PI", "password123", lang);
        Experiment study = createExperiment("Main study");
        me.ownedExperiments.add(study);
        me.update();
        me.saveManyToManyAssociations("ownedExperiments");

        assertEquals(BAD_REQUEST, status(post("/researchers", me, "not an email")));
        assertEquals(BAD_REQUEST, status(post("/researchers", me, "PI@Lab.org")));

        Result added = post("/researchers", me, " ra@lab.org ");
        assertEquals(contentAsString(added), OK, status(added));
        JsonNode body = Json.parse(contentAsString(added));
        assertEquals("ra@lab.org", body.get("email").asText());
        String password = body.get("password").asText();
        assertTrue(password.length() >= User.PASSWORD_MIN_LENGTH);
        assertEquals(2, body.get("researchers").size());
        assertEquals("the new account writes in the same language", "eng", User.findByEmail("ra@lab.org").defaultLanguage.getCode());

        Result signedIn = signIn("ra@lab.org", password);
        assertEquals(OK, status(signedIn));
        User ra = User.findByEmail("ra@lab.org");
        assertEquals("the experiment someone else made is in the new account's list",
            "Main study", ra.toJson().get("user").get("experiments").get(0).get("name").asText());

        // ... and it can work on it
        ObjectNode name = Json.newObject();
        name.put("name", "Main study, wave 2");
        Result renamed = route(fakeRequest(POST, "/experiment/" + study.id + "/name").withSession("uid", ra.uid).withJsonBody(name));
        assertEquals(contentAsString(renamed), OK, status(renamed));
        assertEquals("Main study, wave 2", Experiment.findById(study.id).name);
    }

    @Test
    public void anotherAccountCanBeGivenANewPasswordButNotYourOwn() {
        Language lang = createLanguage("eng", "English");
        User me = createUser("pi@lab.org", "PI", "password123", lang);
        User ra = createUser("ra@lab.org", "RA", "password123", lang);
        String raOldUid = ra.uid;

        assertEquals(BAD_REQUEST, status(post("/researchers/password", me, "pi@lab.org")));
        assertEquals(BAD_REQUEST, status(post("/researchers/password", me, "nobody@lab.org")));

        Result reset = post("/researchers/password", me, "RA@lab.org");
        assertEquals(contentAsString(reset), OK, status(reset));
        String password = Json.parse(contentAsString(reset)).get("password").asText();

        assertEquals("the old password stops working", BAD_REQUEST, status(signIn("ra@lab.org", "password123")));
        assertEquals(OK, status(signIn("ra@lab.org", password)));
        Result oldSession = route(fakeRequest(GET, "/state").withSession("uid", raOldUid).withSession("email", "ra@lab.org"));
        assertEquals("browsers signed in before are signed out", UNAUTHORIZED, status(oldSession));
        assertEquals("ended", Json.parse(contentAsString(oldSession)).get("reason").asText());
    }

    @Test
    public void anotherAccountCanBeRemovedAndItsExperimentsStay() {
        Language lang = createLanguage("eng", "English");
        User me = createUser("pi@lab.org", "PI", "password123", lang);
        User ra = createUser("ra@lab.org", "RA", "password123", lang);
        Experiment study = createExperiment("RA's pilot");
        ra.ownedExperiments.add(study);
        ra.setSelectedExperiment(study);
        ra.update();
        ra.saveManyToManyAssociations("ownedExperiments");

        assertEquals(BAD_REQUEST, status(post("/researchers/remove", me, "pi@lab.org")));

        Result removed = post("/researchers/remove", me, "ra@lab.org");
        assertEquals(contentAsString(removed), OK, status(removed));
        assertEquals(1, Json.parse(contentAsString(removed)).get("researchers").size());
        assertNull(User.findByEmail("ra@lab.org"));
        assertNotNull("the experiment stays", Experiment.findById(study.id));
        assertEquals("RA's pilot", me.toJson().get("user").get("experiments").get(0).get("name").asText());

        Result state = route(fakeRequest(GET, "/state").withSession("uid", ra.uid).withSession("email", "ra@lab.org"));
        assertEquals(UNAUTHORIZED, status(state));
        assertEquals("removed", Json.parse(contentAsString(state)).get("reason").asText());
        assertEquals(BAD_REQUEST, status(signIn("ra@lab.org", "password123")));
    }

    @Test
    public void experimentNamesAreUniqueAcrossAccounts() {
        Language lang = createLanguage("eng", "English");
        User me = createUser("pi@lab.org", "PI", "password123", lang);
        User ra = createUser("ra@lab.org", "RA", "password123", lang);
        Experiment study = createExperiment("Main study");
        me.ownedExperiments.add(study);
        me.update();
        me.saveManyToManyAssociations("ownedExperiments");

        ObjectNode body = Json.newObject();
        body.put("newExperimentName", "  main STUDY ");
        Result same = route(fakeRequest(PUT, "/experiment").withSession("uid", ra.uid).withJsonBody(body, PUT));
        assertEquals(BAD_REQUEST, status(same));
        assertTrue(contentAsString(same).contains("already an experiment"));

        body.put("newExperimentName", " RA pilot ");
        Result made = route(fakeRequest(PUT, "/experiment").withSession("uid", ra.uid).withJsonBody(body, PUT));
        assertEquals(contentAsString(made), OK, status(made));
        assertEquals("RA pilot", Json.parse(contentAsString(made)).get("name").asText());
        assertEquals("the other account sees it too", 2, me.toJson().get("user").get("experiments").size());
    }
}
