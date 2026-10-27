package controllers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.io.FileUtils;
import play.*;
import play.libs.Json;
import play.mvc.*;
import play.data.Form;
import security.ParticipantAuth;
import views.html.*;
import com.fasterxml.jackson.databind.*;
import models.*;
import java.io.*;

// TODO: Why does this always redirect to login?
//@Security.Authenticated(SecuredClient.class)
public class ClientController extends Controller 
{
    public static Result index(String experimentId, String experimentInstanceId, String clientId, String connectionSpeed) 
    {
        ExperimentInstance experimentInstance = null;
        Experiment experiment = null;
        
        try {
          experimentInstance = ExperimentInstance.findById(Long.valueOf(experimentInstanceId));
          experiment = Experiment.findById(Long.valueOf(experimentId));
        } catch (NumberFormatException ignored) {}

        if (experimentInstance == null || experiment == null || experimentInstance.status != ExperimentInstance.Status.RUNNING) {
          return ok( amtError.render() );
        }

        // A signed invitation link claims the seat, then redirects to the plain page URL so the
        // signature doesn't linger in the address bar or browser history.
        String invite = request().getQueryString("invite");
        if (invite != null) {
          if (!ParticipantAuth.verifyInvite(experimentInstanceId, clientId, invite)) {
            return forbidden(amtError.render());
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
     * Whether the current request may act as this participant: it presents the seat's token, or
     * comes from a logged-in researcher (the admin's Testing panel embeds participant pages).
     */
    static boolean isAuthorized(String experimentInstanceId, String clientId) {
        String uid = session("uid");
        if (uid != null && User.findByUID(uid) != null) {
            return true;
        }
        String token = ParticipantAuth.presentedToken(request(), experimentInstanceId);
        return ParticipantAuth.verify(experimentInstanceId, clientId, token);
    }

    public static Result getState(String experimentId, String experimentInstanceId, String clientId, String connectionSpeed){
        if (!isAuthorized(experimentInstanceId, clientId)) {
            ObjectNode error = Json.newObject();
            error.put("error", "not-logged-in");
            error.put("login", routes.ClientLogin.login(experimentId, experimentInstanceId).url());
            return unauthorized(error);
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
        return ok(result);
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

    /**
    * Handle the client websocket.
    */
    public static WebSocket<JsonNode> connectClient(final String experimentId, final String experimentInstanceId, final String clientId) 
    {
        // Checked here, during the handshake request; the callbacks below run later on another
        // thread without access to the request.
        final boolean authorized = isAuthorized(experimentInstanceId, clientId);
        return new WebSocket<JsonNode>() 
        {
            // Called when the Websocket Handshake is done.
            public void onReady(final WebSocket.In<JsonNode> in, final WebSocket.Out<JsonNode> out)
            {
                Logger.debug("ClientController.connectClient.onReady");
                if (!authorized) {
                    Logger.info("Refused websocket for client " + clientId + " in instance " + experimentInstanceId + ": no valid token");
                    out.close();
                    return;
                }
                try
                {
                    ScriptBoard.addClient(experimentId, experimentInstanceId, clientId, in, new ThrottledWebSocketOut(out, Breadboard.WEBSOCKET_RATE));
                }
                catch (Exception ex)
                {
                    ex.printStackTrace();
                }
            }
        };
    }
}
