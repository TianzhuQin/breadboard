package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.Parameter;
import models.Experiment;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security;

import java.util.Arrays;
import java.util.List;

public class ParametersController extends Controller {

  static final List<String> TYPES = Arrays.asList("Integer", "Decimal", "Text", "Boolean");

  @Security.Authenticated(Secured.class)
  public static Result createParameter(Long experimentId) {

    Experiment experiment = Experiment.findById(experimentId);
    if (experiment == null) {
      return badRequest("Invalid experiment ID");
     }
    JsonNode json = request().body().asJson();
    if(json == null) {
      return badRequest("Expecting Json data");
    }
    Parameter parameter = new Parameter();
    String problem = fill(parameter, experiment, json);
    if (problem != null) {
      return badRequest(problem);
    }

    experiment.parameters.add(parameter);
    experiment.save();

    ObjectNode returnJson = Json.newObject();
    returnJson.put("parameter", parameter.toJson());
    return ok(returnJson);
  }

  /**
   * Change a parameter in place (it keeps its id and its place in the list). The body is the same
   * as for {@link #createParameter}. 200 {"parameter": {...}}, or 400 {"message": "<what is wrong>"}.
   * Sessions already created keep the values they were given.
   */
  @Security.Authenticated(Secured.class)
  public static Result updateParameter(Long experimentId, Long parameterId) {
    Experiment experiment = Experiment.findById(experimentId);
    if (experiment == null) {
      return badRequest(message("There is no experiment with id " + experimentId + "."));
    }
    Parameter parameter = null;
    for (Parameter p : experiment.parameters) {
      if (p.id != null && p.id.equals(parameterId)) {
        parameter = p;
      }
    }
    if (parameter == null) {
      return badRequest(message("This experiment has no parameter with id " + parameterId + ". It may have been deleted."));
    }
    JsonNode json = request().body().asJson();
    if (json == null) {
      return badRequest(message("Expecting Json data"));
    }
    String problem = fill(parameter, experiment, json);
    if (problem != null) {
      return badRequest(message(problem));
    }
    parameter.update();

    ObjectNode returnJson = Json.newObject();
    returnJson.put("parameter", parameter.toJson());
    return ok(returnJson);
  }

  /**
   * Check a create or update body and copy it into {@code parameter}: the name trimmed and required,
   * not used by another parameter of the experiment, the type one of {@link #TYPES}, and no min. or
   * max. for Text and Boolean. Returns what is wrong (nothing is copied then), or null.
   */
  static String fill(Parameter parameter, Experiment experiment, JsonNode json) {
    String name = text(json, "name");
    String type = text(json, "type");
    if (name == null || name.trim().isEmpty()) {
      return "Please provide a name for the parameter.";
    }
    name = name.trim();
    if (type == null || !TYPES.contains(type)) {
      return "Please provide the parameter's type: Integer, Decimal, Text or Boolean.";
    }
    for (Parameter existing : experiment.parameters) {
      boolean itself = existing == parameter || (parameter.id != null && parameter.id.equals(existing.id));
      if (!itself && name.equals(existing.name)) {
        return "There is already a parameter named \"" + name + "\".";
      }
    }
    boolean noRange = type.equals("Text") || type.equals("Boolean");
    parameter.name = name;
    parameter.type = type;
    parameter.minVal = noRange ? "" : text(json, "minVal");
    parameter.maxVal = noRange ? "" : text(json, "maxVal");
    parameter.defaultVal = text(json, "defaultVal");
    parameter.description = text(json, "description");
    return null;
  }

  private static ObjectNode message(String text) {
    ObjectNode body = Json.newObject();
    body.put("message", text);
    return body;
  }

  /**
   * A value as text, whether it was sent as a string, a number or a boolean ({@code true} becomes
   * "true", {@code 5} becomes "5"); null when it is missing or null. It used to be read with
   * textValue(), which is null for anything but a string, so a Boolean parameter created with its
   * default untouched (the JSON boolean true) was stored with no default.
   */
  static String text(JsonNode json, String key) {
    JsonNode node = json.findPath(key);
    if (node == null || node.isMissingNode() || node.isNull()) {
      return null;
    }
    if (node.isValueNode()) {
      return node.asText();
    }
    return node.toString();
  }

  @Security.Authenticated(Secured.class)
  public static Result removeParameter(Long experimentId, Long parameterId) {
    Experiment experiment = Experiment.findById(experimentId);
    Parameter parameter = Parameter.find.byId(parameterId+"");
    if (parameter != null && experiment != null) {
      experiment.parameters.remove(parameter);
      experiment.update();
      parameter.delete();
    } else {
      return badRequest("Invalid experiment or parameter ID");
    }
    return ok();
  }
}
