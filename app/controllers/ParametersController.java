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
    String name, type, minVal, maxVal, defaultVal, description;
    JsonNode json = request().body().asJson();
    if(json == null) {
      return badRequest("Expecting Json data");
    } else {
      name = text(json, "name");
      type = text(json, "type");
      minVal = text(json, "minVal");
      maxVal = text(json, "maxVal");
      defaultVal = text(json, "defaultVal");
      description = text(json, "description");
    }

    if (name == null || name.trim().isEmpty()) {
      return badRequest("Please provide a name for the parameter.");
    }
    name = name.trim();
    if (type == null || !TYPES.contains(type)) {
      return badRequest("Please provide the parameter's type: Integer, Decimal, Text or Boolean.");
    }
    for (Parameter existing : experiment.parameters) {
      if (name.equals(existing.name)) {
        return badRequest("There is already a parameter named \"" + name + "\".");
      }
    }

    Parameter parameter = new Parameter();
    parameter.name = name;
    parameter.type = type;
    parameter.minVal = (type.equals("Text") || type.equals("Boolean")) ? "" : minVal;
    parameter.maxVal = (type.equals("Text") || type.equals("Boolean")) ? "" : maxVal;
    parameter.defaultVal = defaultVal;
    parameter.description = description;

    experiment.parameters.add(parameter);
    experiment.save();

    ObjectNode returnJson = Json.newObject();
    returnJson.put("parameter", parameter.toJson());
    return ok(returnJson);
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
