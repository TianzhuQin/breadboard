package controllers;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import play.Logger;
import play.Play;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security;

/** Only the guides shipped with this version, for the console's Help page. */
public class HelpController extends Controller {
  @Security.Authenticated(Secured.class)
  public static Result guide(String file) {
    if (!Arrays.asList("console.md", "zh-CN.md", "install.md", "whats-new.md", "development.md").contains(file)) {
      return notFound("There is no guide with that name.");
    }
    try {
      File docs = new File(Play.application().path(), "docs").getCanonicalFile();
      File guide = new File(docs, file).getCanonicalFile();
      if (!docs.equals(guide.getParentFile()) || !guide.isFile()) {
        return notFound("This guide is missing from the Breadboard folder.");
      }
      response().setHeader("Cache-Control", "no-store");
      response().setHeader("X-Content-Type-Options", "nosniff");
      return ok(new String(Files.readAllBytes(guide.toPath()), StandardCharsets.UTF_8)).as("text/plain; charset=utf-8");
    } catch (java.io.IOException e) {
      Logger.warn("Could not read a shipped guide: " + file);
      return notFound("This guide could not be read from the Breadboard folder.");
    }
  }
}
