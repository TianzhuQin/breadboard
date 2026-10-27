package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.Experiment;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security;

public class CustomizeController extends Controller {

  @Security.Authenticated(Secured.class)
  public static Result getClientHtml(Long experimentId) {
    Experiment experiment = Experiment.findById(experimentId);
    if(experiment == null) {
      return badRequest("Invalid Experiment ID");
    }

    ObjectNode returnJson = Json.newObject();
    returnJson.put("clientHtml", experiment.getClientHtml());
    return ok(returnJson);
  }

  @Security.Authenticated(Secured.class)
  public static Result getClientGraph(Long experimentId) {
    Experiment experiment = Experiment.findById(experimentId);
    if(experiment == null) {
      return badRequest("Invalid Experiment ID");
    }

    ObjectNode returnJson = Json.newObject();
    returnJson.put("clientGraph", experiment.getClientGraph());
    return ok(returnJson);
  }

  @Security.Authenticated(Secured.class)
  public static Result getStyle(Long experimentId) {
    Experiment experiment = Experiment.findById(experimentId);
    if(experiment == null) {
      return badRequest("Invalid Experiment ID");
    }

    ObjectNode returnJson = Json.newObject();
    returnJson.put("style", experiment.getStyle());
    return ok(returnJson);
  }

  /**
   * The text sent under {@code key}: "" (and null) clear it, so researchers can remove their custom
   * page, script or style; only a body without the key is refused (null is returned for it).
   */
  static String text(JsonNode json, String key) {
    JsonNode node = json.get(key);
    if (node == null) {
      return null;
    }
    if (node.isNull()) {
      return "";
    }
    return node.isValueNode() ? node.asText() : node.toString();
  }

  @Security.Authenticated(Secured.class)
  public static Result updateClientHtml(Long experimentId) {
    Experiment experiment = Experiment.findById(experimentId);

    if(experiment == null) {
      return badRequest("Invalid Experiment ID");
    }

    JsonNode json = request().body().asJson();
    if(json == null) {
      return badRequest("Expecting Json data");
    }
    String clientHtml = text(json, "clientHtml");
    if (clientHtml == null) {
      return badRequest("Please provide client HTML.");
    }

    experiment.setClientHtml(clientHtml);
    experiment.save();

    return ok();
  }

  @Security.Authenticated(Secured.class)
  public static Result updateClientGraph(Long experimentId) {
    Experiment experiment = Experiment.findById(experimentId);
    if(experiment == null) {
      return badRequest("Invalid Experiment ID");
    }

    JsonNode json = request().body().asJson();
    if(json == null) {
      return badRequest("Expecting Json data");
    }
    String clientGraph = text(json, "clientGraph");
    if (clientGraph == null) {
      return badRequest("Please provide client graph.");
    }

    experiment.setClientGraph(clientGraph);
    experiment.save();

    return ok();
  }

  @Security.Authenticated(Secured.class)
  public static Result updateStyle(Long experimentId) {
    Experiment experiment = Experiment.findById(experimentId);
    if(experiment == null) {
      return badRequest("Invalid Experiment ID");
    }

    JsonNode json = request().body().asJson();
    if(json == null) {
      return badRequest("Expecting Json data");
    }
    String style = text(json, "style");
    if (style == null) {
      return badRequest("Please provide style.");
    }

    experiment.setStyle(style);
    experiment.save();

    return ok();
  }

}
