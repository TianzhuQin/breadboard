package models;

import com.avaje.ebean.Ebean;
import com.avaje.ebean.SqlRow;
import play.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The language of a session's texts: what its steps chose with c.setDefaultLanguage (ContentFetcher),
 * which Breadboard's own words to its participants follow (SystemTexts). Kept with the session
 * (experiment_instances.content_language), so its pages know it without asking the engine, also once
 * the engine runs another session, or after a restart of the server.
 *
 * <p>Recorded as a session is created, as its experiment's most recent session's (recordProvisional),
 * then when the steps have loaded for it (ScriptBoard.loadSteps), and again when they choose another
 * language while it runs. "" when they chose none (English), null when nothing was recorded (a session
 * from before 3.1, or an imported one). The experiment's test session (0) is kept in memory only.
 */
public final class SessionLanguage {
  /** Evolution 35, which the server's start makes too, for databases from older releases (Global). */
  public static final String ADD_COLUMN =
      "alter table experiment_instances add column if not exists content_language varchar(16)";

  // The sessions' languages as last recorded or read ("" for none chosen), the most recently used kept:
  // an autopilot opens thousands of sessions over a long run.
  private static final int KEPT = 500;
  private static final Map<Long, String> known = Collections.synchronizedMap(new LinkedHashMap<Long, String>(64, 0.75f, true) {
    protected boolean removeEldestEntry(Map.Entry<Long, String> eldest) {
      return size() > KEPT;
    }
  });
  private static volatile boolean warned = false;

  private SessionLanguage() {}

  /** The code of the language of the session's texts ("zho", "eng", ...), or null: none chosen, or not known. */
  public static String of(Long instanceId) {
    if (instanceId == null) {
      return null;
    }
    String code = known.get(instanceId);
    if (code == null && !Experiment.TEST_INSTANCE_ID.equals(instanceId)) {
      code = fromDatabase(instanceId);
      if (code != null) {
        known.put(instanceId, code);
      }
    }
    return code == null || code.isEmpty() ? null : code;
  }

  /** of(Long), as known in memory: without asking the database (e.g. while the server stops). */
  public static String known(Long instanceId) {
    String code = instanceId == null ? null : known.get(instanceId);
    return code == null || code.isEmpty() ? null : code;
  }

  /** of(Long), for a session's id as a link has it; null for one that isn't a number. */
  public static String of(String instanceId) {
    try {
      return instanceId == null ? null : of(Long.valueOf(instanceId.trim()));
    } catch (NumberFormatException notAnId) {
      return null;
    }
  }

  /**
   * The language of the experiment's most recent session that recorded one: for the pages people see
   * before or between its sessions (the holding page, "No session is open right now"), which lead to
   * the next session, not open yet. Null when none has (English).
   */
  public static String ofExperiment(Long experimentId) {
    if (experimentId == null || !databaseThere()) {
      return null;
    }
    try {
      SqlRow row = Ebean.createSqlQuery("select content_language as lang from experiment_instances"
          + " where experiment_id = :experiment and content_language is not null order by id desc limit 1")
          .setParameter("experiment", experimentId)
          .findUnique();
      String code = row == null ? null : row.getString("lang");
      return code == null || code.isEmpty() ? null : code;
    } catch (RuntimeException e) {
      warn(e);
      return null;
    }
  }

  /** ofExperiment(Long), for an experiment's id as a link has it. */
  public static String ofExperiment(String experimentId) {
    try {
      return experimentId == null ? null : ofExperiment(Long.valueOf(experimentId.trim()));
    } catch (NumberFormatException notAnId) {
      return null;
    }
  }

  /**
   * Record the language the session's steps chose ({@code code}; null: none). When it changes, the
   * pages of the session's participants are told (ScriptBoard.tellLanguage), so what Breadboard itself
   * shows them follows.
   */
  public static void record(Long instanceId, String code) {
    if (instanceId == null) {
      return;
    }
    String chosen = code == null ? "" : code.trim();
    String before = known.get(instanceId);
    if (before == null && !Experiment.TEST_INSTANCE_ID.equals(instanceId)) {
      before = fromDatabase(instanceId);
    }
    known.put(instanceId, chosen);
    if (chosen.equals(before)) {
      return;
    }
    if (!Experiment.TEST_INSTANCE_ID.equals(instanceId) && databaseThere()) {
      try {
        Ebean.createSqlUpdate("update experiment_instances set content_language = :lang where id = :id")
            .setParameter("lang", chosen)
            .setParameter("id", instanceId)
            .execute();
      } catch (RuntimeException e) {
        warn(e);
      }
    }
    if (before != null || !chosen.isEmpty()) {
      ScriptBoard.tellLanguage(instanceId, chosen.isEmpty() ? null : chosen);
    }
  }

  /**
   * Record the language of a new session's texts until its steps have loaded and chosen one
   * (ScriptBoard.startGame): its experiment's most recent session's (ofExperiment), which its steps
   * most likely choose again. A session is open to participants as soon as it runs, a moment before its
   * steps have loaded, and the study's link (and a holding page, which loads it every few seconds)
   * takes people to its sign-in page at once: that page was in English until then, and stayed so until
   * they sent it. ScriptBoard.loadSteps records what the steps chose.
   */
  public static void recordProvisional(Long instanceId, Long experimentId) {
    if (instanceId == null || Experiment.TEST_INSTANCE_ID.equals(instanceId)) {
      return;
    }
    record(instanceId, ofExperiment(experimentId));
  }

  /** Forget what is known in memory (tests). */
  public static void reset() {
    known.clear();
  }

  private static String fromDatabase(Long instanceId) {
    if (!databaseThere()) {
      return null;
    }
    try {
      SqlRow row = Ebean.createSqlQuery("select content_language as lang from experiment_instances where id = :id")
          .setParameter("id", instanceId)
          .findUnique();
      return row == null ? null : row.getString("lang");
    } catch (RuntimeException e) {
      warn(e);
      return null;
    }
  }

  // Whether the server runs (with its database): not in tests of the engine alone.
  private static boolean databaseThere() {
    return play.api.Play.maybeApplication().isDefined();
  }

  private static void warn(RuntimeException e) {
    if (!warned) {
      warned = true;
      Logger.warn("Could not read or record the language of a session's texts (Breadboard's own words to its participants "
          + "are then in English): " + e.getMessage());
    }
  }
}
