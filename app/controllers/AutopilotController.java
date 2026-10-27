package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.Autopilot;
import models.AutopilotSettings;
import models.Experiment;
import models.User;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security;

/**
 * The console's autopilot settings: {@code GET /autopilot} and {@code POST /autopilot}, both answering
 * with the AutopilotState {@code {settings, source, status}} (see {@link Autopilot#stateJson}).
 */
public class AutopilotController extends Controller {

  @Security.Authenticated(Secured.class)
  public static Result get() {
    return ok(Autopilot.stateJson());
  }

  /**
   * Save the settings (keys left out keep their value) and act on them: with the autopilot on, a
   * session of the experiment opens now unless one is open; turned off, no new sessions open (a
   * running one continues). 400 {message} for a value that can't be accepted; 409 {message} while
   * application.conf is in charge.
   */
  @Security.Authenticated(Secured.class)
  public static Result save() {
    User user = User.findByUID(session("uid"));
    if (user == null) {
      return unauthorized(message("Sign in again."));
    }
    JsonNode body = request().body().asJson();
    if (body == null) {
      return badRequest(message("Send the settings as JSON."));
    }
    Autopilot.Effective current = Autopilot.effective();
    if (Autopilot.CONFIG.equals(current.source)) {
      return status(CONFLICT, message("The autopilot is set in the server's application.conf (breadboard.autopilot.*), "
          + "so it can't be changed here. Remove those lines and restart the server to manage it from the console."));
    }
    AutopilotSettings next;
    try {
      next = AutopilotSettings.fromJson(body, current.settings);
    } catch (AutopilotSettings.Invalid invalid) {
      return badRequest(message(invalid.getMessage()));
    }
    if (next.enabled && next.experimentId == null) {
      return badRequest(message("Choose the experiment to run (experimentId)."));
    }
    boolean sameExperiment = next.experimentId != null && next.experimentId.equals(current.settings.experimentId);
    if (next.experimentId != null) {
      Experiment experiment = Experiment.findById(next.experimentId);
      if (experiment == null) {
        return badRequest(message("There is no experiment with id " + next.experimentId + "."));
      }
      // Choosing an experiment takes owning it; the one already chosen (maybe by another researcher)
      // can still be turned on or off, or have its waiting room changed.
      if (!sameExperiment && !owns(user, experiment)) {
        return badRequest(message("\"" + experiment.name + "\" isn't one of your experiments."));
      }
      // Sessions open as the account that chose the experiment, or as whoever saves when they own it.
      if (!sameExperiment || owns(user, experiment) || current.settings.operatorEmail == null) {
        next.operatorEmail = user.email;
      }
    } else {
      next.operatorEmail = user.email;
    }
    next.save();
    Autopilot.settingsSaved(next);
    return ok(Autopilot.stateJson());
  }

  private static boolean owns(User user, Experiment experiment) {
    for (Experiment owned : user.ownedExperiments) {
      if (experiment.id.equals(owned.id)) {
        return true;
      }
    }
    return false;
  }

  private static ObjectNode message(String text) {
    ObjectNode json = Json.newObject();
    json.put("message", text);
    return json;
  }
}
