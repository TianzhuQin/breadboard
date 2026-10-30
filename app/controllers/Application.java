package controllers;

import models.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mindrot.jbcrypt.BCrypt;
import play.Logger;
import play.data.Form;
import play.libs.Json;
import play.mvc.*;
import security.PreviewTokens;
import security.SetupCode;
import views.html.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class Application extends Controller {

  public static Result addFirstUser() {

    if (User.findRowCount() > 0) {
      // (Another tab, or someone else, created it first: the console then shows the sign-in page.)
      return badRequest("An account already exists on this server. Sign in instead.");
    }

    String email, password;
    Long defaultLanguageId;
    JsonNode json = request().body().asJson();
    if(json == null) {
      return badRequest("Expecting Json data");
    } else {
      email = json.findPath("email").textValue();
      password = json.findPath("password").textValue();
      defaultLanguageId = json.findPath("defaultLanguageId").longValue();
    }

    Language defaultLanguage = Language.findById(defaultLanguageId);
    if (defaultLanguage == null && email != null && password != null) {
      // (None named, or one from a list read before the server had its languages: English will do,
      // and it can be changed in Settings. Added now if the server has none yet.)
      try {
        defaultLanguage = LanguageController.english();
      } catch (RuntimeException e) {
        Logger.warn("Could not add the list of languages for the first account: " + e);
      }
      if (defaultLanguage == null) {
        return status(SERVICE_UNAVAILABLE, "The server is still setting up. Try again in a moment.");
      }
    }

    if (email == null || password == null || defaultLanguage == null) {
      return badRequest("Please provide email, password, and default language.");
    }
    // From another computer, the code the server printed (SetupCode): otherwise whoever reached a
    // new server first would own it.
    if (!SetupCode.isLocal(request())) {
      JsonNode given = json.get("setupCode");
      SetupCode.current();
      if (given == null || !SetupCode.matches(given.asText())) {
        ObjectNode refused = Json.newObject();
        refused.put("message", given == null || given.asText().trim().isEmpty()
            ? "Enter the setup code: the server printed it in its window and in logs/application.log."
            : "That isn't the setup code the server printed. Check its window or logs/application.log.");
        refused.put("setupCodeRequired", true);
        return forbidden(refused);
      }
    }
    email = email.trim();
    if (!User.looksLikeEmail(email)) {
      return badRequest("Enter a valid email address, like name@university.edu.");
    }
    if (password.length() < User.PASSWORD_MIN_LENGTH) {
      return badRequest("The password must be at least " + User.PASSWORD_MIN_LENGTH + " characters long.");
    }

    User user = new User();
    user.email = email;
    user.password = BCrypt.hashpw(password, BCrypt.gensalt());
    user.role = "admin";
    user.currentScript = "";
    user.defaultLanguage = defaultLanguage;
    user.experimentInstanceId = -1L;
    user.selectedExperiment = null;
    // A token nobody holds until the first sign-in (a missing one used to match a request with none).
    user.uid = UUID.randomUUID().toString();
    user.save();
    SetupCode.forget();

    // TODO: Make method for creating demo experiments and associating with new user
    /*
    Experiment experiment = Experiment.findById(321l);
    if (experiment != null) {
      user.ownedExperiments.add(experiment);
      user.update();
      user.saveManyToManyAssociations("ownedExperiments");
    }
    */
    return ok();
  }


  public static Result authenticate() {
    Form<Login> loginForm = Form.form(Login.class).bindFromRequest();
    ObjectNode result = Json.newObject();
    if (loginForm.hasErrors()) {
      result.put("message", "Invalid username or password");
      result.put("status", "error");
      return badRequest(result);
    } else {
      User user = User.findByEmail(loginForm.get().email);
      if (user == null) {
        return badRequest(login.render(loginForm));
      }
      // The account's current token, so that signing in on a second browser (a laptop and the lab's
      // computer, or two people sharing the lab's account) doesn't sign the first one out. Signing
      // out or changing the password replaces it, and so signs out every browser.
      String uid = user.uid;
      if (uid == null || uid.trim().isEmpty()) {
        uid = UUID.randomUUID().toString();
        user.uid = uid;
        user.update();
      }
      String juid = UUID.randomUUID().toString();
      session("email", user.email);
      session("uid", uid);
      session("juid", uid);
      result = Json.newObject();
      result.put("uid", uid);
      result.put("email", user.email);
      result.put("juid", juid);
      return ok(result);
    }
  }

  /**
   * Sign out. The account's uid (the token every console websocket frame carries, also readable from
   * /state) is replaced too, so an open console tab, or anyone who copied the token, can't keep
   * acting as this account; its participant-page previews stop working as well. Only when this
   * session's uid is still the account's: an older session signing out doesn't sign out a newer one.
   */
  public static Result logout() {
    String uid = session("uid");
    if (uid != null) {
      User user = User.findByUID(uid);
      if (user != null) {
        user.uid = UUID.randomUUID().toString();
        user.update();
        PreviewTokens.dropAccount(user.email);
        // Its open console sockets stop receiving the live network and participants' data too.
        ScriptBoard.closeAdminsOf(user.email, user.uid, null);
      }
    }
    session().clear();
    return unauthorized();
  }

  /** The researcher's console. */
  public static Result index () {
      return ok(admin.render(assetsRoot()));
  }

  /** The previous console, kept while researchers move to the new one. */
  public static Result legacyAdmin () {
      return ok(main.render(assetsRoot()));
  }

  private static String assetsRoot () {
      return play.Play.application().configuration().getString("breadboard.assetsRoot", "/assets");
  }

  @Security.Authenticated(Secured.class)
  public static Result getState() {
    ObjectNode result = Json.newObject();
    result.put("uid", session("uid")); // remove this
    result.put("juid", session("juid"));
    result.put("email", session("email"));
    // "auto" (or unset) lets the admin page connect back to the host and scheme it was loaded from,
    // instead of a hard-coded ws://localhost:9000 that breaks on any other host or port.
    String wsUrl = play.Play.application().configuration().getString("breadboard.wsUrl", "auto");
    result.put("connectSocket", wsUrl.isEmpty() ? "auto" : wsUrl);
    // Where computers on the same network (a lab's) reach this server, for the console to offer when
    // it was opened at localhost, whose links work on this computer only.
    com.fasterxml.jackson.databind.node.ArrayNode lan = result.putArray("lanAddresses");
    for (String address : lanAddresses(request().host())) {
      lan.add(address);
    }
    return ok(result);
  }

  /**
   * http://<address>:<port> for each IPv4 address of this computer on a private network (10.x,
   * 172.16-31.x, 192.168.x), with the port of {@code host} (the address the console was opened at).
   */
  static List<String> lanAddresses(String host) {
    String port = "";
    if (host != null) {
      int colon = host.lastIndexOf(':');
      if (colon > 0 && host.indexOf(']') < colon) {
        port = host.substring(colon);
      }
    }
    List<String> found = new ArrayList<String>();
    try {
      for (java.net.NetworkInterface nic : Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
        if (!nic.isUp() || nic.isLoopback() || nic.isVirtual() || nic.isPointToPoint()) {
          continue;
        }
        for (java.net.InetAddress address : Collections.list(nic.getInetAddresses())) {
          if (address instanceof java.net.Inet4Address && address.isSiteLocalAddress()) {
            String url = "http://" + address.getHostAddress() + port;
            if (!found.contains(url)) {
              found.add(url);
            }
          }
        }
      }
    } catch (Exception e) {
      Logger.debug("Could not list this computer's network addresses: " + e);
    }
    return found;
  }

  /**
   * The language the signed-in researcher writes new content in: JSON {"languageId": n}. 200, or
   * 400 when there is no such language.
   */
  @Security.Authenticated(Secured.class)
  public static Result saveDefaultLanguage() {
    User user = User.findByUID(session("uid"));
    JsonNode json = request().body().asJson();
    JsonNode id = json == null ? null : json.get("languageId");
    Language language = id == null || !id.canConvertToLong() ? null : Language.findById(id.asLong());
    if (user == null || language == null) {
      return badRequest("Choose one of the languages.");
    }
    user.defaultLanguage = language;
    user.update();
    return ok(user.toJson());
  }

  /** Shortest password accepted by the password change. */
  static final int PASSWORD_MIN_LENGTH = User.PASSWORD_MIN_LENGTH;

  /**
   * Change the signed-in account's password: {@code currentPassword}, {@code newPassword},
   * {@code confirmPassword} (form-encoded or JSON). Always 200: {"success": true}, or
   * {"success": false, "error": "<what is wrong>"}. A posted {@code email} must be the account's own:
   * it used to choose whose password was changed.
   */
  @Security.Authenticated(Secured.class)
  public static Result saveUserSettings() {
    ObjectNode result = Json.newObject();
    result.put("success", false);

    User user = User.findByUID(session("uid"));
    if (user == null) {
      result.put("error", "You are not signed in. Sign in again, then retry.");
      result.put("status", "unauthorized");
      return ok(result);
    }
    String email = field("email");
    String currentPassword = field("currentPassword");
    String newPassword = field("newPassword");
    String confirmPassword = field("confirmPassword");

    String error = null;
    if (email != null && !email.trim().isEmpty() && !email.trim().equalsIgnoreCase(user.email)) {
      error = "You can only change your own password";
    } else if (currentPassword == null || currentPassword.isEmpty()) {
      error = "Current password is missing";
    } else if (!user.passwordMatches(currentPassword)) {
      error = "Current password doesn't match your password";
    } else if (newPassword == null || newPassword.isEmpty()) {
      error = "New password is missing";
    } else if (newPassword.length() < PASSWORD_MIN_LENGTH) {
      error = "New password must be at least " + PASSWORD_MIN_LENGTH + " characters long";
    } else if (!newPassword.equals(confirmPassword)) {
      error = "Confirm Password doesn't match the new password";
    }
    if (error != null) {
      result.put("error", error);
      return ok(result);
    }

    user.changePassword(newPassword);
    // A new token too: whoever held the old one (another browser, a copied token) is signed out.
    // This session carries on with the new one, which is also returned: the console's websocket
    // must use it from now on (frames with the old one are refused). Previews ask for new tokens.
    String uid = UUID.randomUUID().toString();
    user.uid = uid;
    user.update();
    PreviewTokens.dropAccount(user.email);
    session("uid", uid);
    session("juid", uid);
    closeOldConsolesSoon(user.email, uid);
    result.put("success", true);
    result.put("uid", uid);
    return ok(result);
  }

  // How long the account's consoles have to move to the new token before the old ones are closed.
  static long CONSOLE_GRACE_MS = 3000L;

  /**
   * After a password change, close the account's console sockets that still use an old token
   * (ScriptBoard.closeAdminsOf), so a stolen token's socket stops receiving pushes. A little later,
   * not at once: the console that asked reconnects with the new token from this reply, and the
   * account's other tabs in this browser follow it; closing first raced the browser's cookie update,
   * and the tab could find itself signed out.
   */
  private static void closeOldConsolesSoon(final String email, final String uid) {
    play.libs.Akka.system().scheduler().scheduleOnce(
        scala.concurrent.duration.Duration.create(CONSOLE_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS),
        new Runnable() {
          public void run() {
            ScriptBoard.closeAdminsOf(email, uid, null);
          }
        },
        play.libs.Akka.system().dispatcher());
  }

  /** A field of a form-encoded or JSON request body, or null. Passwords are taken as typed (not trimmed). */
  private static String field(String name) {
    Map<String, String[]> form = request().body().asFormUrlEncoded();
    if (form != null) {
      String[] values = form.get(name);
      return values == null || values.length == 0 ? null : values[0];
    }
    JsonNode json = request().body().asJson();
    if (json != null) {
      JsonNode value = json.get(name);
      return value == null || value.isNull() ? null : value.asText();
    }
    return null;
  }

  /**
   * The experiment's sessions as CSV: id, name, status, creation time and every parameter value.
   * Every field is quoted, with quotes inside doubled (names and values used to go in as typed, so
   * one with a quote or a comma shifted the columns).
   */
  @Security.Authenticated(Secured.class)
  public static Result dataCsv(Long experimentId) {
    Experiment experiment = Experiment.findById(experimentId);
    if (experiment == null) {
      return badRequest("There is no experiment with id " + experimentId + ".");
    }
    Set<String> parameterNames = new TreeSet<>();
    for (ExperimentInstance instance : experiment.instances) {
      for (Data d : instance.data) {
        if (d.name != null) {
          parameterNames.add(d.name);
        }
      }
    }

    final StringBuilder csv = new StringBuilder();
    List<Object> header = new ArrayList<Object>(Arrays.asList("id", "instance", "status", "created_at"));
    header.addAll(parameterNames);
    csvRow(csv, header);

    for (ExperimentInstance instance : experiment.instances) {
      HashMap<String, String> dataMap = new HashMap<>();
      for (Data d : instance.data) {
        dataMap.put(d.name, d.value);
      }
      List<Object> row = new ArrayList<Object>();
      row.add(instance.id);
      row.add(instance.name);
      row.add(instance.status);
      row.add(instance.creationDate == null ? null : Timestamps.csv(instance.creationDate));
      for (String parameterName : parameterNames) {
        row.add(dataMap.get(parameterName));
      }
      csvRow(csv, row);
    }

    return ok(csv.toString());
  }

  /**
   * Every session's events of an experiment in one CSV (Sessions & data → Download all data): the
   * columns of a session's own file (id, event, event_date, data_name, data_value) after the session's
   * id, name and status and one column per launch parameter, so the rows of all sessions can be
   * analysed together. {@code ids}: only these sessions (comma-separated), e.g. the ones the page
   * lists; all of them when empty. Sessions oldest first, events in time order within each.
   */
  @Security.Authenticated(Secured.class)
  public static Result experimentDataCsv(Long experimentId, String ids) {
    Experiment experiment = Experiment.findById(experimentId);
    if (experiment == null) {
      return badRequest("There is no experiment with id " + experimentId + ".");
    }
    Set<Long> wanted = new HashSet<Long>();
    if (ids != null && !ids.trim().isEmpty()) {
      for (String part : ids.split(",")) {
        try {
          wanted.add(Long.parseLong(part.trim()));
        } catch (NumberFormatException notAnId) {
          return badRequest("\"" + part.trim() + "\" is not a session id.");
        }
      }
    }
    List<ExperimentInstance> sessions = new ArrayList<ExperimentInstance>();
    for (ExperimentInstance instance : experiment.instances) {
      if (wanted.isEmpty() || wanted.contains(instance.id)) {
        sessions.add(instance);
      }
    }
    Collections.sort(sessions, new Comparator<ExperimentInstance>() {
      @Override
      public int compare(ExperimentInstance a, ExperimentInstance b) {
        return Long.compare(a.id == null ? 0 : a.id, b.id == null ? 0 : b.id);
      }
    });
    Set<String> parameterNames = new TreeSet<>();
    for (ExperimentInstance instance : sessions) {
      for (Data d : instance.data) {
        if (d.name != null) {
          parameterNames.add(d.name);
        }
      }
    }

    final StringBuilder csv = new StringBuilder();
    List<Object> header = new ArrayList<Object>(Arrays.asList("session_id", "session_name", "session_status"));
    header.addAll(parameterNames);
    header.addAll(Arrays.asList("id", "event", "event_date", "data_name", "data_value"));
    csvRow(csv, header);
    for (ExperimentInstance instance : sessions) {
      List<Object> lead = new ArrayList<Object>(Arrays.<Object>asList(instance.id, instance.name, instance.status));
      HashMap<String, String> values = new HashMap<>();
      for (Data d : instance.data) {
        values.put(d.name, d.value);
      }
      for (String name : parameterNames) {
        lead.add(values.get(name));
      }
      for (Event event : eventsInOrder(instance)) {
        String when = event.datetime == null ? null : Timestamps.csv(event.datetime);
        if (event.eventData == null || event.eventData.isEmpty()) {
          List<Object> row = new ArrayList<Object>(lead);
          row.addAll(Arrays.<Object>asList(event.id, event.name, when, null, null));
          csvRow(csv, row);
          continue;
        }
        for (EventData eventData : event.eventData) {
          List<Object> row = new ArrayList<Object>(lead);
          row.addAll(Arrays.<Object>asList(event.id, event.name, when, eventData.name, eventData.value));
          csvRow(csv, row);
        }
      }
    }
    return ok(csv.toString());
  }

  /** A session's events by time; events in the same millisecond in the order they were recorded. */
  private static List<Event> eventsInOrder(ExperimentInstance instance) {
    List<Event> events = new ArrayList<Event>(instance.events);
    Collections.sort(events, new Comparator<Event>() {
      @Override
      public int compare(Event o1, Event o2) {
        int byTime = o1.datetime == null || o2.datetime == null
            ? Boolean.compare(o1.datetime != null, o2.datetime != null) : o1.datetime.compareTo(o2.datetime);
        return byTime != 0 ? byTime : Long.compare(o1.id == null ? 0 : o1.id, o2.id == null ? 0 : o2.id);
      }
    });
    return events;
  }

  /** One session's events as CSV: a row per event data item, every field quoted. */
  @Security.Authenticated(Secured.class)
  public static Result eventCsv(Long experimentInstanceId) {
    ExperimentInstance ei = ExperimentInstance.findById(experimentInstanceId);
    if (ei == null) {
      return badRequest("There is no session with id " + experimentInstanceId + ".");
    }

    final StringBuilder csv = new StringBuilder();
    csvRow(csv, Arrays.<Object>asList("id", "event", "event_date", "data_name", "data_value"));
    List<Event> events = new ArrayList<Event>(ei.events);
    Collections.sort(events, new Comparator<Event>() {
      @Override
      public int compare(Event o1, Event o2) {
        int byTime = o1.datetime == null || o2.datetime == null
            ? Boolean.compare(o1.datetime != null, o2.datetime != null) : o1.datetime.compareTo(o2.datetime);
        // Events in the same millisecond keep the order they were recorded in.
        return byTime != 0 ? byTime : Long.compare(o1.id == null ? 0 : o1.id, o2.id == null ? 0 : o2.id);
      }
    });
    for (Event event : events) {
      String when = event.datetime == null ? null : Timestamps.csv(event.datetime);
      if (event.eventData == null || event.eventData.isEmpty()) {
        // An event with no data (e.g. Finished) still gets its row, with empty data columns.
        csvRow(csv, Arrays.<Object>asList(event.id, event.name, when, null, null));
        continue;
      }
      for (EventData eventData : event.eventData) {
        csvRow(csv, Arrays.<Object>asList(event.id, event.name, when, eventData.name, eventData.value));
      }
    }

    return ok(csv.toString());
  }

  /** Append one CSV line: every field quoted, quotes inside doubled, null as an empty field. */
  static void csvRow(StringBuilder csv, List<?> fields) {
    for (int i = 0; i < fields.size(); i++) {
      if (i > 0) {
        csv.append(',');
      }
      csv.append(csvField(fields.get(i)));
    }
    csv.append('\n');
  }

  /**
   * One quoted field. The value is written exactly as recorded, with no spreadsheet-formula escaping
   * (no ' put before =, +, - or @): researchers analyse these files, so values must stay exact.
   */
  static String csvField(Object value) {
    String text = value == null ? "" : value.toString();
    return "\"" + text.replace("\"", "\"\"") + "\"";
  }

  /**
   * Handle the websocket.
   */
  public static WebSocket<JsonNode> connect() {
    WebSocket socket = new WebSocket<JsonNode>(){
      // Called when the Websocket Handshake is done.
      public void onReady(final WebSocket.In<JsonNode> in, final WebSocket.Out<JsonNode> out) {
        try {
          Breadboard.connect(in, out);
        } catch (Exception ex) {
          ex.printStackTrace();
        }
      }
    };
    return socket;
  }

  public static class Login {
    public String email;
    public String password;

    public String validate() {
      if (User.authenticate(email, password) == null) {
        return "Invalid user or password";
      }
      return null;
    }
  }

}
