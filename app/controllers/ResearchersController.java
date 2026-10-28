package controllers;

import com.avaje.ebean.Ebean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.ScriptBoard;
import models.User;
import org.mindrot.jbcrypt.BCrypt;
import play.Logger;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security;
import security.PreviewTokens;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/**
 * The researcher accounts of this server (the console's Settings → Researchers). Every account works
 * on every experiment (User.experiments), so any signed-in researcher may add one, remove another,
 * or give another a new password. New and reset passwords are made here and returned once, for the
 * researcher to pass on; the account changes it in Settings after signing in.
 */
@Security.Authenticated(Secured.class)
public class ResearchersController extends Controller {

  /** {"researchers": [{"email", "name", "you"}]}, by email. */
  public static Result list() {
    User me = User.findByUID(session("uid"));
    return ok(listJson(me));
  }

  /**
   * Add an account: {"email", "name"?}. 200 {"email", "password", "researchers"} with the new account's
   * first password (shown once), or 400 with what is wrong.
   */
  public static Result add() {
    User me = User.findByUID(session("uid"));
    String email = field("email");
    if (me == null) {
      return unauthorized(message("You are not signed in any more. Sign in again, then retry."));
    }
    if (email == null || !User.looksLikeEmail(email.trim())) {
      return badRequest(message("Enter their email address, like name@university.edu."));
    }
    email = email.trim();
    if (User.findByEmail(email) != null) {
      return badRequest(message(email + " already has an account."));
    }
    String name = field("name");
    String password = newPassword();
    User user = new User();
    user.email = email;
    user.name = name == null || name.trim().isEmpty() ? email : name.trim();
    user.password = BCrypt.hashpw(password, BCrypt.gensalt());
    user.role = "admin";
    user.currentScript = "";
    user.defaultLanguage = me.defaultLanguage;
    user.experimentInstanceId = -1L;
    // A token nobody holds until the first sign-in.
    user.uid = UUID.randomUUID().toString();
    user.save();
    Logger.info("Researchers: " + me.email + " added the account " + email);
    ObjectNode result = listJson(me);
    result.put("email", email);
    result.put("password", password);
    return ok(result);
  }

  /** Remove an account: {"email"}. Not your own (so never the last one). 200 {"researchers"}, or 400. */
  public static Result remove() {
    User me = User.findByUID(session("uid"));
    if (me == null) {
      return unauthorized(message("You are not signed in any more. Sign in again, then retry."));
    }
    User user = User.findByEmail(field("email"));
    if (user == null) {
      return badRequest(message("There is no such account."));
    }
    if (user.email.equals(me.email)) {
      return badRequest(message("You can't remove your own account. Another researcher can."));
    }
    String email = user.email;
    // Its experiments stay (they belong to everyone); only the account's own rows go.
    Ebean.createSqlUpdate("delete from users_experiments where users_email = :email")
        .setParameter("email", email).execute();
    Ebean.createSqlUpdate("delete from users where email = :email")
        .setParameter("email", email).execute();
    PreviewTokens.dropAccount(email);
    ScriptBoard.closeAdminsOf(email, null, null,
        "This account was removed by another researcher. Ask them for a new one if you still need it.");
    Logger.info("Researchers: " + me.email + " removed the account " + email);
    return ok(listJson(me));
  }

  /**
   * Give another account a new password: {"email"}. 200 {"email", "password"} (shown once), or 400.
   * Every browser signed in to it is signed out. Your own password is changed under Password.
   */
  public static Result resetPassword() {
    User me = User.findByUID(session("uid"));
    if (me == null) {
      return unauthorized(message("You are not signed in any more. Sign in again, then retry."));
    }
    User user = User.findByEmail(field("email"));
    if (user == null) {
      return badRequest(message("There is no such account."));
    }
    if (user.email.equals(me.email)) {
      return badRequest(message("Change your own password under Password, above."));
    }
    String password = newPassword();
    user.changePassword(password);
    user.uid = UUID.randomUUID().toString();
    user.update();
    PreviewTokens.dropAccount(user.email);
    ScriptBoard.closeAdminsOf(user.email, user.uid, null,
        "Another researcher gave this account a new password. Ask them for it, then sign in again.");
    Logger.info("Researchers: " + me.email + " gave " + user.email + " a new password");
    ObjectNode result = Json.newObject();
    result.put("email", user.email);
    result.put("password", password);
    return ok(result);
  }

  private static ObjectNode listJson(User me) {
    ObjectNode result = Json.newObject();
    ArrayNode list = result.putArray("researchers");
    List<User> users = User.find.orderBy("email").findList();
    for (User u : users) {
      ObjectNode row = list.addObject();
      row.put("email", u.email);
      row.put("name", u.name == null || u.name.isEmpty() ? u.email : u.name);
      row.put("you", me != null && u.email.equals(me.email));
    }
    return result;
  }

  /** 14 letters and digits, without the ones that look alike (l, 1, O, 0). */
  static String newPassword() {
    String alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    SecureRandom random = new SecureRandom();
    StringBuilder password = new StringBuilder();
    for (int i = 0; i < 14; i++) {
      password.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return password.toString();
  }

  private static String field(String name) {
    JsonNode json = request().body().asJson();
    if (json != null) {
      JsonNode value = json.get(name);
      return value == null || value.isNull() ? null : value.asText();
    }
    java.util.Map<String, String[]> form = request().body().asFormUrlEncoded();
    String[] values = form == null ? null : form.get(name);
    return values == null || values.length == 0 ? null : values[0];
  }

  private static ObjectNode message(String text) {
    ObjectNode result = Json.newObject();
    result.put("message", text);
    return result;
  }
}
