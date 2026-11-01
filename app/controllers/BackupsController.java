package controllers;

import models.Backups;
import play.Logger;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security;

import java.io.File;

/** The database backups (models.Backups), for the console's Settings → Backups. */
@Security.Authenticated(Secured.class)
public class BackupsController extends Controller {

  public static Result list() {
    return ok(Backups.statusJson());
  }

  /** Back up now. 200 with the list, or 400 saying why not. */
  public static Result backUp() {
    if (!Backups.enabled()) {
      return badRequest(play.libs.Json.newObject().put("message", "Backups are off on this server (breadboard.backup.enabled), or it doesn't keep its database in a file."));
    }
    try {
      Backups.backUp("manual");
    } catch (Exception e) {
      Logger.error("Backups: a backup asked for in the console failed", e);
      return badRequest(play.libs.Json.newObject().put("message", "The backup failed: " + e.getMessage()));
    }
    return ok(Backups.statusJson());
  }

  /** One backup's zip. */
  public static Result download(String name) {
    File file = Backups.find(name);
    if (file == null) {
      return notFound("There is no such backup.");
    }
    response().setHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
    response().setHeader("Cache-Control", "no-store");
    return ok(file).as("application/zip");
  }
}
