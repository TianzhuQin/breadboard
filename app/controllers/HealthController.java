package controllers;

import com.avaje.ebean.Ebean;
import com.fasterxml.jackson.databind.node.ObjectNode;
import play.Play;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;

import java.lang.management.ManagementFactory;

/**
 * GET /health, for uptime monitors and service managers: 200 {"status": "ok", ...} while the server
 * can reach its database, else 503. Says nothing about experiments or people, so it needs no sign-in.
 */
public class HealthController extends Controller {

  public static Result health() {
    ObjectNode result = Json.newObject();
    boolean database;
    try {
      database = Ebean.createSqlQuery("select 1 as one").findUnique() != null;
    } catch (Exception e) {
      database = false;
    }
    result.put("status", database ? "ok" : "failing");
    result.put("database", database ? "ok" : "unreachable");
    result.put("version", Play.application().configuration().getString("application.version", ""));
    result.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
    response().setHeader("Cache-Control", "no-store");
    return database ? ok(result) : status(SERVICE_UNAVAILABLE, result);
  }
}
