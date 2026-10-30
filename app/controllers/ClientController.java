package controllers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.io.FileUtils;
import play.*;
import play.libs.Json;
import play.mvc.*;
import play.data.Form;
import security.ParticipantAuth;
import security.PreviewTokens;
import views.html.*;
import com.fasterxml.jackson.databind.*;
import models.*;
import java.io.*;
import java.util.Date;

// TODO: Why does this always redirect to login?
//@Security.Authenticated(SecuredClient.class)
public class ClientController extends Controller 
{
    /**
     * The policy a participant page opened as a researcher's preview ({@code ?preview=1}) is served
     * with. The console frames previews with the same sandbox; the header keeps a preview sandboxed
     * even when it is opened in a tab of its own. Without allow-same-origin the page runs in an opaque
     * origin: no cookies, no storage, and no way to call the console's routes as the researcher.
     */
    static final String PREVIEW_SANDBOX = "sandbox allow-scripts allow-forms allow-modals allow-popups";

    public static Result index(String experimentId, String experimentInstanceId, String clientId, String connectionSpeed) 
    {
        if ("1".equals(request().getQueryString("preview"))) {
            // Whatever the page turns out to be (the game, or a "not running" notice).
            response().setHeader("Content-Security-Policy", PREVIEW_SANDBOX);
        }
        ExperimentInstance experimentInstance = null;
        Experiment experiment = null;
        
        try {
          experimentInstance = ExperimentInstance.findById(Long.valueOf(experimentInstanceId));
          experiment = Experiment.findById(Long.valueOf(experimentId));
        } catch (NumberFormatException ignored) {}

        // Someone who took part reloading their page after the session ended (or was stopped) gets
        // their last screen back (their score, a completion code) over the socket, not a notice that
        // sends them to the next session.
        boolean returning = experimentInstance != null && experiment != null
            && (experimentInstance.status == ExperimentInstance.Status.FINISHED || experimentInstance.status == ExperimentInstance.Status.STOPPED)
            && ParticipantAuth.verify(experimentInstanceId, clientId, ParticipantAuth.presentedToken(request(), experimentInstanceId));
        if (!returning && (experimentInstance == null || experiment == null || experimentInstance.status != ExperimentInstance.Status.RUNNING)) {
          // Mechanical Turk workers are asked to return the HIT; everyone else gets the general notice.
          return ok(experimentInstance != null && experimentInstance.getHit() != null
              ? amtError.render() : ClientLogin.notRunning(experimentId, experimentInstanceId));
        }

        // A signed invitation link claims the seat, then redirects to the plain page URL so the
        // signature doesn't linger in the address bar or browser history.
        String invite = request().getQueryString("invite");
        if (invite != null) {
          boolean amt = experimentInstance.getHit() != null;
          if (!ParticipantAuth.verifyInvite(experimentInstanceId, clientId, invite)) {
            return forbidden(amt ? amtError.render() : participantMessage.render("This link isn't valid",
                "This invitation link isn't valid. Please check the link you were given, or contact the researcher."));
          }
          if (!amt && !ScriptBoard.runsSession(experimentId, experimentInstanceId)) {
            // As at sign-in: a session the engine doesn't run (e.g. since a restart) takes no one.
            return ok(ClientLogin.notRunning());
          }
          if (ClientLogin.closedToNewcomers(experimentInstanceId, clientId)) {
            return ok(amt ? amtGameStarted.render() : participantMessage.render("The session has already started",
                "This session began before you arrived, so it takes no one new. Please contact the researcher."));
          }
          String token = ParticipantAuth.claim(experimentInstanceId, clientId);
          if (token == null) {
            Form<ClientLogin.CLogin> form = Form.form(ClientLogin.CLogin.class);
            form.reject("This invitation link has already been used.");
            return forbidden(clientLogin.render(experimentId, experimentInstanceId, form));
          }
          return ClientLogin.enterGame(experimentId, experimentInstanceId, clientId, token, connectionSpeed);
        }

        // The page itself carries nothing private; /state and the websocket check the token.
        String assetsRoot = play.Play.application().configuration().getString("breadboard.assetsRoot", "/assets");
        if (assetsRoot != null) {
            return ok(client.render(assetsRoot));
        } else {
            return ok(client.render(""));
        }
    }

    /**
     * Whether the current request may act as this participant: it presents the seat's token, or a
     * researcher's preview token for this page, or comes from a signed-in researcher (the previous
     * console's Testing panel embeds participant pages in its own origin, with the researcher's cookie).
     */
    static boolean isAuthorized(String experimentId, String experimentInstanceId, String clientId) {
        if (isResearcher()) {
            return true;
        }
        String token = ParticipantAuth.presentedToken(request(), experimentInstanceId);
        if (PreviewTokens.verify(experimentId, experimentInstanceId, clientId, token)) {
            // A researcher's preview: as their sign-in would, the test session included.
            return true;
        }
        if (ClientLogin.isTestSession(experimentInstanceId)) {
            // The test session (0) is a researcher's only: a participant's seat token for it (from
            // before the login refused it) used to put them into the game of whatever session ran.
            return false;
        }
        return ParticipantAuth.verify(experimentInstanceId, clientId, token);
    }

    /** Whether the request comes from a signed-in researcher. */
    static boolean isResearcher() {
        String uid = session("uid");
        return uid != null && User.findByUID(uid) != null;
    }

    public static Result getState(String experimentId, String experimentInstanceId, String clientId, String connectionSpeed){
        // A preview runs in an opaque origin (see PREVIEW_SANDBOX), so its request for this is
        // cross-origin. Any origin may read the answer, but never with credentials: a page elsewhere
        // can't use a participant's cookie or the researcher's sign-in to read it, only a token it
        // already holds.
        response().setHeader("Access-Control-Allow-Origin", "*");
        if (!isAuthorized(experimentId, experimentInstanceId, clientId)) {
            ObjectNode error = Json.newObject();
            error.put("error", "not-logged-in");
            error.put("login", routes.ClientLogin.login(experimentId, experimentInstanceId).url());
            return unauthorized(error);
        }
        if (request().getQueryString(SEAT_QUERY) != null) {
            // A page of the participant's that another one took the seat from asks whether that one
            // still holds it: once it has gone, the page takes the seat back (frontend/core).
            ObjectNode seat = Json.newObject();
            seat.put("seatHeld", ScriptBoard.seatHeldByTheirPage(experimentId, experimentInstanceId, clientId));
            return ok(seat);
        }
        // Only what the page needs. This used to echo every request header back to page
        // JavaScript (including the Cookie header), plus the IP address and full request URI.
        ObjectNode result = Json.newObject();
        result.put("assetsRoot", play.Play.application().configuration().getString("breadboard.assetsRoot", "/assets"));
        result.put("clientId", clientId);
        result.put("experimentId", experimentId);
        result.put("experimentInstanceId", experimentInstanceId);
        result.put("clientGraph", getClientGraph(experimentId));
        result.put("clientHtml", getClientHtml(experimentId));
        String socketUrl = routes.ClientController.connectClient(experimentId, experimentInstanceId, clientId)
            .webSocketURL(request(), play.Play.application().configuration().getString("breadboard.wsUrl", "").contains("wss://"));
        if (!ParticipantAuth.presentedViaCookie(request())) {
            // Cookies aren't available (e.g. an iframe on another site), so the websocket handshake
            // has to carry the token itself.
            String token = ParticipantAuth.presentedToken(request(), experimentInstanceId);
            if (token != null) {
                socketUrl = socketUrl + "?" + ParticipantAuth.QUERY_PARAM + "=" + token;
            }
        }
        result.put("connectSocket", socketUrl);
        // When this page was loaded, in the server's time: its socket says so as it connects, so that
        // of two pages of the participant's, the one opened last keeps the seat (Client.connect).
        result.put("loadedAt", System.currentTimeMillis());
        return ok(result);
    }

    /**
     * The CORS preflight for {@link #getState}: a preview sends its token in the X-Breadboard-Token
     * header, which a cross-origin GET may only carry once this allows it.
     */
    public static Result stateOptions(String experimentId, String experimentInstanceId, String clientId) {
        response().setHeader("Access-Control-Allow-Origin", "*");
        response().setHeader("Access-Control-Allow-Methods", "GET");
        response().setHeader("Access-Control-Allow-Headers", ParticipantAuth.HEADER);
        response().setHeader("Access-Control-Max-Age", "600");
        return noContent();
    }

    /**
     * A preview token ({@link PreviewTokens}) for the console to show a participant's page:
     * {@code POST /preview-token} with {"experimentId", "experimentInstanceId", "clientId"}. Answers
     * {"token", "expiresAt" (ISO-8601, UTC)}, or 400 {"error"} when the body is wrong, there is no such
     * session, or the session is another experiment's. The test session (0) can be previewed for any
     * experiment the account has. The client id is any the participant login takes, and the computer
     * players' ("_1", ...) too. Nothing about the participant's own seat changes.
     */
    @Security.Authenticated(Secured.class)
    public static Result previewToken() {
        User user = User.findByUID(session("uid"));
        if (user == null) {
            return unauthorized();
        }
        JsonNode body = request().body().asJson();
        if (body == null || !body.isObject()) {
            return previewRefused("Send {\"experimentId\", \"experimentInstanceId\", \"clientId\"} as JSON.");
        }
        Long experimentId = wholeNumber(body.get("experimentId"));
        Long instanceId = wholeNumber(body.get("experimentInstanceId"));
        JsonNode client = body.get("clientId");
        String clientId = client != null && client.isTextual() ? client.textValue() : null;
        if (experimentId == null) {
            return previewRefused("\"experimentId\" must be a number.");
        }
        if (instanceId == null) {
            return previewRefused("\"experimentInstanceId\" must be a number.");
        }
        if (clientId == null || clientId.trim().isEmpty()) {
            return previewRefused("\"clientId\" must be a player's id.");
        }
        Experiment experiment = Experiment.findById(experimentId);
        if (experiment == null) {
            return previewRefused("There is no experiment with id " + experimentId + ".");
        }
        if (Experiment.TEST_INSTANCE_ID.equals(instanceId)) {
            if (!canSee(user, experiment)) {
                return previewRefused("\"" + experiment.name + "\" isn't one of your experiments.");
            }
        } else {
            ExperimentInstance instance = ExperimentInstance.findById(instanceId);
            if (instance == null) {
                return previewRefused("There is no session with id " + instanceId + ".");
            }
            if (instance.experiment == null || !experimentId.equals(instance.experiment.id)) {
                return previewRefused("Session " + instanceId + " is not a session of \"" + experiment.name + "\".");
            }
        }
        PreviewTokens.Issued issued = PreviewTokens.issue(user.email, experimentId.toString(), instanceId.toString(), clientId);
        ObjectNode result = Json.newObject();
        result.put("token", issued.token);
        result.put("expiresAt", Timestamps.iso(new Date(issued.expiresAt)));
        return ok(result);
    }

    // Every researcher works on every experiment on the server (User.experiments).
    private static boolean canSee(User user, Experiment experiment) {
        return user != null && experiment != null;
    }

    /** A JSON whole number, or one written as text ("12"); else null. */
    private static Long wholeNumber(JsonNode value) {
        if (value == null) {
            return null;
        }
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            return value.longValue();
        }
        if (value.isTextual()) {
            try {
                return Long.valueOf(value.textValue().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Result previewRefused(String why) {
        ObjectNode error = Json.newObject();
        error.put("error", why);
        return badRequest(error);
    }

    /**
     * Return the custom experiement graph if it exists
     * @return
     */
    public static String getClientGraph(String experimentId){
        Experiment experiment = null;
        try {
            experiment = Experiment.findById(Long.valueOf(experimentId));
        } catch (NumberFormatException ignored) {}

        if (experiment == null) {
            File file = play.Play.application().getFile("conf/defaults/default-client-graph.js");
            try {
                return FileUtils.readFileToString(file, "UTF-8");
            } catch(IOException ignored) {}
            return null;
        }
        return experiment.getClientGraph();
    }

    /**
     * Return the custom experiment client html if it exists
     */
    public static String getClientHtml(String experimentId){
        Experiment experiment = null;
        try {
            experiment = Experiment.findById(Long.valueOf(experimentId));
        } catch (NumberFormatException ignored) {}

        if (experiment == null) {
            File file = play.Play.application().getFile("conf/defaults/default-client-html.html");
            try {
                return FileUtils.readFileToString(file, "UTF-8");
            } catch(IOException ignored) {}
            return null;
        }
        return experiment.getClientHtml();
    }

    // What a participant's page adds to its socket's address (frontend/core, breadboard.ts): when it
    // was loaded (its /state's loadedAt), and, when the participant chose "Use it here", that it takes
    // the seat.
    static final String PAGE_LOADED_QUERY = "bbPage";
    static final String TAKE_SEAT_QUERY = "bbTake";
    // What the page adds to its state's address to ask only whether a page of the participant's holds
    // their seat now (getState).
    static final String SEAT_QUERY = "seat";

    /** A whole number written as text, or 0. */
    private static long wholeNumberOrZero(String text) {
        if (text == null) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(text.trim()));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /**
    * Handle the client websocket.
    */
    public static WebSocket<JsonNode> connectClient(final String experimentId, final String experimentInstanceId, final String clientId) 
    {
        // Checked here, during the handshake request; the callbacks below run later on another
        // thread without access to the request.
        final boolean authorized = isAuthorized(experimentId, experimentInstanceId, clientId);
        // A researcher looking through the player's eyes, not the participant: the data says so.
        final boolean byResearcher = authorized && !ParticipantAuth.verify(experimentInstanceId, clientId,
            ParticipantAuth.presentedToken(request(), experimentInstanceId));
        // When the page was loaded (its /state's loadedAt), and whether the participant asked for the
        // seat there ("Use it here"): see Client.Connection.
        final long pageLoadedAt = wholeNumberOrZero(request().getQueryString(PAGE_LOADED_QUERY));
        final boolean takesSeat = "1".equals(request().getQueryString(TAKE_SEAT_QUERY));
        return new WebSocket<JsonNode>() 
        {
            // Called when the Websocket Handshake is done.
            public void onReady(final WebSocket.In<JsonNode> in, final WebSocket.Out<JsonNode> out)
            {
                Logger.debug("ClientController.connectClient.onReady");
                if (!authorized) {
                    refuse(experimentId, experimentInstanceId, clientId, out);
                    return;
                }
                try
                {
                    ScriptBoard.addClient(experimentId, experimentInstanceId, clientId, in, new ThrottledWebSocketOut(out, Breadboard.WEBSOCKET_RATE), byResearcher,
                        pageLoadedAt, takesSeat);
                }
                catch (Exception ex)
                {
                    ex.printStackTrace();
                }
            }
        };
    }

    /**
     * Turn away a participant page whose socket holds no valid credentials. Most often the page was
     * open while the server restarted: seat tokens live only in memory, so its token is gone. Closed
     * without a word, the page took it for a dropped connection and retried every few seconds for
     * ever, so it is told why first: the session isn't running (it then shows what a reload would
     * show, and stops reconnecting), or the session runs and the page has to sign in again. Only at
     * debug level: each open page used to add a line to the log every few seconds.
     */
    private static void refuse(String experimentId, String experimentInstanceId, String clientId, WebSocket.Out<JsonNode> out) {
        Logger.debug("Refused websocket for client " + clientId + " in instance " + experimentInstanceId + ": no valid token");
        try {
            out.write(ScriptBoard.runsSession(experimentId, experimentInstanceId) ? signInAgain() : notRunningScreen(experimentId, experimentInstanceId));
        } catch (RuntimeException e) {
            // (then closed without a word, as before)
            Logger.debug("Could not tell client " + clientId + " why its websocket was refused: " + e);
        } finally {
            out.close();
        }
    }

    /**
     * The last screen of a page whose session the engine doesn't run (any more), with the words a
     * reload of the page shows (index: the notice for a Mechanical Turk HIT, for a session that
     * doesn't exist, or ClientLogin.notRunning()), marked finished and ended like the screens of
     * sessions that have ended. Under "sessionOver", not "player": pages from before 3.1 (and the
     * AngularJS client) render every player frame, and a completion code on their screen would be
     * replaced; they ignore this and go on retrying quietly, as they did.
     */
    static ObjectNode notRunningScreen(String experimentId, String experimentInstanceId) {
        String[] words;
        ExperimentInstance instance = null;
        try {
            instance = ExperimentInstance.findById(Long.valueOf(experimentInstanceId));
        } catch (NumberFormatException ignored) {}
        if (instance != null && instance.getHit() != null) {
            words = ClientLogin.AMT_NOT_RUNNING;
        } else if (!ClientLogin.sessionExists(experimentId, experimentInstanceId)) {
            words = ClientLogin.INVALID_LINK;
        } else {
            words = ClientLogin.NOT_RUNNING;
        }
        ObjectNode frame = Json.newObject();
        ObjectNode player = frame.putObject("sessionOver").putObject("player");
        player.putObject("_system");
        player.put("finished", true);
        player.put("ended", "notRunning");
        player.put("text", "<h3>" + words[0] + "</h3><p>" + words[1] + "</p>");
        return frame;
    }

    /** For a page whose session runs but whose token no longer opens its seat: it signs in again. */
    private static ObjectNode signInAgain() {
        ObjectNode frame = Json.newObject();
        frame.put("reauth", true);
        return frame;
    }
}
