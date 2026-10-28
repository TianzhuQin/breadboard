package controllers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import models.User;
import play.mvc.Http.Context;
import play.mvc.Result;
import play.mvc.Security;
import play.libs.Json;

public class Secured extends Security.Authenticator {
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
    } else {
      result.put("status", "unauthorized");
      // This browser was signed in, but the account has signed out somewhere else since (or changed
      // its password), which ended this session: the console says so.
      String uid = ctx.session().get("uid");
      if (uid != null && !uid.trim().isEmpty()) {
        // ... or another researcher removed the account.
        String email = ctx.session().get("email");
        result.put("reason", email != null && User.findByEmail(email) == null ? "removed" : "ended");
      }
    }
    result.put("message", "please login");
    return unauthorized(result);
  }
}
