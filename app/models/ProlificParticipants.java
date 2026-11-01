package models;

import com.avaje.ebean.Ebean;
import com.avaje.ebean.SqlRow;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import play.libs.Json;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everyone who came from Prolific to any session of an experiment, read from the sessions' data:
 * who joined (prolificEntry: their seat, Prolific ID, study and Prolific session), who finished with
 * a completion code (prolificComplete: the code and any bonus, from recruitment.completeProlific),
 * and who finished without one (noCompletionCode, which the network game example records when its
 * prolificCode parameter is empty). The console lists them on the Recruitment page, so a session the
 * autopilot replaced ten seconds after its game ended can still be paid: the live list there only
 * shows the players of the session the engine runs now.
 */
public final class ProlificParticipants {

  private ProlificParticipants() {}

  // Every row of data of those events in the experiment's sessions, newest session first, then in the
  // order they were recorded. The events are found by their session (indexed) and name.
  private static final String EVENTS_SQL =
      "select i.id as session_id, i.name as session_name, i.status as session_status,"
      + " e.id as event_id, e.name as event_name, e.datetime as event_at, d.name as data_name, d.value as data_value"
      + " from experiment_instances i"
      + " join events e on e.experiment_instance_id = i.id"
      + " join event_data d on d.event_id = e.id"
      + " where i.experiment_id = :experiment"
      + " and e.name in ('prolificEntry', 'prolificComplete', 'noCompletionCode')"
      + " order by i.id desc, e.id, d.id";

  /** One participant's seat in one session. */
  private static final class Seat {
    Long sessionId;
    String sessionName;
    String sessionStatus;
    String player;
    String prolificPid = "";
    String studyId = "";
    String prolificSessionId = "";
    Date joinedAt;
    boolean completed;
    Date completedAt;
    String completionCode;
    Double bonus;
    boolean noCode;
  }

  /** One event, with its data by name. */
  private static final class Recorded {
    Long sessionId;
    String sessionName;
    String sessionStatus;
    String name;
    Date at;
    final Map<String, String> data = new LinkedHashMap<String, String>();
  }

  /**
   * {"participants": [...]}: one entry per Prolific participant and session, newest session first,
   * then in the order they joined. Each has sessionId, sessionName, sessionStatus, player (their
   * seat's id), prolificPid, studyId, prolificSessionId, joinedAt, completed, completedAt,
   * completionCode, bonus (null when none) and finishedWithoutCode. Times are ISO-8601 UTC.
   */
  public static ObjectNode forExperiment(Long experimentId) {
    List<SqlRow> rows = Ebean.createSqlQuery(EVENTS_SQL).setParameter("experiment", experimentId).findList();
    // the events, in the order of the rows
    Map<Long, Recorded> events = new LinkedHashMap<Long, Recorded>();
    for (SqlRow row : rows) {
      Long id = row.getLong("event_id");
      Recorded event = events.get(id);
      if (event == null) {
        event = new Recorded();
        event.sessionId = row.getLong("session_id");
        event.sessionName = row.getString("session_name");
        event.sessionStatus = row.getString("session_status");
        event.name = row.getString("event_name");
        event.at = row.getTimestamp("event_at");
        events.put(id, event);
      }
      String name = row.getString("data_name");
      if (name != null) {
        event.data.put(name, row.getString("data_value"));
      }
    }
    // their seats: by session and player, in the order they first appear
    Map<String, Seat> seats = new LinkedHashMap<String, Seat>();
    for (Recorded event : events.values()) {
      String player = "prolificEntry".equals(event.name) ? event.data.get("clientId") : event.data.get("player");
      if (player == null || player.isEmpty()) {
        continue;
      }
      String key = event.sessionId + "/" + player;
      Seat seat = seats.get(key);
      if (seat == null) {
        seat = new Seat();
        seat.sessionId = event.sessionId;
        seat.sessionName = event.sessionName;
        seat.sessionStatus = event.sessionStatus;
        seat.player = player;
        seats.put(key, seat);
      }
      if ("prolificEntry".equals(event.name)) {
        // (the first entry is when they joined; one again, e.g. after a reload, adds nothing)
        if (seat.joinedAt == null) {
          seat.joinedAt = event.at;
        }
        if (seat.prolificPid.isEmpty()) {
          seat.prolificPid = text(event.data.get("prolificPid"));
          seat.studyId = text(event.data.get("studyId"));
          seat.prolificSessionId = text(event.data.get("sessionId"));
        }
      } else if ("prolificComplete".equals(event.name)) {
        // (the latest completion is what they were given last)
        seat.completed = true;
        seat.completedAt = event.at;
        seat.completionCode = event.data.get("completionCode");
        seat.bonus = amount(event.data.get("bonus"));
      } else {
        seat.noCode = true;
      }
    }
    ObjectNode json = Json.newObject();
    ArrayNode list = json.putArray("participants");
    // (in the order of the rows: newest session first, then as they joined)
    for (Seat seat : seats.values()) {
      ObjectNode p = list.addObject();
      p.put("sessionId", seat.sessionId);
      p.put("sessionName", seat.sessionName);
      p.put("sessionStatus", seat.sessionStatus);
      p.put("player", seat.player);
      p.put("prolificPid", seat.prolificPid);
      p.put("studyId", seat.studyId);
      p.put("prolificSessionId", seat.prolificSessionId);
      p.put("joinedAt", Timestamps.iso(seat.joinedAt));
      p.put("completed", seat.completed);
      p.put("completedAt", Timestamps.iso(seat.completedAt));
      p.put("completionCode", seat.completionCode);
      if (seat.bonus == null) {
        p.putNull("bonus");
      } else {
        p.put("bonus", seat.bonus);
      }
      p.put("finishedWithoutCode", seat.noCode && !seat.completed);
    }
    return json;
  }

  private static String text(String s) {
    return s == null || "null".equals(s) ? "" : s;
  }

  /** A bonus as recorded ("1.25"); null when none, or not a positive amount. */
  private static Double amount(String s) {
    if (s == null) {
      return null;
    }
    try {
      double d = Double.parseDouble(s.trim());
      return d > 0 && !Double.isInfinite(d) && !Double.isNaN(d) ? d : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
