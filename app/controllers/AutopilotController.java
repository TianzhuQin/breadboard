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
 * The console's autopilot settings: {@code GET /autopilot}, {@code POST /autopilot} and
 * {@code POST /autopilot/resume}, each answering with the AutopilotState
 * {@code {settings, source, status}} (see {@link Autopilot#stateJson}).
 */
public class AutopilotController extends Controller {

  @Security.Authenticated(Secured.class)
  public static Result get() {
    return ok(Autopilot.stateJson());
  }

  /**
   * Save the settings (keys left out keep their value) and act on them: with the autopilot on, a
   * session of the experiment opens now unless one is open, or the engine runs a live session of
   * another experiment (then it waits for that one to end, unless the body has {@code takeOver: true});
   * turned off, no new sessions open (a running one continues). A save also lifts a pause. 400
   * {message} for a value that can't be accepted; 409 {message} while application.conf is in charge.
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
    if (next.experimentId != null && !next.enabled && sameExperiment && Experiment.findById(next.experimentId) == null) {
      // Switching off (or editing while off) after the experiment was deleted: the console posts the
      // id it still has, and refusing it left no way to turn the autopilot off.
      next.experimentId = null;
    }
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
    // Only with takeOver: true (which the console sends after asking) does opening a session end a
    // live session of another experiment; otherwise the autopilot waits for it to end.
    JsonNode takeOver = body.get("takeOver");
    Autopilot.settingsSaved(next, takeOver != null && takeOver.isBoolean() && takeOver.booleanValue());
    return ok(Autopilot.stateJson());
  }

  /**
   * Resume ({@code POST /autopilot/resume}, no body): a paused or done autopilot opens sessions by
   * itself again, and one now if none of its experiment is open and the engine is free (a live session
   * of another experiment is left to end). It changes no settings, so it also works while
   * application.conf is in charge, where saving answers 409. 400 {message} when the autopilot is off.
   * Answers with the AutopilotState, as a save does.
   */
  @Security.Authenticated(Secured.class)
  public static Result resume() {
    User user = User.findByUID(session("uid"));
    if (user == null) {
      return unauthorized(message("Sign in again."));
    }
    if (!Autopilot.effective().isOn()) {
      return badRequest(message("The autopilot is off, so there is nothing to resume. Switch it on and save instead."));
    }
    Autopilot.resume(user);
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
