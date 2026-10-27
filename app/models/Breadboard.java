package models;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tinkerpop.blueprints.Vertex;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.WordUtils;
import play.Logger;
import play.libs.*;
import play.libs.F.Callback;
import play.libs.F.Callback0;
import play.mvc.WebSocket;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * The admin console's websocket ({@code /connect}) and the actor that handles its account-level
 * actions. Engine actions go on to the account's {@link ScriptBoard} actor.
 *
 * <p>Every frame is a JSON object with {@code action} and {@code uid}. Replies go to the socket the
 * frame came from. A frame that can't be carried out (a missing key, an unknown id, an engine action
 * before LogIn) is answered with {@code {"error": "<what went wrong>", "request": "<action>"}} and
 * changes nothing. SendScript and SendStep may carry a {@code "requestId"} (text, at most 64
 * characters), and MakeChoice carries its {@code "choiceUID"}: every frame answering them carries it
 * back, so a console can tell its answer from everything else on the socket.
 */
public class Breadboard extends UntypedActor {
  // How often to send websocket messages in ms
  public static final long WEBSOCKET_RATE = 100L;

  // One script environment instance per user at this time, map the user's email address to the instance
  // Written by the Breadboard actor and the autopilot thread.
  static Map<String, ActorRef> instances = new java.util.concurrent.ConcurrentHashMap<String, ActorRef>();

  static ActorRef breadboardController = Akka.system().actorOf(new Props(Breadboard.class));

  private static final ObjectMapper mapper = new ObjectMapper();

  /**
   * A console socket opened. Everything read from it is answered on it: the reply socket used to be
   * one static field, replaced by every new connection, so replies went to whichever tab connected
   * last. Its Admin (registered on LogIn) is dropped when it closes.
   */
  public static void connect(final WebSocket.In<JsonNode> in, final WebSocket.Out<JsonNode> wsout) {
    final ThrottledWebSocketOut out = new ThrottledWebSocketOut(wsout, WEBSOCKET_RATE);
    in.onMessage(new Callback<JsonNode>() {
      public void invoke(JsonNode event) {
        receive(event, out);
      }
    });
    in.onClose(new Callback0() {
      public void invoke() {
        // Marked first: an AddAdmin still queued for this socket then registers nothing.
        out.markClosed();
        ScriptBoard.removeAdmin(out);
      }
    });
  }

  /** A request that can't be carried out; its message is sent back as {"error": ...}. */
  static final class BadRequest extends RuntimeException {
    BadRequest(String message) {
      super(message);
    }
  }

  /** {@code {"error": message, "request": action}} to one socket. */
  static void replyError(ThrottledWebSocketOut out, String request, String message) {
    replyError(out, request, message, null);
  }

  /** As above, with {@code "status": status} too when it is not null (e.g. "unauthorized"). */
  static void replyError(ThrottledWebSocketOut out, String request, String message, String status) {
    replyError(out, request, message, status, Collections.<String, String>emptyMap());
  }

  /** The error answering a message: its action as "request", and what it asks to have echoed. */
  static void replyError(BreadboardMessage answering, String message) {
    replyError(answering.out, requestName(answering), message, null, answering.echo());
  }

  /** As above, with the fields to echo (see {@link #echoed}) added. */
  static void replyError(ThrottledWebSocketOut out, String request, String message, String status, Map<String, String> echo) {
    if (out == null) {
      return;
    }
    ObjectNode error = Json.newObject();
    error.put("error", message);
    if (request != null) {
      error.put("request", request);
    }
    if (status != null) {
      error.put("status", status);
    }
    for (Map.Entry<String, String> field : echo.entrySet()) {
      error.put(field.getKey(), field.getValue());
    }
    try {
      out.write(error);
    } catch (Exception e) {
      Logger.debug("Could not send an error to a console: " + e);
    }
  }

  /** The action name a message stands for, for error replies. */
  static String requestName(BreadboardMessage message) {
    if (message instanceof ChangeExperiment) return "SelectExperiment";
    if (message instanceof AddAdmin || message instanceof Refresh) return "LogIn";
    if (message instanceof RunStep) return "SendStep";
    if (message instanceof HitCreated) return "SubmitAMTTask";
    return message.getClass().getSimpleName();
  }

  /** Longest "requestId" a console may send. */
  static final int MAX_REQUEST_ID = 64;

  /**
   * What a frame asks to have copied onto every frame answering it: its "requestId" for SendScript
   * and SendStep, its "choiceUID" for MakeChoice; nothing for other actions. A "requestId" that isn't
   * text of at most 64 characters is refused (the request is not carried out).
   */
  static Map<String, String> echoed(String action, Map<String, Object> json) {
    Map<String, String> echo = new LinkedHashMap<String, String>();
    if ("SendScript".equals(action) || "SendStep".equals(action)) {
      Object requestId = json.get("requestId");
      if (requestId != null) {
        if (!(requestId instanceof String) || ((String) requestId).length() > MAX_REQUEST_ID) {
          throw new BadRequest("\"requestId\" must be text of at most " + MAX_REQUEST_ID + " characters.");
        }
        echo.put("requestId", (String) requestId);
      }
    } else if ("MakeChoice".equals(action)) {
      String choiceUID = text(json, "choiceUID");
      if (choiceUID != null) {
        echo.put("choiceUID", choiceUID);
      }
    }
    return echo;
  }

  /** {"requestId": requestId}, or nothing when it is null. */
  static Map<String, String> requestIdEcho(String requestId) {
    return requestId == null ? Collections.<String, String>emptyMap() : Collections.singletonMap("requestId", requestId);
  }

  /** Read one frame from a console socket and act on it; never throws. */
  static void receive(JsonNode event, ThrottledWebSocketOut out) {
    String action = null;
    Map<String, String> echo = Collections.emptyMap();
    try {
      Logger.debug(event.toString());
      // TODO: check if there is a better way to do this with new version of Jackson
      Map<String, Object> jsonInput;
      try {
        jsonInput = mapper.readValue(event.toString(), Map.class);
      } catch (IOException notAnObject) {
        replyError(out, null, "The message could not be read: it must be a JSON object.");
        return;
      }
      action = text(jsonInput, "action");
      if (action == null) {
        replyError(out, null, "The message has no \"action\".");
        return;
      }
      echo = echoed(action, jsonInput);
      String uid = text(jsonInput, "uid");
      User user = uid == null ? null : User.findByUID(uid);
      if (user == null) {
        // A stale or forged admin session (reading user.uid here used to throw on every message).
        Logger.error("user not found with UID: " + uid);
        replyError(out, action, "You are not signed in (or your sign-in has expired). Sign in again.", "unauthorized", echo);
        return;
      }
      dispatch(action, jsonInput, user, echo, out);
    } catch (BadRequest bad) {
      replyError(out, action, bad.getMessage(), null, echo);
    } catch (Exception e) {
      Logger.error("Could not handle admin action " + action, e);
      replyError(out, action, "Something went wrong: " + ScriptBoardSupport.describeError(e), null, echo);
    }
  }

  // ---- reading a frame ------------------------------------------------------------------------

  /** The value as text (a number arrives as "5", an object as Map.toString()), or null when absent. */
  static String text(Map<String, Object> json, String key) {
    Object value = json.get(key);
    return value == null ? null : value.toString();
  }

  static String required(Map<String, Object> json, String key) {
    String value = text(json, key);
    if (value == null) {
      throw new BadRequest("\"" + key + "\" is missing.");
    }
    return value;
  }

  static Long requiredLong(Map<String, Object> json, String key) {
    Object value = json.get(key);
    if (value == null) {
      throw new BadRequest("\"" + key + "\" is missing.");
    }
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    try {
      return Long.parseLong(value.toString().trim());
    } catch (NumberFormatException nfe) {
      throw new BadRequest("\"" + key + "\" must be a number, not \"" + value + "\".");
    }
  }

  /** As requiredLong, or null when the key is absent. */
  static Long optionalLong(Map<String, Object> json, String key) {
    return json.get(key) == null ? null : requiredLong(json, key);
  }

  static Integer requiredInt(Map<String, Object> json, String key) {
    Long value = requiredLong(json, key);
    if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
      throw new BadRequest("\"" + key + "\" is too large.");
    }
    return value.intValue();
  }

  /** The account's engine actor; engine actions need LogIn to have opened it. */
  static ActorRef scriptBoard(User user) {
    ActorRef scriptBoard = instances.get(user.email);
    if (scriptBoard == null) {
      throw new BadRequest("This console hasn't logged in yet: send LogIn first.");
    }
    return scriptBoard;
  }

  private static void dispatch(String action, Map<String, Object> jsonInput, User user, Map<String, String> echo,
                               ThrottledWebSocketOut out) throws IOException {
    if (action.equals("LogIn")) {
      breadboardController.tell(new LogIn(user, out), null);
    } else if (action.equals("SaveUserSettings")) {
      // Missing fields are reported by the validation, as empty ones. Passwords are taken as typed:
      // trimming them stored a different password from the one typed.
      String currentPassword = StringUtils.defaultString(text(jsonInput, "currentPassword"));
      String newPassword = StringUtils.defaultString(text(jsonInput, "newPassword"));
      String confirmPassword = StringUtils.defaultString(text(jsonInput, "confirmPassword"));

      breadboardController.tell(new SaveUserSettings(user, currentPassword, newPassword, confirmPassword, out), null);

    } else if (action.equals("SelectExperiment")) {
      Long experimentId = requiredLong(jsonInput, "experimentId");
      Experiment experiment = Experiment.findById(experimentId);
      if (experiment == null) {
        throw new BadRequest("There is no experiment with id " + experimentId + ".");
      }
      breadboardController.tell(new SelectExperiment(user, experiment, out), null);
    } else if (action.equals("CreateExperiment")) {
      String name = text(jsonInput, "name");
      String copyExperimentName = text(jsonInput, "copyExperimentName");
      breadboardController.tell(new CreateExperiment(user, name, copyExperimentName, out), null);
      Logger.debug("CreateExperiment");
    } else if (action.equals("ImportExperiment")) {
      String importFrom = text(jsonInput, "importFrom");
      String importTo = text(jsonInput, "importTo");
      breadboardController.tell(new ImportExperiment(user, importFrom, importTo, out), null);
      Logger.debug("ImportExperiment");
    } else if (action.equals("DeleteExperiment")) {
      // By id (the new console); by name for the old one.
      Long experimentId = jsonInput.get("experimentId") == null ? null : requiredLong(jsonInput, "experimentId");
      String selectedExperimentName = text(jsonInput, "selectedExperiment");
      if (experimentId == null && selectedExperimentName == null) {
        throw new BadRequest("\"experimentId\" is missing.");
      }
      scriptBoard(user).tell(new DeleteExperiment(user, experimentId, selectedExperimentName, out), null);
    } else if (action.equals("ExportExperiment")) {
      Experiment experiment = null;
      if (jsonInput.get("experimentId") != null) {
        Long experimentId = requiredLong(jsonInput, "experimentId");
        for (Experiment e : user.ownedExperiments) {
          if (experimentId.equals(e.id)) {
            experiment = e;
          }
        }
      } else {
        experiment = user.getExperimentByName(required(jsonInput, "selectedExperiment"));
      }
      if (experiment == null) {
        throw new BadRequest("None of your experiments matches.");
      }
      ObjectNode jsonOutput = Json.newObject();
      try {
        experiment.export();
        jsonOutput.put("output", "Experiment exported.");
      } catch (IOException ioe) {
        jsonOutput.put("output", "Export failed.");
        Logger.error(ioe.getMessage());
      }
      out.write(jsonOutput);
    } else if (action.equals("SubmitAMTTask")) {
      Logger.debug("action.equals(\"SubmitAMTTask\")");
      // The submission to AMT is handled by the AMTAdmin createHIT route
      // This action handles setting the startAt global variable
      // and setting the timer to start initStep
      Integer lifetimeInSeconds = requiredInt(jsonInput, "lifetimeInSeconds");
      Integer tutorialTime = requiredInt(jsonInput, "tutorialTime");
      scriptBoard(user);
      breadboardController.tell(new SubmitAMTTask(user, lifetimeInSeconds, tutorialTime, out), null);
    } else if (action.equals("RunGame")) {
      scriptBoard(user).tell(new RunGame(user, out), null);
    } else if (action.equals("SendScript")) {
      String script = required(jsonInput, "script");
      // The experiment the console's page shows (the new console sends it): checked against the engine's.
      Long experimentId = optionalLong(jsonInput, "experimentId");
      scriptBoard(user).tell(new SendScript(user, script, experimentId, echo.get("requestId"), out), null);
    } else if (action.equals("AddLanguage")) {
      Long experimentId = requiredLong(jsonInput, "experimentId");
      String languageCode = required(jsonInput, "code");
      breadboardController.tell(new AddLanguage(user, experimentId, languageCode, out), null);
    } else if (action.equals("MakeChoice")) {
      // TODO: Player client will not be logged in with email and will
      // need a different way to identify the correct game
      String choiceUID = required(jsonInput, "choiceUID");
      String params = text(jsonInput, "params");
      scriptBoard(user).tell(new MakeChoice(user, choiceUID, params, out), null);
    } else if (action.equals("EditTie")) {
      // A researcher adding or cutting a tie from the admin network view. Refusals come back in the
      // same {"tieEdit": ...} shape as the outcome, so the console's wait for it ends either way.
      String op = text(jsonInput, "op");
      String a = text(jsonInput, "a");
      String b = text(jsonInput, "b");
      ActorRef scriptBoard = instances.get(user.email);
      if (op == null || a == null || b == null) {
        tieEditRefused(out, op, a, b, "An edit needs \"op\", \"a\" and \"b\".");
      } else if (scriptBoard == null) {
        tieEditRefused(out, op, a, b, "This console hasn't logged in yet.");
      } else {
        scriptBoard.tell(new EditTie(user, op, a, b, out), null);
      }
    } else if (action.equals("SaveStyle")) {
      String style = required(jsonInput, "style");
      breadboardController.tell(new SaveStyle(user, style, out), null);
    } else if (action.equals("SaveClientHtml")) {
      String clientHtml = required(jsonInput, "clientHtml");
      breadboardController.tell(new SaveClientHtml(user, clientHtml, out), null);
    } else if (action.equals("SaveClientGraph")) {
      String clientGraph = required(jsonInput, "clientGraph");
      breadboardController.tell(new SaveClientGraph(user, clientGraph, out), null);
    } else if (action.equals("CreateStep")) {
      String name = required(jsonInput, "name");
      breadboardController.tell(new CreateStep(user, name, out), null);
    } else if (action.equals("DeleteStep")) {
      Long id = requiredLong(jsonInput, "id");
      breadboardController.tell(new DeleteStep(user, id, out), null);
    } else if (action.equals("SendStep")) {
      // The source is what runs (a step not saved yet has no id, -1); a saved step's id is only used to
      // check that the step is of the experiment the engine has loaded.
      Long id = -1L;
      try {
        id = jsonInput.get("id") == null ? -1L : requiredLong(jsonInput, "id");
      } catch (BadRequest ignored) {
        Logger.debug("SendStep with a non-numeric id: " + jsonInput.get("id"));
      }
      String name = StringUtils.defaultString(text(jsonInput, "name"));
      String source = required(jsonInput, "source");
      Long experimentId = optionalLong(jsonInput, "experimentId");
      // TODO: is providing the name necessary?
      scriptBoard(user).tell(new SendStep(user, id, name, source, experimentId, echo.get("requestId"), out), null);
    } else if (action.equals("LaunchGame")) {
      String name = required(jsonInput, "name");
      // The experiment the console's page shows (the new console sends it): checked against the selection.
      Long experimentId = jsonInput.get("experimentId") == null ? null : requiredLong(jsonInput, "experimentId");
      Object parameters = jsonInput.get("parameters");
      if (parameters != null && !(parameters instanceof Map)) {
        throw new BadRequest("\"parameters\" must be an object of name: value.");
      }
      LinkedHashMap params = parameters == null ? new LinkedHashMap() : new LinkedHashMap((Map) parameters);
      scriptBoard(user).tell(new LaunchGame(user, experimentId, name, params, out), null);
    } else if (action.equals("StopGame")) {
      Long id = requiredLong(jsonInput, "id");
      scriptBoard(user).tell(new StopGame(user, id, out), null);
    } else if (action.equals("NewParameter")) {
      String name = required(jsonInput, "name");
      String type = required(jsonInput, "type");
      String minVal = StringUtils.defaultString(text(jsonInput, "minVal"));
      String maxVal = StringUtils.defaultString(text(jsonInput, "maxVal"));
      String defaultVal = StringUtils.defaultString(text(jsonInput, "defaultVal"));
      String description = StringUtils.defaultString(text(jsonInput, "description"));

      breadboardController.tell(new NewParameter(user, name, type, minVal, maxVal, defaultVal, description, out), null);
    } else if (action.equals("RemoveParameter")) {
      String id = required(jsonInput, "id");

      breadboardController.tell(new RemoveParameter(user, id, out), null);
    } else if (action.equals("SelectInstance")) {
      Long id = requiredLong(jsonInput, "id");
      scriptBoard(user);
      breadboardController.tell(new SelectInstance(user, id, out), null);
    } else if (action.equals("Update")) {
      breadboardController.tell(new Update(user, out), null);
    } else if (action.equals("ShowEvent")) {
      Long experimentInstanceId = requiredLong(jsonInput, "id");
      breadboardController.tell(new ShowEvent(user, experimentInstanceId, out), null);
    } else if (action.equals("DeleteInstance")) {
      Long experimentInstanceId = requiredLong(jsonInput, "id");
      breadboardController.tell(new DeleteInstance(user, experimentInstanceId, out), null);
    } else if (action.equals("ReloadEngine")) {
      scriptBoard(user).tell(new ReloadEngine(user, out), null);
    } else if (action.equals("DeleteImage")) {
      Long imageId = requiredLong(jsonInput, "imageId");
      breadboardController.tell(new DeleteImage(user, imageId, out), null);
    } else if (action.equals("ToggleFileMode")) {
      breadboardController.tell(new ToggleFileMode(user, out), null);
    } else {
      throw new BadRequest("Unknown action \"" + action + "\".");
    }
  }

  static void tieEditRefused(ThrottledWebSocketOut out, String op, String a, String b, String reason) {
    ObjectNode result = Json.newObject();
    result.put("op", op);
    result.put("a", a);
    result.put("b", b);
    result.put("ok", false);
    result.put("reason", reason);
    ObjectNode message = Json.newObject();
    message.put("tieEdit", result);
    out.write(message);
  }

  /**
   * What is wrong with a password change, in the order the checks run; empty when it may go ahead.
   * The current password must be the account's (checked against its hash).
   */
  static List<String> passwordChangeErrors(User user, String currentPassword, String newPassword, String confirmPassword) {
    List<String> errors = new ArrayList<String>();
    if (StringUtils.isEmpty(currentPassword)) {
      errors.add("'Current Password' is required");
    } else if (!user.passwordMatches(currentPassword)) {
      errors.add("'Current Password' doesn't match the current password");
    }
    if (StringUtils.isEmpty(newPassword)) {
      errors.add("'New Password' is required");
    } else if (newPassword.length() < User.PASSWORD_MIN_LENGTH) {
      // The same rule as the Settings page's /saveUserSettings.
      errors.add("'New Password' must be at least " + User.PASSWORD_MIN_LENGTH + " characters long");
    } else if (!newPassword.equals(confirmPassword)) {
      errors.add("'Confirm Password' doesn't match the new password");
    }
    return errors;
  }

  public void onReceive(Object message) throws Exception {
    try {
      handleMessage(message);
    } catch (Exception e) {
      Logger.error("Unhandled error in Breadboard.onReceive", e);
      if (message instanceof BreadboardMessage) {
        BreadboardMessage request = (BreadboardMessage) message;
        replyError(request, e instanceof BadRequest
            ? e.getMessage() : "Something went wrong: " + ScriptBoardSupport.describeError(e));
      }
    }
  }

  private void handleMessage(Object message) throws Exception {
    if (message instanceof BreadboardMessage) {
      BreadboardMessage breadboardMessage = (BreadboardMessage) message;
      Logger.debug("breadboardMessage.getClass().getName() = " + breadboardMessage.getClass().getName());

      if (message instanceof LogIn) {
        logIn(breadboardMessage.user, breadboardMessage.out);
        return;
      } else if (message instanceof SaveUserSettings) {
        SaveUserSettings saveUserSettings = (SaveUserSettings) message;

        //TODO: better validation like regex?
        List<String> errors = passwordChangeErrors(saveUserSettings.user, saveUserSettings.currentPassword,
            saveUserSettings.newPassword, saveUserSettings.confirmPassword);
        if (!errors.isEmpty()) {
          // (These checks used to be made and then ignored, and the new password stored as typed.)
          replyError(saveUserSettings.out, "SaveUserSettings", StringUtils.join(errors, ". ") + ".");
          return;
        }
        saveUserSettings.user.changePassword(saveUserSettings.newPassword);
        // As /saveUserSettings does: a new token, so whoever held the old one is signed out. That
        // includes this socket's later frames; the console signs in again with the new password.
        saveUserSettings.user.uid = UUID.randomUUID().toString();
        saveUserSettings.user.update();
        security.PreviewTokens.dropAccount(saveUserSettings.user.email);
        // The account's other console sockets stop receiving anything at once (this one is answered).
        ScriptBoard.closeAdminsOf(saveUserSettings.user.email, saveUserSettings.user.uid, saveUserSettings.out);

      } else if (message instanceof SelectExperiment) {
        // TODO: Clear the working memory of the ScriptEngine
        SelectExperiment selectExperiment = (SelectExperiment) message;
        Logger.debug("SelectExperiment: " + selectExperiment.experiment.toString());
        ActorRef scriptBoard = scriptBoard(breadboardMessage.user);

        breadboardMessage.user.setSelectedExperiment(selectExperiment.experiment);
        breadboardMessage.user.update();

        // If there are steps associated with this experiment, load them
        Experiment selectedExperiment = breadboardMessage.user.getExperiment();
        if (selectedExperiment != null) {
          // TODO: Create "ChangeExperiment" action and handle it in ScriptBoard
          // Find Content based on currently selected Experiment
          BreadboardMessage change = new ChangeExperiment(breadboardMessage.user, selectedExperiment, breadboardMessage.out);
          scriptBoard.tell(selectExperiment.atSignIn ? change.atSignIn() : change, null);
          //move the load steps into scriptboard
//                    for (Step step : selectedExperiment.steps)
//                        instances.get(breadboardMessage.user.email).tell(new RunStep(breadboardMessage.user, step.source, breadboardMessage.out));
        }
      } else if (message instanceof CreateExperiment) {
        Logger.error("Create experiment incorrectly sent via socket");
      } else if (message instanceof ImportExperiment) {
        Logger.error("Import experiment incorrectly sent via socket");
      } else if (message instanceof SubmitAMTTask) {
        SubmitAMTTask submitAMTTask = (SubmitAMTTask) message;
        // Its timer starts the engine's game: refused here, before it says the game will start.
        String refusal = ScriptBoard.engineRefusal(breadboardMessage.user);
        if (refusal != null) {
          throw new BadRequest(refusal);
        }
        Integer lifetimeInSeconds = submitAMTTask.lifetimeInSeconds;
        Integer tutorialTime = submitAMTTask.tutorialTime;

        scriptBoard(breadboardMessage.user).tell(new HitCreated(breadboardMessage.user, lifetimeInSeconds, tutorialTime, breadboardMessage.out), null);

        // Send 'initStep will automatically start' message to Output
        ObjectNode jsonOutput = Json.newObject();
        Double totalSeconds = (double)(lifetimeInSeconds + tutorialTime);
        Double minutes = Math.floor(totalSeconds / 60);
        Double seconds = totalSeconds - (minutes * 60);
        jsonOutput.put("output", "AMT HIT created, initStep will automatically start in " + minutes.toString() + " minutes and " + seconds.toString() + " seconds.");
        breadboardMessage.out.write(jsonOutput);

      } else if (message instanceof AddLanguage) {
        AddLanguage addLanguage = (AddLanguage) message;
        // Add the language to the experiment, if it doesn't already exist
        Experiment selectedExperiment = Experiment.findById(addLanguage.experimentId);
        if (selectedExperiment == null) {
          throw new BadRequest("There is no experiment with id " + addLanguage.experimentId + ".");
        }
        Language language = Language.find.where().eq("code", addLanguage.languageCode).findUnique();
        if (language == null) {
          // New language, create it
          language = new Language();
          language.code = addLanguage.languageCode;
          language.name = new Locale(addLanguage.languageCode).getDisplayLanguage();
        }
        boolean hasLanguage = false;
        for (Language l : selectedExperiment.languages) {
          if (l.id.equals(language.id)) {
            hasLanguage = true;
          }
        }
        if (!hasLanguage) {
          selectedExperiment.languages.add(language);
          selectedExperiment.save();
        }
        // (It used to be passed on to the ScriptBoard too, which has nothing to do with it.)
      } else if (message instanceof CreateStep) {
        CreateStep createStep = (CreateStep) message;
        Logger.debug("CreateStep: " + createStep.name);
        Experiment selectedExperiment = breadboardMessage.user.getExperiment();
        if (selectedExperiment != null) {
          Step newStep = new Step();
          newStep.name = createStep.name;
          String nameVariableName = WordUtils.uncapitalize(newStep.name.replaceAll("[^a-zA-Z0-9\\s]", ""));
          newStep.source = nameVariableName + " = stepFactory.createStep()\n\n" +
              nameVariableName + ".run = {\n" +
              "\tprintln \"" + nameVariableName + ".run\"\n" +
              "}\n\n" +
              nameVariableName + ".done = {\n" +
              "\tprintln \"" + nameVariableName + ".done\"\n" +
              "}\n";

          selectedExperiment.addStep(newStep);
          selectedExperiment.save();
        }
      } else if (message instanceof DeleteStep) {
        DeleteStep deleteStep = (DeleteStep) message;
        Step step = Step.find.byId(deleteStep.id);
        if (step == null) {
          throw new BadRequest("There is no step with id " + deleteStep.id + ".");
        }
        step.delete();
      } else if (message instanceof SaveStyle) {
        SaveStyle saveStyle = (SaveStyle) message;
        Experiment selectedExperiment = breadboardMessage.user.getExperiment();
        if (selectedExperiment != null) {
          Logger.debug("SaveStyle: " + saveStyle.style);
          selectedExperiment.setStyle(saveStyle.style);
          selectedExperiment.update();
          // Send "Style saved" message to output
          ObjectNode jsonOutput = Json.newObject();
          jsonOutput.put("output", "Style saved.");
          breadboardMessage.out.write(jsonOutput);
        }
      } else if (message instanceof SaveClientHtml) {
        SaveClientHtml saveClientHtml = (SaveClientHtml) message;
        Experiment selectedExperiment = breadboardMessage.user.getExperiment();
        if (selectedExperiment != null) {
          Logger.debug("SaveClientHtml: " + saveClientHtml.clientHtml);
          selectedExperiment.setClientHtml(saveClientHtml.clientHtml);
          selectedExperiment.update();
          ObjectNode jsonOutput = Json.newObject();
          jsonOutput.put("output", "Client HTML saved.");
          breadboardMessage.out.write(jsonOutput);
          // TODO: Consider pushing update to all clients here, to prevent need for browser refresh.
        }
      } else if (message instanceof SaveClientGraph) {
        SaveClientGraph saveClientGraph = (SaveClientGraph) message;
        Experiment selectedExperiment = breadboardMessage.user.getExperiment();
        if (selectedExperiment != null) {
          Logger.debug("SaveClientGraph: " + saveClientGraph.clientGraph);
          selectedExperiment.setClientGraph(saveClientGraph.clientGraph);
          selectedExperiment.update();
          ObjectNode jsonOutput = Json.newObject();
          jsonOutput.put("output", "Client Graph saved.");
          breadboardMessage.out.write(jsonOutput);
          // TODO: Consider pushing update to all clients here, to prevent need for browser refresh.
        }
      } else if (message instanceof NewParameter) {
        NewParameter newParameter = (NewParameter) message;

        Experiment selectedExperiment = breadboardMessage.user.getExperiment();

        if (selectedExperiment != null) {
          Parameter parameter = new Parameter();
          parameter.name = newParameter.name;
          parameter.type = newParameter.type;
          parameter.minVal = (parameter.type.equals("Text") || parameter.type.equals("Boolean")) ? "" : newParameter.minVal;
          parameter.maxVal = (parameter.type.equals("Text") || parameter.type.equals("Boolean")) ? "" : newParameter.maxVal;
          parameter.defaultVal = newParameter.defaultVal;
          parameter.description = newParameter.description;

          selectedExperiment.parameters.add(parameter);
          selectedExperiment.save();
        }
      } else if (message instanceof RemoveParameter) {
        RemoveParameter removeParameter = (RemoveParameter) message;

        Experiment selectedExperiment = breadboardMessage.user.getExperiment();

        if (selectedExperiment != null) {
          Parameter parameter = Parameter.find.byId(removeParameter.id);
          if (parameter != null) {
            selectedExperiment.parameters.remove(parameter);
            selectedExperiment.update();
            parameter.delete();
          }
        }
      } else if (message instanceof SelectInstance) {
        scriptBoard(breadboardMessage.user).tell(message, null);
      } else if (message instanceof Update) {
        //Do nothing, just update the user JSON object
      } else if (message instanceof ShowEvent) {
        ShowEvent showEvent = (ShowEvent) message;
        Long experimentInstanceId = showEvent.experimentInstanceId;
        ExperimentInstance experimentInstance = ExperimentInstance.findById(experimentInstanceId);
        if (experimentInstance == null) {
          throw new BadRequest("There is no session with id " + experimentInstanceId + ".");
        }
        List<Event> events = experimentInstance.events;
        Collections.sort(events, new Comparator<Event>() {
          @Override
          public int compare(Event o1, Event o2) {
            return o1.datetime.compareTo(o2.datetime);
          }
        });

        ObjectNode eventNode = Json.newObject();
        eventNode.put("eventExperimentInstance", experimentInstance.name);
        ArrayNode arrayNode = eventNode.putArray("events");

        for (Event event : events) {
          List<EventData> eventDatas = event.eventData;
          ObjectNode objectNode = Json.newObject();
          objectNode.put("id", event.id);
          objectNode.put("name", event.name);
          objectNode.put("datetime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,S").format(event.datetime));
          // The server's local time above has no zone; this one is UTC, as Event.toJson and live events send it.
          objectNode.put("datetimeUtc", Timestamps.iso(event.datetime));
          ArrayNode dataNodes = objectNode.putArray("data");
          for (EventData eventData : eventDatas) {
            ObjectNode dataNode = Json.newObject();
            dataNode.put("id", eventData.id);
            dataNode.put("name", eventData.name);
            dataNode.put("value", eventData.value);
            dataNodes.add(dataNode);
          }
          arrayNode.add(objectNode);
        }
        Logger.debug("breadboardMessage.out.write(eventNode); " + eventNode);
        breadboardMessage.out.write(eventNode);
        return;
      } else if (message instanceof DeleteInstance) {
        DeleteInstance deleteInstance = (DeleteInstance) message;
        Long experimentInstanceId = deleteInstance.experimentInstanceId;
        ExperimentInstance experimentInstance = ExperimentInstance.findById(experimentInstanceId);
        if (experimentInstance == null) {
          throw new BadRequest("There is no session with id " + experimentInstanceId + ".");
        }
        //only finished and stopped instance can be deleted
        if (experimentInstance.status == ExperimentInstance.Status.FINISHED || experimentInstance.status == ExperimentInstance.Status.STOPPED) {
          experimentInstance.delete();
        } else {
          throw new BadRequest("Only finished or stopped sessions can be deleted; stop it first.");
        }
      } else if (message instanceof DeleteImage) {
        DeleteImage deleteImage = (DeleteImage) message;
        Long imageId = deleteImage.imageId;
        Image image = Image.findById(imageId);
        if (image == null) {
          throw new BadRequest("There is no image with id " + imageId + ".");
        }
        image.delete();
      } else if (message instanceof ToggleFileMode) {
        Experiment selectedExperiment = breadboardMessage.user.getExperiment();
        if (selectedExperiment != null) {
          try {
            selectedExperiment.toggleFileMode(breadboardMessage.user);
          } catch (Exception e) {
            // e.g. the dev/ folder can't be written. The mode stays as it was; this used to be logged
            // only, and the console was answered as if the switch had worked.
            Logger.error("Could not switch file mode of " + selectedExperiment, e);
            String reason = e.getMessage() == null || e.getMessage().isEmpty() ? ScriptBoardSupport.describeError(e) : e.getMessage();
            replyError(breadboardMessage.out, "ToggleFileMode", "Couldn't switch file mode: " + reason);
            return;
          }
        }
      }

      ObjectNode userJson = breadboardMessage.user.toJson();
      breadboardMessage.out.write(userJson);
      if (message instanceof SelectExperiment || message instanceof ToggleFileMode
          || (message instanceof Update && ((Update) message).wholeAccount)) {
        // The account's other tabs show the same selection and session; one left behind would act on
        // what it still shows (e.g. launch another experiment than the one it shows).
        ScriptBoard.toOtherTabs(breadboardMessage.user.email, userJson, breadboardMessage.out);
      }

    } // END if(message instanceof BreadboardMessage)
  }

  /**
   * A console socket logged in (it does on every open, reconnects included). It gets the account's
   * User JSON, then, from the ScriptBoard actor, its own Admin and its own copy of the live network.
   *
   * <p>The engine is shared by every account. Only the first account in after the server starts
   * (before the autopilot or anyone else has opened an engine) has its saved work loaded, as before;
   * any other first login used to rebuild the engine too, which ended a session another account was
   * running.
   */
  private void logIn(User user, ThrottledWebSocketOut out) {
    ActorRef scriptBoard = instances.get(user.email);
    boolean firstIntoEngine = false;
    boolean firstSinceStart = scriptBoard == null;
    if (scriptBoard == null) {
      // instances is filled synchronously (here and by the autopilot) before any engine is built.
      firstIntoEngine = instances.isEmpty() && !ScriptBoard.hasEngine();
      // TODO: Allow multiple instances per user?
      scriptBoard = Akka.system().actorOf(new Props(ScriptBoard.class));
      Logger.debug("breadboardMessage.user.email PUT: " + user.email);
      instances.put(user.email, scriptBoard);
    }

    // User JSON first, then the network (the ScriptBoard actor writes it after this).
    out.write(user.toJson());
    // Registered now, not only when the engine actor gets to it (it may be busy for seconds), so
    // this tab already hears of changes made in the account's other tabs.
    ScriptBoard.registerAdmin(user, scriptBoard, out);
    scriptBoard.tell(new AddAdmin(user, scriptBoard, out), null);

    if (firstIntoEngine) {
      // Load the account's saved work, rebuilding the engine once: its running session if it has
      // one (SelectInstance rebuilds for its experiment and reloads its steps), else its experiment,
      // else an empty engine (what creating the ScriptBoard used to build). A stopped or finished
      // session is no longer restarted by logging in; it stays selected.
      ExperimentInstance saved = (user.experimentInstanceId == null || user.experimentInstanceId == -1)
          ? null : ExperimentInstance.findById(user.experimentInstanceId);
      boolean running = saved != null && (saved.status == ExperimentInstance.Status.RUNNING
          || saved.status == ExperimentInstance.Status.TESTING);
      if (running) {
        getSelf().tell(new SelectInstance(user, saved.id, out).atSignIn(), null);
      } else if (user.selectedExperiment != null) {
        Logger.debug("user.selectedExperiment = " + user.selectedExperiment);
        getSelf().tell(new SelectExperiment(user, user.selectedExperiment, out).atSignIn(), null);
      } else {
        scriptBoard.tell(new ReloadEngine(user, out).atSignIn(), null);
      }
    } else if (firstSinceStart) {
      // Someone else (or the autopilot) has the engine: a session this account left running before
      // the restart isn't taken up, and nothing runs it any more. Marked stopped, so the Sessions list
      // and the participant links don't offer it as open.
      ExperimentInstance saved = (user.experimentInstanceId == null || user.experimentInstanceId == -1)
          ? null : ExperimentInstance.findById(user.experimentInstanceId);
      if (saved != null && (saved.status == ExperimentInstance.Status.RUNNING || saved.status == ExperimentInstance.Status.TESTING)
          && !ScriptBoard.isEngineSession(saved.id)) {
        Logger.info("LogIn: session " + saved.id + " of " + user.email + " is no longer running in the engine; marking it stopped");
        ScriptBoard.stopQuietly(saved.id);
      }
    }
    Logger.debug("LogIn: " + user.email);
  }

  public static abstract class BreadboardMessage {
    final User user;
    final ThrottledWebSocketOut out;
    // Set on what LogIn sends to load the account's saved work after a restart (see logIn): the
    // engine that builds is not one the researcher asked for, so the autopilot may take it over
    // (ScriptBoard.researcherHolding).
    boolean atSignIn = false;

    public BreadboardMessage(User user, ThrottledWebSocketOut out) {
      this.user = user;
      this.out = out;
    }

    /** Fields copied onto every frame answering this request (see Breadboard.echoed); none by default. */
    Map<String, String> echo() {
      return Collections.emptyMap();
    }

    BreadboardMessage atSignIn() {
      this.atSignIn = true;
      return this;
    }
  }

  public static class LogIn extends BreadboardMessage {
    public LogIn(User user, ThrottledWebSocketOut out) {
      super(user, out);
    }
  }

  public static class Refresh extends BreadboardMessage {
    public Refresh(User user, ThrottledWebSocketOut out) {
      super(user, out);
    }
  }

  public static class Update extends BreadboardMessage {
    // After a change to the account (its selection, its session): its other tabs get the User JSON too.
    final boolean wholeAccount;

    public Update(User user, ThrottledWebSocketOut out) {
      this(user, out, false);
    }

    public Update(User user, ThrottledWebSocketOut out, boolean wholeAccount) {
      super(user, out);
      this.wholeAccount = wholeAccount;
    }
  }

  public static class SaveUserSettings extends BreadboardMessage {

    final String currentPassword;
    final String newPassword;
    final String confirmPassword;

    public SaveUserSettings(User user, String currentPassword, String newPassword, String confirmPassword, ThrottledWebSocketOut out) {
      super(user, out);
      this.currentPassword = currentPassword;
      this.newPassword = newPassword;
      this.confirmPassword = confirmPassword;
    }
  }

  public static class SelectExperiment extends BreadboardMessage {
    final Experiment experiment;

    public SelectExperiment(User user, Experiment experiment, ThrottledWebSocketOut out) {
      super(user, out);
      this.experiment = experiment;
    }
  }

  public static class DeleteExperiment extends BreadboardMessage {
    // The id when given (preferred); otherwise the name, as the old console sends it.
    final Long experimentId;
    final String experimentName;

    public DeleteExperiment(User user, String experimentName, ThrottledWebSocketOut out) {
      this(user, null, experimentName, out);
    }

    public DeleteExperiment(User user, Long experimentId, String experimentName, ThrottledWebSocketOut out) {
      super(user, out);
      this.experimentId = experimentId;
      this.experimentName = experimentName;
    }
  }

  public static class ExportExperiment extends BreadboardMessage {
    final String experimentName;

    public ExportExperiment(User user, String experimentName, ThrottledWebSocketOut out) {
      super(user, out);
      this.experimentName = experimentName;
    }
  }

  public static class ChangeExperiment extends BreadboardMessage {
    final Experiment experiment;

    public ChangeExperiment(User user, Experiment experiment, ThrottledWebSocketOut out) {
      super(user, out);
      this.experiment = experiment;
    }
  }

  public static class CreateExperiment extends BreadboardMessage {
    final String name;
    final String copyExperimentName;

    public CreateExperiment(User user, String name, String copyExperimentName, ThrottledWebSocketOut out) {
      super(user, out);
      this.name = name;
      this.copyExperimentName = copyExperimentName;
    }
  }

  public static class ImportExperiment extends BreadboardMessage {
    final String importFrom;
    final String importTo;

    public ImportExperiment(User user, String importFrom, String importTo, ThrottledWebSocketOut out) {
      super(user, out);
      this.importFrom = importFrom;
      this.importTo = importTo;
    }
  }

  public static class SubmitAMTTask extends BreadboardMessage {
    final Integer lifetimeInSeconds;
    final Integer tutorialTime;

    public SubmitAMTTask(User user, Integer lifetimeInSeconds, Integer tutorialTime, ThrottledWebSocketOut out) {
      super(user, out);
      this.lifetimeInSeconds = lifetimeInSeconds;
      this.tutorialTime = tutorialTime;
    }
  }

  public static class HitCreated extends BreadboardMessage {
    final Integer lifetimeInSeconds;
    final Integer tutorialTime;

    public HitCreated(User user, Integer lifetimeInSeconds, Integer tutorialTime, ThrottledWebSocketOut out) {
      super(user, out);
      this.lifetimeInSeconds = lifetimeInSeconds;
      this.tutorialTime = tutorialTime;
    }
  }

  public static class AddLanguage extends BreadboardMessage {
    final Long experimentId;
    final String languageCode;

    public AddLanguage(User user, Long experimentId, String languageCode, ThrottledWebSocketOut out) {
      super(user, out);
      this.experimentId = experimentId;
      this.languageCode = languageCode;
    }
  }

  public static class CreateStep extends BreadboardMessage {
    final String name;

    public CreateStep(User user, String name, ThrottledWebSocketOut out) {
      super(user, out);
      this.name = name;
    }
  }

  public static class DeleteStep extends BreadboardMessage {
    final Long id;

    public DeleteStep(User user, Long id, ThrottledWebSocketOut out) {
      super(user, out);
      this.id = id;
    }
  }

  public static class SaveStyle extends BreadboardMessage {
    final String style;

    public SaveStyle(User user, String style, ThrottledWebSocketOut out) {
      super(user, out);
      this.style = style;
    }
  }

  public static class SaveClientHtml extends BreadboardMessage {
    final String clientHtml;

    public SaveClientHtml(User user, String clientHtml, ThrottledWebSocketOut out) {
      super(user, out);
      this.clientHtml = clientHtml;
    }
  }

  public static class SaveClientGraph extends BreadboardMessage {
    final String clientGraph;

    public SaveClientGraph(User user, String clientGraph, ThrottledWebSocketOut out) {
      super(user, out);
      this.clientGraph = clientGraph;
    }
  }

  public static class RunGame extends BreadboardMessage {
    public RunGame(User user, ThrottledWebSocketOut out) {
      super(user, out);
    }
  }

  public static class SendScript extends BreadboardMessage {
    final String script;
    // The experiment the console showed when it sent the script, or null when it didn't say: nothing
    // runs when the engine has another one loaded.
    final Long experimentId;
    final String requestId;

    public SendScript(User user, String script, ThrottledWebSocketOut out) {
      this(user, script, null, null, out);
    }

    public SendScript(User user, String script, Long experimentId, String requestId, ThrottledWebSocketOut out) {
      super(user, out);
      this.script = script;
      this.experimentId = experimentId;
      this.requestId = requestId;
    }

    @Override
    Map<String, String> echo() {
      return requestIdEcho(requestId);
    }
  }

  public static class SendStep extends BreadboardMessage {
    // -1 for a step not saved yet
    final Long id;
    final String name;
    final String source;
    // As SendScript's
    final Long experimentId;
    final String requestId;

    public SendStep(User user, Long id, String name, String source, ThrottledWebSocketOut out) {
      this(user, id, name, source, null, null, out);
    }

    public SendStep(User user, Long id, String name, String source, Long experimentId, String requestId, ThrottledWebSocketOut out) {
      super(user, out);
      this.id = id;
      this.name = name;
      this.source = source;
      this.experimentId = experimentId;
      this.requestId = requestId;
    }

    @Override
    Map<String, String> echo() {
      return requestIdEcho(requestId);
    }
  }

  /** Sent by a ScriptBoard to itself after the steps it queued have run (see AutopilotHooks). */
  public static class StepsLoaded extends BreadboardMessage {
    final Experiment experiment;
    // The engine it was queued for (ScriptBoard.engineGeneration); dropped once another has been
    // built. Null: whichever engine runs then.
    final Long generation;

    public StepsLoaded(User user, Experiment experiment, ThrottledWebSocketOut out) {
      this(user, experiment, out, null);
    }

    public StepsLoaded(User user, Experiment experiment, ThrottledWebSocketOut out, Long generation) {
      super(user, out);
      this.experiment = experiment;
      this.generation = generation;
    }
  }

  public static class RunStep extends BreadboardMessage {
    final String source;
    // The console request it answers ("SendStep"), sent back as "request" on its frames; null for
    // steps that load with an experiment, whose frames carry "source": "Steps" instead.
    final String request;
    // As StepsLoaded's: the engine it was queued for, or null.
    final Long generation;
    // The requestId of the SendStep it answers, or null.
    final String requestId;

    public RunStep(User user, String source, ThrottledWebSocketOut out) {
      this(user, source, out, null);
    }

    public RunStep(User user, String source, ThrottledWebSocketOut out, String request) {
      this(user, source, out, request, null);
    }

    public RunStep(User user, String source, ThrottledWebSocketOut out, String request, Long generation) {
      this(user, source, out, request, generation, null);
    }

    public RunStep(User user, String source, ThrottledWebSocketOut out, String request, Long generation, String requestId) {
      super(user, out);
      this.source = source;
      this.request = request;
      this.generation = generation;
      this.requestId = requestId;
    }

    @Override
    Map<String, String> echo() {
      return requestIdEcho(requestId);
    }
  }

  public static class MakeChoice extends BreadboardMessage {
    final String uid;
    final String params;

    public MakeChoice(User user, String uid, String params, ThrottledWebSocketOut out) {
      super(user, out);
      this.uid = uid;
      this.params = params;
    }

    @Override
    Map<String, String> echo() {
      return uid == null ? Collections.<String, String>emptyMap() : Collections.singletonMap("choiceUID", uid);
    }
  }

  public static class EditTie extends BreadboardMessage {
    final String op;
    final String a;
    final String b;

    public EditTie(User user, String op, String a, String b, ThrottledWebSocketOut out) {
      super(user, out);
      this.op = op;
      this.a = a;
      this.b = b;
    }
  }

  public static class LaunchGame extends BreadboardMessage {
    // The experiment the console showed when Launch was pressed; null when it didn't say.
    final Long experimentId;
    final String name;
    final LinkedHashMap parameters;
    // Set only by the autopilot: the experiment to launch, instead of the account's selection (which
    // it leaves alone), and how Autopilot.launchStillWanted checks the launch is still wanted.
    final Experiment autopilotExperiment;
    final boolean onlyIfIdle;
    final boolean automatic;

    public LaunchGame(User user, String name, LinkedHashMap parameters, ThrottledWebSocketOut out) {
      this(user, null, name, parameters, out);
    }

    public LaunchGame(User user, Long experimentId, String name, LinkedHashMap parameters, ThrottledWebSocketOut out) {
      super(user, out);
      this.experimentId = experimentId;
      this.name = name;
      this.parameters = parameters;
      this.autopilotExperiment = null;
      this.onlyIfIdle = false;
      this.automatic = false;
    }

    /** The autopilot's launch of {@code experiment}, as {@code user} (its operator). */
    LaunchGame(User user, Experiment experiment, String name, LinkedHashMap parameters, ThrottledWebSocketOut out,
               boolean onlyIfIdle, boolean automatic) {
      super(user, out);
      this.experimentId = null;
      this.name = name;
      this.parameters = parameters;
      this.autopilotExperiment = experiment;
      this.onlyIfIdle = onlyIfIdle;
      this.automatic = automatic;
    }
  }

  public static class StopGame extends BreadboardMessage {
    final Long id;

    public StopGame(User user, Long id, ThrottledWebSocketOut out) {
      super(user, out);
      this.id = id;
    }
  }

  public static class NewParameter extends BreadboardMessage {
    final String name;
    final String type;
    final String minVal;
    final String maxVal;
    final String defaultVal;
    final String description;

    public NewParameter(User user, String name, String type, String minVal, String maxVal, String defaultVal, String description, ThrottledWebSocketOut out) {
      super(user, out);
      this.name = name;
      this.type = type;
      this.minVal = minVal;
      this.maxVal = maxVal;
      this.defaultVal = defaultVal;
      this.description = description;
    }
  }

  public static class RemoveParameter extends BreadboardMessage {
    final String id;

    public RemoveParameter(User user, String id, ThrottledWebSocketOut out) {
      super(user, out);
      this.id = id;
    }
  }

  public static class SelectInstance extends BreadboardMessage {
    final Long id;

    public SelectInstance(User user, Long id, ThrottledWebSocketOut out) {
      super(user, out);
      this.id = id;
    }
  }

  public static class AddAdmin extends BreadboardMessage {
    final ActorRef scriptBoardController;

    public AddAdmin(User user, ActorRef scriptBoardController, ThrottledWebSocketOut out) {
      super(user, out);
      this.scriptBoardController = scriptBoardController;
    }
  }

  public static class RunOnJoinStep extends BreadboardMessage {
    final Vertex vertex;

    public RunOnJoinStep(User user, Vertex vertex, ThrottledWebSocketOut out) {
      super(user, out);
      this.vertex = vertex;
    }
  }

  public static class RunOnLeaveStep extends BreadboardMessage {
    final Vertex vertex;

    public RunOnLeaveStep(User user, Vertex vertex, ThrottledWebSocketOut out) {
      super(user, out);
      this.vertex = vertex;
    }
  }

  public static class ShowEvent extends BreadboardMessage {
    final Long experimentInstanceId;

    public ShowEvent(User user, Long experimentInstanceId, ThrottledWebSocketOut out) {
      super(user, out);
      this.experimentInstanceId = experimentInstanceId;
    }
  }

  public static class GameFinish extends BreadboardMessage {
    public GameFinish(User user, ThrottledWebSocketOut out) {
      super(user, out);
    }
  }

  public static class DeleteInstance extends BreadboardMessage {

    final Long experimentInstanceId;

    public DeleteInstance(User user, Long experimentInstanceId, ThrottledWebSocketOut out) {
      super(user, out);
      this.experimentInstanceId = experimentInstanceId;
    }
  }

  public static class ReloadEngine extends BreadboardMessage {
    public ReloadEngine(User user, ThrottledWebSocketOut out) {
      super(user, out);
    }
  }

  public static class ToggleFileMode extends BreadboardMessage {
    public ToggleFileMode(User user, ThrottledWebSocketOut out) {
      super(user, out);
    }
  }

  public static class DeleteImage extends BreadboardMessage {
    final Long imageId;

    public DeleteImage(User user, Long imageId, ThrottledWebSocketOut out) {
      super(user, out);
      this.imageId = imageId;
    }
  }

}
