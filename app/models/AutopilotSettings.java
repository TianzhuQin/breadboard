package models;

import com.avaje.ebean.Ebean;
import com.avaje.ebean.SqlRow;
import com.avaje.ebean.SqlUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import play.libs.Json;

import java.sql.Types;
import java.util.Date;

/**
 * The autopilot settings a researcher saves in the console (GET/POST /autopilot), kept in the
 * {@code autopilot_settings} table (evolution 31, and 34 for the longest session). There is one row:
 * the script engine is shared, so only one experiment can run unattended at a time.
 *
 * <p>The defaults are groovy/autopilot.groovy's own. The waiting-room and replace-disconnected values
 * are applied to the engine by {@link AutopilotHooks} after the experiment's steps load.
 */
public final class AutopilotSettings {

  /** Longest wait accepted: a day. The engine's timers count milliseconds in an int. */
  public static final int MAX_SECONDS = 86400;
  public static final int MAX_PLAYERS = 10000;
  /** The longest session that can be set: a day, in minutes. */
  public static final int MAX_SESSION_MINUTES = 1440;
  /** What a session's game may run by default before the autopilot ends it (see maxSessionMinutes). */
  public static final int DEFAULT_SESSION_MINUTES = 120;

  public boolean enabled = false;
  public Long experimentId = null;
  public boolean continuous = true;
  public int nextSessionDelaySeconds = 10;
  // How long the game of the autopilot's own session may run, in minutes, before it is ended
  // (Autopilot.watchdog); null: no limit. A game that never ends (a script waiting for something
  // that never comes) otherwise held the engine, and the study, for good.
  public Integer maxSessionMinutes = DEFAULT_SESSION_MINUTES;

  public boolean waitingRoomEnabled = true;
  public int minPlayers = 2;
  public Integer maxPlayers = null;
  public int waitSeconds = 60;
  public Integer maxWaitSeconds = null;
  public int countdownSeconds = 5;

  public boolean replaceEnabled = true;
  public int replaceAfterSeconds = 30;
  public boolean returnToHuman = true;

  /** The account that saved them, which the autopilot opens sessions as. */
  public String operatorEmail = null;
  public Date updatedAt = null;

  /** A settings value that can't be accepted; its message says why, in plain words. */
  public static final class Invalid extends IllegalArgumentException {
    public Invalid(String message) {
      super(message);
    }
  }

  public AutopilotSettings copy() {
    AutopilotSettings c = new AutopilotSettings();
    c.enabled = enabled;
    c.experimentId = experimentId;
    c.continuous = continuous;
    c.nextSessionDelaySeconds = nextSessionDelaySeconds;
    c.maxSessionMinutes = maxSessionMinutes;
    c.waitingRoomEnabled = waitingRoomEnabled;
    c.minPlayers = minPlayers;
    c.maxPlayers = maxPlayers;
    c.waitSeconds = waitSeconds;
    c.maxWaitSeconds = maxWaitSeconds;
    c.countdownSeconds = countdownSeconds;
    c.replaceEnabled = replaceEnabled;
    c.replaceAfterSeconds = replaceAfterSeconds;
    c.returnToHuman = returnToHuman;
    c.operatorEmail = operatorEmail;
    c.updatedAt = updatedAt;
    return c;
  }

  // ---- JSON (the console's AutopilotSettings) ---------------------------------------------------

  public ObjectNode toJson() {
    ObjectNode json = Json.newObject();
    json.put("enabled", enabled);
    if (experimentId == null) {
      json.putNull("experimentId");
    } else {
      json.put("experimentId", experimentId);
    }
    json.put("continuous", continuous);
    json.put("nextSessionDelaySeconds", nextSessionDelaySeconds);
    putOptional(json, "maxSessionMinutes", maxSessionMinutes);   // null: no limit
    ObjectNode room = json.putObject("waitingRoom");
    room.put("enabled", waitingRoomEnabled);
    room.put("minPlayers", minPlayers);
    putOptional(room, "maxPlayers", maxPlayers);
    room.put("waitSeconds", waitSeconds);
    putOptional(room, "maxWaitSeconds", maxWaitSeconds);
    room.put("countdownSeconds", countdownSeconds);
    ObjectNode replace = json.putObject("replaceDisconnected");
    replace.put("enabled", replaceEnabled);
    replace.put("afterSeconds", replaceAfterSeconds);
    replace.put("returnToHuman", returnToHuman);
    return json;
  }

  private static void putOptional(ObjectNode json, String key, Integer value) {
    if (value == null) {
      json.putNull(key);
    } else {
      json.put(key, value);
    }
  }

  /**
   * {@code base} with the values in {@code body} applied: a key that is left out keeps its value.
   * Checks each value on its own (whether the experiment exists is the caller's job).
   *
   * @throws Invalid naming the first value that can't be accepted
   */
  public static AutopilotSettings fromJson(JsonNode body, AutopilotSettings base) {
    if (body == null || !body.isObject()) {
      throw new Invalid("Send the settings as a JSON object.");
    }
    AutopilotSettings s = base.copy();
    s.enabled = bool(body, "enabled", s.enabled, "Whether the autopilot is on (enabled)");
    if (body.has("experimentId")) {
      JsonNode id = body.get("experimentId");
      if (id.isNull()) {
        s.experimentId = null;
      } else {
        Long value = wholeNumber(id, "The experiment (experimentId)");
        if (value <= 0) {
          throw new Invalid("The experiment (experimentId) must be an experiment's id.");
        }
        s.experimentId = value;
      }
    }
    s.continuous = bool(body, "continuous", s.continuous, "Whether to open the next session (continuous)");
    s.nextSessionDelaySeconds = whole(body, "nextSessionDelaySeconds", s.nextSessionDelaySeconds, 0, MAX_SECONDS,
        "The pause before the next session opens (nextSessionDelaySeconds)");
    s.maxSessionMinutes = optionalWhole(body, "maxSessionMinutes", s.maxSessionMinutes, 1, MAX_SESSION_MINUTES,
        "The longest a session may run, in minutes (maxSessionMinutes)");

    JsonNode room = section(body, "waitingRoom", "The waiting room (waitingRoom)");
    if (room != null) {
      s.waitingRoomEnabled = bool(room, "enabled", s.waitingRoomEnabled, "Whether there is a waiting room (waitingRoom.enabled)");
      s.minPlayers = whole(room, "minPlayers", s.minPlayers, 1, MAX_PLAYERS,
          "The number of people needed to start (waitingRoom.minPlayers)");
      s.maxPlayers = optionalWhole(room, "maxPlayers", s.maxPlayers, 1, MAX_PLAYERS,
          "The most people in one session (waitingRoom.maxPlayers)");
      s.waitSeconds = whole(room, "waitSeconds", s.waitSeconds, 0, MAX_SECONDS,
          "How long to wait for more people (waitingRoom.waitSeconds)");
      s.maxWaitSeconds = optionalWhole(room, "maxWaitSeconds", s.maxWaitSeconds, 0, MAX_SECONDS,
          "How long to wait before giving up (waitingRoom.maxWaitSeconds)");
      s.countdownSeconds = whole(room, "countdownSeconds", s.countdownSeconds, 0, MAX_SECONDS,
          "The countdown before the game starts (waitingRoom.countdownSeconds)");
    }
    // groovy/autopilot.groovy treats 0 as "never give up", which null says plainly.
    if (s.maxWaitSeconds != null && s.maxWaitSeconds == 0) {
      s.maxWaitSeconds = null;
    }
    if (s.maxPlayers != null && s.maxPlayers < s.minPlayers) {
      throw new Invalid("The most people in one session (waitingRoom.maxPlayers) can't be less than the number needed to start ("
          + s.minPlayers + ").");
    }

    JsonNode replace = section(body, "replaceDisconnected", "Replacing people who leave (replaceDisconnected)");
    if (replace != null) {
      s.replaceEnabled = bool(replace, "enabled", s.replaceEnabled,
          "Whether the computer takes over for people who leave (replaceDisconnected.enabled)");
      s.replaceAfterSeconds = whole(replace, "afterSeconds", s.replaceAfterSeconds, 0, MAX_SECONDS,
          "How long someone may be gone (replaceDisconnected.afterSeconds)");
      s.returnToHuman = bool(replace, "returnToHuman", s.returnToHuman,
          "Whether people get their seat back (replaceDisconnected.returnToHuman)");
    }
    return s;
  }

  private static JsonNode section(JsonNode body, String key, String label) {
    JsonNode node = body.get(key);
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isObject()) {
      throw new Invalid(label + " must be an object.");
    }
    return node;
  }

  private static boolean bool(JsonNode json, String key, boolean current, String label) {
    JsonNode node = json.get(key);
    if (node == null || node.isNull()) {
      return current;
    }
    if (!node.isBoolean()) {
      throw new Invalid(label + " must be true or false.");
    }
    return node.booleanValue();
  }

  private static int whole(JsonNode json, String key, int current, int min, int max, String label) {
    JsonNode node = json.get(key);
    if (node == null || node.isNull()) {
      return current;
    }
    return inRange(wholeNumber(node, label), min, max, label);
  }

  private static Integer optionalWhole(JsonNode json, String key, Integer current, int min, int max, String label) {
    if (!json.has(key)) {
      return current;
    }
    JsonNode node = json.get(key);
    if (node.isNull()) {
      return null;
    }
    return inRange(wholeNumber(node, label), min, max, label);
  }

  private static int inRange(long value, int min, int max, String label) {
    if (value < min || value > max) {
      throw new Invalid(label + " must be from " + min + " to " + max + ", not " + value + ".");
    }
    return (int) value;
  }

  /** A whole number sent as a number (5 or 5.0) or as text ("5"). */
  private static long wholeNumber(JsonNode node, String label) {
    if (node.isIntegralNumber() && node.canConvertToLong()) {
      return node.longValue();
    }
    if (node.isNumber()) {
      double d = node.doubleValue();
      if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
        return (long) d;
      }
    } else if (node.isTextual() && node.textValue().trim().matches("-?\\d{1,15}")) {
      return Long.parseLong(node.textValue().trim());
    }
    throw new Invalid(label + " must be a whole number, not " + node + ".");
  }

  // ---- the database -------------------------------------------------------------------------------

  private static final long ROW_ID = 1L;
  private static volatile boolean tableChecked = false;

  /**
   * The table as evolutions 31 and 34 make it. Installs that run with evolutions off
   * (application-prod.conf) ship a ready-made database, so the table is also created here, the first
   * time it is needed; Global adds evolution 34's column to one an older release made.
   */
  static final String CREATE_TABLE =
      "create table if not exists autopilot_settings (\n"
          + "  id                          bigint not null,\n"
          + "  enabled                     boolean default false not null,\n"
          + "  experiment_id               bigint,\n"
          + "  operator_email              varchar(255),\n"
          + "  continuous                  boolean default true not null,\n"
          + "  next_session_delay_seconds  integer default 10 not null,\n"
          + "  max_session_minutes         integer default " + DEFAULT_SESSION_MINUTES + ",\n"
          + "  waiting_room_enabled        boolean default true not null,\n"
          + "  min_players                 integer default 2 not null,\n"
          + "  max_players                 integer,\n"
          + "  wait_seconds                integer default 60 not null,\n"
          + "  max_wait_seconds            integer,\n"
          + "  countdown_seconds           integer default 5 not null,\n"
          + "  replace_enabled             boolean default true not null,\n"
          + "  replace_after_seconds       integer default 30 not null,\n"
          + "  return_to_human             boolean default true not null,\n"
          + "  updated_at                  timestamp,\n"
          + "  constraint pk_autopilot_settings primary key (id))";

  private static void ensureTable() {
    if (!tableChecked) {
      Ebean.createSqlUpdate(CREATE_TABLE).execute();
      tableChecked = true;
    }
  }

  /** The saved settings, or null when none were ever saved. */
  public static AutopilotSettings load() {
    ensureTable();
    SqlRow row = Ebean.createSqlQuery("select * from autopilot_settings where id = :id")
        .setParameter("id", ROW_ID)
        .findUnique();
    if (row == null) {
      return null;
    }
    AutopilotSettings s = new AutopilotSettings();
    s.enabled = flag(row, "enabled", s.enabled);
    s.experimentId = row.getLong("experiment_id");
    s.operatorEmail = row.getString("operator_email");
    s.continuous = flag(row, "continuous", s.continuous);
    s.nextSessionDelaySeconds = number(row, "next_session_delay_seconds", s.nextSessionDelaySeconds);
    // (null: no limit, as saved)
    s.maxSessionMinutes = row.getInteger("max_session_minutes");
    s.waitingRoomEnabled = flag(row, "waiting_room_enabled", s.waitingRoomEnabled);
    s.minPlayers = number(row, "min_players", s.minPlayers);
    s.maxPlayers = row.getInteger("max_players");
    s.waitSeconds = number(row, "wait_seconds", s.waitSeconds);
    s.maxWaitSeconds = row.getInteger("max_wait_seconds");
    s.countdownSeconds = number(row, "countdown_seconds", s.countdownSeconds);
    s.replaceEnabled = flag(row, "replace_enabled", s.replaceEnabled);
    s.replaceAfterSeconds = number(row, "replace_after_seconds", s.replaceAfterSeconds);
    s.returnToHuman = flag(row, "return_to_human", s.returnToHuman);
    s.updatedAt = row.getTimestamp("updated_at");
    return s;
  }

  private static boolean flag(SqlRow row, String column, boolean fallback) {
    Boolean value = row.getBoolean(column);
    return value == null ? fallback : value;
  }

  private static int number(SqlRow row, String column, int fallback) {
    Integer value = row.getInteger(column);
    return value == null ? fallback : value;
  }

  /** Store these settings as the saved ones (replacing what was saved before). */
  public void save() {
    ensureTable();
    updatedAt = new Date();
    String set = "enabled = :enabled, experiment_id = :experimentId, operator_email = :operatorEmail, "
        + "continuous = :continuous, next_session_delay_seconds = :nextDelay, max_session_minutes = :maxSession, "
        + "waiting_room_enabled = :roomEnabled, min_players = :minPlayers, max_players = :maxPlayers, "
        + "wait_seconds = :waitSeconds, max_wait_seconds = :maxWait, countdown_seconds = :countdown, "
        + "replace_enabled = :replaceEnabled, replace_after_seconds = :replaceAfter, "
        + "return_to_human = :returnToHuman, updated_at = :updatedAt";
    int updated = bind(Ebean.createSqlUpdate("update autopilot_settings set " + set + " where id = :id")).execute();
    if (updated == 0) {
      bind(Ebean.createSqlUpdate("insert into autopilot_settings (id, enabled, experiment_id, operator_email, continuous, "
          + "next_session_delay_seconds, max_session_minutes, waiting_room_enabled, min_players, max_players, wait_seconds, "
          + "max_wait_seconds, countdown_seconds, replace_enabled, replace_after_seconds, return_to_human, updated_at) values "
          + "(:id, :enabled, :experimentId, :operatorEmail, :continuous, :nextDelay, :maxSession, :roomEnabled, :minPlayers, "
          + ":maxPlayers, :waitSeconds, :maxWait, :countdown, :replaceEnabled, :replaceAfter, :returnToHuman, :updatedAt)")).execute();
    }
  }

  private SqlUpdate bind(SqlUpdate sql) {
    sql.setParameter("id", ROW_ID);
    sql.setParameter("enabled", enabled);
    nullable(sql, "experimentId", experimentId, Types.BIGINT);
    nullable(sql, "operatorEmail", operatorEmail, Types.VARCHAR);
    sql.setParameter("continuous", continuous);
    sql.setParameter("nextDelay", nextSessionDelaySeconds);
    nullable(sql, "maxSession", maxSessionMinutes, Types.INTEGER);
    sql.setParameter("roomEnabled", waitingRoomEnabled);
    sql.setParameter("minPlayers", minPlayers);
    nullable(sql, "maxPlayers", maxPlayers, Types.INTEGER);
    sql.setParameter("waitSeconds", waitSeconds);
    nullable(sql, "maxWait", maxWaitSeconds, Types.INTEGER);
    sql.setParameter("countdown", countdownSeconds);
    sql.setParameter("replaceEnabled", replaceEnabled);
    sql.setParameter("replaceAfter", replaceAfterSeconds);
    sql.setParameter("returnToHuman", returnToHuman);
    sql.setParameter("updatedAt", new java.sql.Timestamp(updatedAt.getTime()));
    return sql;
  }

  private static void nullable(SqlUpdate sql, String name, Object value, int type) {
    if (value == null) {
      sql.setNullParameter(name, type);
    } else {
      sql.setParameter(name, value);
    }
  }

  // ---- paused or done, kept over a restart (autopilot_halt) ---------------------------------------

  /**
   * Why the autopilot opens no session by itself (a researcher ended its session, or its one session
   * finished), and for which experiment. A table of its own, not columns of autopilot_settings: when
   * application.conf runs the autopilot there is no settings row, and writing one would put the
   * console in charge.
   */
  public static final class Halt {
    public Long experimentId;
    public String pausedBecause;
    public String doneBecause;
  }

  /** The table as evolution 32 creates it (also created here, for installs with evolutions off). */
  static final String CREATE_HALT_TABLE =
      "create table if not exists autopilot_halt (\n"
          + "  id                          bigint not null,\n"
          + "  experiment_id               bigint,\n"
          + "  paused_because              varchar(2000),\n"
          + "  done_because                varchar(2000),\n"
          + "  updated_at                  timestamp,\n"
          + "  constraint pk_autopilot_halt primary key (id))";

  private static volatile boolean haltTableChecked = false;

  private static void ensureHaltTable() {
    if (!haltTableChecked) {
      Ebean.createSqlUpdate(CREATE_HALT_TABLE).execute();
      haltTableChecked = true;
    }
  }

  /** What was kept, or null when the autopilot is neither paused nor done. */
  public static Halt loadHalt() {
    ensureHaltTable();
    SqlRow row = Ebean.createSqlQuery("select * from autopilot_halt where id = :id")
        .setParameter("id", ROW_ID)
        .findUnique();
    if (row == null || (row.getString("paused_because") == null && row.getString("done_because") == null)) {
      return null;
    }
    Halt halt = new Halt();
    halt.experimentId = row.getLong("experiment_id");
    halt.pausedBecause = row.getString("paused_because");
    halt.doneBecause = row.getString("done_because");
    return halt;
  }

  /** Keep why the autopilot opens nothing by itself; both null (not paused, not done) clears it. */
  public static void saveHalt(Long experimentId, String pausedBecause, String doneBecause) {
    ensureHaltTable();
    if (pausedBecause == null && doneBecause == null) {
      Ebean.createSqlUpdate("delete from autopilot_halt where id = :id").setParameter("id", ROW_ID).execute();
      return;
    }
    SqlUpdate update = Ebean.createSqlUpdate("update autopilot_halt set experiment_id = :experimentId, "
        + "paused_because = :paused, done_because = :done, updated_at = :updatedAt where id = :id");
    if (bindHalt(update, experimentId, pausedBecause, doneBecause).execute() == 0) {
      bindHalt(Ebean.createSqlUpdate("insert into autopilot_halt (id, experiment_id, paused_because, done_because, updated_at) "
          + "values (:id, :experimentId, :paused, :done, :updatedAt)"), experimentId, pausedBecause, doneBecause).execute();
    }
  }

  private static SqlUpdate bindHalt(SqlUpdate sql, Long experimentId, String pausedBecause, String doneBecause) {
    sql.setParameter("id", ROW_ID);
    nullable(sql, "experimentId", experimentId, Types.BIGINT);
    nullable(sql, "paused", clip(pausedBecause), Types.VARCHAR);
    nullable(sql, "done", clip(doneBecause), Types.VARCHAR);
    sql.setParameter("updatedAt", new java.sql.Timestamp(System.currentTimeMillis()));
    return sql;
  }

  // Session names are typed by researchers; the column holds 2000 characters.
  private static String clip(String text) {
    return text == null || text.length() <= 2000 ? text : text.substring(0, 1999) + "…";
  }
}
