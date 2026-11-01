package controllers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import models.User;
import play.mvc.Http.Context;
import play.mvc.Result;
import play.mvc.Security;
import play.libs.Json;

public class Secured extends Security.Authenticator {
  // The sign-ins another researcher's New password ended (ResearchersController.resetPassword), by the
  // uid their browsers still hold, so that the sign-in page says so ("reset"), as it does when the
  // account was removed, rather than that it signed out somewhere. In memory, the newest
  // RESETS_KEPT, until the server restarts (then it says "ended", which is true too).
  static final int RESETS_KEPT = 1000;
  private static final Map<String, Boolean> RESETS = Collections.synchronizedMap(new LinkedHashMap<String, Boolean>() {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
      return size() > RESETS_KEPT;
    }
  });

  /** Another researcher gave the account a new password, which ended the sign-ins that hold {@code uid}. */
  public static void passwordReset(String uid) {
    if (uid != null && !uid.trim().isEmpty()) {
      RESETS.put(uid, Boolean.TRUE);
    }
  }

  @Override
  public String getUsername(Context ctx) {
    String uid = ctx.session().get("uid");
    // No sign-in: a lookup by a null uid matched an account that had never signed in.
    if (uid == null || uid.trim().isEmpty()) {
      return null;
    }
    User user = User.findByUID(uid);
    if(user != null) {
      return user.email;
    } else {
      return null;
    }
  }

  @Override
  public Result onUnauthorized(Context ctx) {
    ObjectNode result = Json.newObject();
    if (User.findRowCount() == 0) {
      result.put("status", "create-first-user");
      // From another computer the first account needs the code the server prints (SetupCode).
      boolean local = security.SetupCode.isLocal(ctx.request());
      result.put("setupCodeRequired", !local);
      if (!local) {
        security.SetupCode.current();
      }
    } else {
      result.put("status", "unauthorized");
      // This browser was signed in, but the account has signed out somewhere else since (or changed
      // its password), which ended this session: the console says so.
      String uid = ctx.session().get("uid");
      if (uid != null && !uid.trim().isEmpty()) {
        // ... or another researcher removed the account, or gave it a new password.
        String email = ctx.session().get("email");
        result.put("reason", email != null && User.findByEmail(email) == null ? "removed"
            : RESETS.containsKey(uid) ? "reset" : "ended");
      }
    }
    result.put("message", "please login");
    return unauthorized(result);
  }
}
