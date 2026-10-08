package controllers;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import logging.LogCapture;
import logging.LogEntry;
import logging.LogStore;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security;

/**
 * Windowed access to the server log for the Logs dialog. Nothing is pushed to the browser:
 * it asks for one page at a time.
 *
 * <p>GET /logs takes these query parameters, all optional:
 * <ul>
 *   <li>{@code before} - page backwards: matching entries with seq &lt; before (default: from the newest)</li>
 *   <li>{@code after}  - page forwards: matching entries with seq &gt; after (takes precedence over before)</li>
 *   <li>{@code limit}  - max entries returned (default 200, max 1000)</li>
 *   <li>{@code q}      - case-insensitive substring of the message or source</li>
 *   <li>{@code level}  - minimum level: DEBUG, INFO, WARN or ERROR</li>
 * </ul>
 * The response's {@code cursor} is the value to pass as the next before/after to keep paging.
 */
public class LogsController extends Controller {
  private static final int DEFAULT_LIMIT = 200;
  private static final int MAX_LIMIT = 1000;

  @Security.Authenticated(Secured.class)
  public static Result getLogs() {
    LogStore store = LogCapture.store();
    ObjectNode result = Json.newObject();
    if (store == null) {
      result.put("enabled", false);
      return ok(result);
    }

    Long before = parseLong(request().getQueryString("before"));
    Long after = parseLong(request().getQueryString("after"));
    Long limitParam = parseLong(request().getQueryString("limit"));
    int limit = limitParam == null ? DEFAULT_LIMIT : (int) Math.max(1, Math.min(MAX_LIMIT, limitParam));
    LogStore.Filter filter = new LogStore.Filter(request().getQueryString("q"), request().getQueryString("level"));

    LogStore.Page page = (after != null)
        ? store.after(after, limit, filter)
        : store.before(before == null ? Long.MAX_VALUE : before, limit, filter);

    ArrayNode entries = result.putArray("entries");
    for (LogEntry e : page.entries) {
      entries.add(Json.toJson(e.toMap()));
    }
    result.put("enabled", true);
    result.put("direction", after != null ? "after" : "before");
    result.put("hasMore", page.hasMore);
    result.put("cursor", page.cursor);
    result.put("latestSeq", page.latestSeq);
    return ok(result);
  }

  private static Long parseLong(String s) {
    if (s == null || s.isEmpty()) return null;
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
