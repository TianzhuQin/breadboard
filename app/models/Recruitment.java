package models;

import com.avaje.ebean.Ebean;
import com.avaje.ebean.SqlRow;
import com.tinkerpop.blueprints.Vertex;
import play.Logger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recruitment details that arrive with the HTTP entry request (e.g. Prolific's PROLIFIC_PID), held
 * until the participant's vertex exists and then stored in {@code player._system.recruitment},
 * which only the player themselves (and the admin console) receives. The client uses {@code source == "prolific"} to show
 * the Prolific completion screen (groovy/recruitment.groovy, BBMain.vue). Also tells whether a Prolific
 * participant who arrives again already took part ({@link #prolificCompletion}).
 */
public final class Recruitment {

  private static final ConcurrentHashMap<String, Map<String, Object>> pending = new ConcurrentHashMap<>();

  private Recruitment() {}

  public static void prolificEntry(String clientId, String prolificPid, String studyId, String sessionId) {
    Map<String, Object> details = new LinkedHashMap<String, Object>();
    details.put("source", "prolific");
    details.put("prolificPid", prolificPid);
    details.put("studyId", studyId);
    details.put("sessionId", sessionId);
    pending.put(clientId, details);
  }

  /** Copy any recruitment details waiting for this vertex onto its _system map. */
  @SuppressWarnings("unchecked")
  public static void applyTo(Vertex v) {
    if (v == null) {
      return;
    }
    Map<String, Object> details = pending.get(String.valueOf(v.getId()));
    if (details == null) {
      return;
    }
    Object system = v.getProperty("_system");
    if (system instanceof Map) {
      Map<String, Object> systemMap = (Map<String, Object>) system;
      Object existing = systemMap.get("recruitment");
      Map<String, Object> recruitment = existing instanceof Map ? (Map<String, Object>) existing : new HashMap<String, Object>();
      recruitment.putAll(details);
      systemMap.put("recruitment", recruitment);
      // Set the property again so the change is announced (the admin console sees it live); writing
      // into the map alone told no one. See ScriptBoard.setConnected for why it is the same map.
      v.setProperty("_system", systemMap);
    }
  }

  public static void reset() {
    pending.clear();
  }

  // The Prolific entries recorded in a session (prolificEntry: clientId, prolificPid, studyId, sessionId).
  private static final String ENTRIES_SQL =
      "select e.id as event_id, d.name as name, d.value as value from events e join event_data d on d.event_id = e.id"
      + " where e.experiment_instance_id = :session and e.name = 'prolificEntry' order by e.id";

  /**
   * A session carries on in a rebuilt engine (a Reload engine, a Resume), which forgot every pending
   * entry (reset): the details of the Prolific participants who entered it are taken up again from
   * its data, so a seat holder's player gets them back as they return (applyTo). Without them the
   * page showed no Prolific end, and recruitment.completeProlific refused their completion. Never
   * throws.
   */
  public static void restoreFor(Long instanceId) {
    if (instanceId == null) {
      return;
    }
    try {
      Map<Long, Map<String, String>> entries = new LinkedHashMap<Long, Map<String, String>>();
      for (SqlRow row : Ebean.createSqlQuery(ENTRIES_SQL).setParameter("session", instanceId).findList()) {
        Long event = row.getLong("event_id");
        Map<String, String> entry = entries.get(event);
        if (entry == null) {
          entry = new HashMap<String, String>();
          entries.put(event, entry);
        }
        entry.put(row.getString("name"), row.getString("value"));
      }
      for (Map<String, String> entry : entries.values()) {
        String clientId = entry.get("clientId");
        String pid = entry.get("prolificPid");
        if (clientId != null && pid != null && !pending.containsKey(clientId)) {
          prolificEntry(clientId, pid, nullIfNull(entry.get("studyId")), nullIfNull(entry.get("sessionId")));
        }
      }
    } catch (RuntimeException e) {
      Logger.warn("Could not take up the Prolific entries of session " + instanceId + " again: " + e);
    }
  }

  // (a value recorded from null reads "null")
  private static String nullIfNull(String value) {
    return value == null || "null".equals(value) ? null : value;
  }

  /** What a Prolific participant who already took part was given (see {@link #prolificCompletion}). */
  public static final class Completion {
    /** Their completion code (the latest, if they were given several), or null when none was recorded. */
    public final String completionCode;

    Completion(String completionCode) {
      this.completionCode = completionCode;
    }
  }

  // For each session of the experiment that this PROLIFIC_PID entered (its prolificEntry event): the
  // session's state, the latest completion code recorded for the seat they had there, and whether
  // that seat's last connection event was a connection or a disconnection. Subqueries rather than
  // joins, so each reads only the events of the sessions found (by the index on their session). The
  // entries are found by the index on event data names (evolution 33): only prolificPid rows are read.
  private static final String PROLIFIC_SESSIONS_SQL =
      "select i.id as session_id, i.status as status, i.has_started as has_started,"
      + " (select code.value from events done"
      + "    join event_data player on player.event_id = done.id"
      + "    join event_data code on code.event_id = done.id"
      + "   where done.experiment_instance_id = i.id and done.name = 'prolificComplete'"
      + "     and player.name = 'player' and player.value = seat.value and code.name = 'completionCode'"
      + "   order by done.id desc limit 1) as completion_code,"
      + " (select conn.name from events conn"
      + "    join event_data who on who.event_id = conn.id"
      + "   where conn.experiment_instance_id = i.id and conn.name in ('clientConnected', 'clientDisconnected')"
      + "     and who.name = 'clientId' and who.value = seat.value"
      // only since the session last stopped, was cut short, resumed or reloaded: a game resumed after
      // a stop starts again, and someone in the first run who isn't in the new one didn't reach its
      // end; a reload of the engine that carries the session on drops everyone without recording it
      + "     and not exists (select 1 from events restart where restart.experiment_instance_id = i.id"
      + "       and restart.name in ('sessionStopped', 'sessionInterrupted', 'sessionResumed', 'engineReloaded')"
      + "       and restart.datetime >= conn.datetime)"
      + "   order by conn.datetime desc, conn.id desc limit 1) as last_connection"
      + " from experiment_instances i"
      + " join events entry on entry.experiment_instance_id = i.id"
      + " join event_data pid on pid.event_id = entry.id"
      + " join event_data seat on seat.event_id = entry.id"
      + " where i.experiment_id = :experiment and entry.name = 'prolificEntry'"
      + " and pid.name = 'prolificPid' and pid.value = :pid and seat.name = 'clientId'"
      + " order by i.id";

  /**
   * Whether someone with this PROLIFIC_PID already took part in a session of the experiment, read
   * from the sessions' events, which outlive the seats and end screens (those are kept in memory, so a
   * server restart forgets them). They did if a completion was recorded for them (prolificComplete,
   * from recruitment.completeProlific), or if a session they joined finished, after its game had
   * started, while they were still in it (connected). Someone whose session was stopped or cut short
   * by a restart, who left before its end, or whose session never started (the autopilot gave up
   * waiting) did not: they may join another. One query, however many sessions the experiment has.
   *
   * @return what they were given, or null when they haven't taken part (or their sessions' data
   *     can't be read: then they may join, as before)
   */
  public static Completion prolificCompletion(Long experimentId, String prolificPid) {
    if (experimentId == null || prolificPid == null || prolificPid.isEmpty()) {
      return null;
    }
    List<SqlRow> sessions;
    try {
      sessions = Ebean.createSqlQuery(PROLIFIC_SESSIONS_SQL)
          .setParameter("experiment", experimentId)
          .setParameter("pid", prolificPid)
          .findList();
    } catch (RuntimeException e) {
      // Only the innermost cause: the query's own error lists its bound values, the Prolific ID among them.
      Throwable cause = e;
      while (cause.getCause() != null) {
        cause = cause.getCause();
      }
      Logger.error("Could not check whether a Prolific participant already took part in experiment "
          + experimentId + ": " + cause);
      return null;
    }
    boolean tookPart = false;
    String code = null;
    for (SqlRow session : sessions) {
      String recorded = session.getString("completion_code");
      if (recorded != null) {
        tookPart = true;
        // (in session order, so the latest session's code wins)
        code = recorded;
      } else if ("FINISHED".equals(session.getString("status"))
          && Boolean.TRUE.equals(session.getBoolean("has_started"))
          && "clientConnected".equals(session.getString("last_connection"))) {
        tookPart = true;
      }
    }
    return tookPart ? new Completion(code) : null;
  }
}
