package models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import play.Logger;
import play.libs.Json;

import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class EventTracker {

  /** Longer event values are cut to this many characters in the live feed (the database keeps them whole, up to VALUE_LIMIT). */
  static final int LIVE_VALUE_LIMIT = 2000;

  /**
   * The longest event value the database keeps whole: event_data.value holds this many characters
   * (Global.widenEventValues; it was varchar(255)). A longer one is kept cut short, marked as such.
   */
  public static final int VALUE_LIMIT = 1048576;

  /** Event names and data names, and values where event_data.value couldn't be widened: varchar(255). */
  static final int NARROW_LIMIT = 255;

  private static final DateTimeFormatter ISO_UTC =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  @JsonIgnore
  private ExperimentInstance experimentInstance;
  private boolean enabled = true;

  public ExperimentInstance getExperimentInstance() {
    return experimentInstance;
  }

  public void enable() {
    enabled = true;
  }

  public void disable() {
    enabled = false;
  }

  /** Whether events go to a session that is still going (not finished, stopped or archived). */
  public boolean sessionLive() {
    ExperimentInstance i = experimentInstance;
    return enabled && i != null && (i.status == null || i.status == ExperimentInstance.Status.RUNNING
        || i.status == ExperimentInstance.Status.TESTING);
  }

  public void setExperimentInstance(ExperimentInstance experimentInstance) {
    this.experimentInstance = experimentInstance;
  }

  public void track(String name, LinkedHashMap<Object, Object> data) {
    this.track(name, nameValues(data));
  }

  private static List<Map<String, String>> nameValues(LinkedHashMap<Object, Object> data) {
    List<Map<String, String>> nameValues = new ArrayList<>();
    if (data == null) {
      return nameValues;
    }
    for (Object key : data.keySet()) {
      Map<String, String> dataMap = new HashMap<>();
      dataMap.put("name", String.valueOf(key));
      dataMap.put("value", (data.get(key) == null) ? "null" : data.get(key).toString());
      nameValues.add(dataMap);
    }
    return nameValues;
  }

  /**
   * Record an event in {@code instance} itself, whichever session the engine's tracker records into
   * (if any), and whether it is still going: what the server notes about a session from outside its
   * game (a participant's comment, sent after the game ended or once the next session has opened; a
   * reload of the engine that carries it on). As track: never throws, a value too long for the
   * database is kept cut short, and every open console gets the event live. Returns it, or null when
   * it couldn't be saved.
   */
  public static Event record(ExperimentInstance instance, String name, LinkedHashMap<Object, Object> data) {
    if (instance == null) {
      return null;
    }
    Event event = save(name, nameValues(data), instance);
    if (event != null) {
      publish(event);
    }
    return event;
  }

  /**
   * Record an event in the session. Never throws into the experiment's script: an event that can't
   * be saved is logged (with its name) and left out; one with a value too long for the database is
   * kept with that value cut short and marked (see {@link #cut}).
   */
  public void track(String name, List<Map<String, String>> nameValues) {
    if (!enabled) {
      return;
    }
    ExperimentInstance instance = experimentInstance;
    if (instance == null) {
      Logger.warn("No experimentInstance. Skips event tracking.");
      return;
    }
    Event event = save(name, nameValues, instance);
    if (event != null) {
      publish(event);
    }
  }

  /** The event as saved, or null when it couldn't be. */
  private static Event save(String name, List<Map<String, String>> nameValues, ExperimentInstance instance) {
    Event event = null;
    try {
      event = event(name, nameValues, instance, VALUE_LIMIT);
      event.save();
      return event;
    } catch (Exception e) {
      if (event == null || !tooLong(e)) {
        Logger.error("Could not record the event \"" + name + "\" in session " + instance.id + ": " + reason(e));
        return null;
      }
    }
    // A value didn't fit: event_data.value is still varchar(255) here (it couldn't be widened). A new
    // event (the failed one may have been given an id), with every value cut to fit.
    try {
      event = event(name, nameValues, instance, NARROW_LIMIT);
      event.save();
      Logger.warn("The event \"" + name + "\" in session " + instance.id + " had a value longer than " + NARROW_LIMIT
          + " characters, which this database can't keep: it was kept cut short, ending in \"…[truncated from N characters]\"");
      return event;
    } catch (Exception e) {
      Logger.error("Could not record the event \"" + name + "\" in session " + instance.id + ": " + reason(e));
      return null;
    }
  }

  private static Event event(String name, List<Map<String, String>> nameValues, ExperimentInstance instance, int valueLimit) {
    Event event = new Event();
    event.name = cut(name, NARROW_LIMIT);
    event.experimentInstance = instance;
    for (Map<?, ?> nameValue : nameValues) {
      EventData eventData = new EventData();
      // Scripts pass Groovy maps, whose values need not be strings (a number used to throw here).
      eventData.name = cut(text(nameValue.get("name")), NARROW_LIMIT);
      eventData.value = cut(text(nameValue.get("value")), valueLimit);
      event.addEventData(eventData);
    }
    return event;
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  /**
   * The text, or when it is longer than {@code limit} characters its start, marked with how long it
   * was ("...[truncated from 2000 characters]"), within {@code limit} in all.
   */
  static String cut(String text, int limit) {
    if (text == null || text.length() <= limit) {
      return text;
    }
    String mark = "…[truncated from " + text.length() + " characters]";
    int keep = Math.max(0, limit - mark.length());
    // never half of a character outside the Basic Multilingual Plane (an emoji)
    if (keep > 0 && Character.isHighSurrogate(text.charAt(keep - 1))) {
      keep--;
    }
    return text.substring(0, keep) + mark;
  }

  /** Whether the database refused a value as too long for its column (SQLSTATE 22001). */
  static boolean tooLong(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      for (SQLException s = t instanceof SQLException ? (SQLException) t : null; s != null; s = s.getNextException()) {
        if ("22001".equals(s.getSQLState())) {
          return true;
        }
      }
      String message = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
      if (message.contains("value too long") || message.contains("data too long")) {
        return true;
      }
    }
    return false;
  }

  /** The deepest cause's message, first line only (H2 adds the statement after it). */
  private static String reason(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String message = String.valueOf(root.getMessage());
    int end = message.indexOf('\n');
    return end > 0 ? message.substring(0, end).trim() : message;
  }

  /**
   * Push the event to every open admin console, as {@code {"event": {...}}} (see {@link #liveJson}).
   * Building it is cheap and sockets are written without blocking, so a participant's action is not
   * held up; nothing is built while no console is open.
   */
  private static void publish(Event event) {
    if (!ScriptBoard.hasAdmins()) {
      return;
    }
    try {
      ScriptBoard.toAllAdmins(liveJson(event));
    } catch (Exception e) {
      Logger.warn("Could not send event " + event.name + " to the admin console: " + e);
    }
  }

  /**
   * {@code {"event": {"id": 12|null, "name": "...", "datetime": "2026-10-27T14:03:05.123Z",
   * "data": {"<name>": "<value>", ...}, "experimentInstanceId": 7|null}}}: the datetime in UTC with
   * milliseconds, values longer than {@value #LIVE_VALUE_LIMIT} characters cut short (ending in an
   * ellipsis), and the session it was recorded in (every console gets every session's events).
   */
  static ObjectNode liveJson(Event event) {
    ObjectNode body = Json.newObject();
    body.put("id", event.id);
    body.put("name", event.name);
    body.put("datetime", event.datetime == null ? null : ISO_UTC.format(event.datetime.toInstant()));
    ObjectNode data = body.putObject("data");
    if (event.eventData != null) {
      for (EventData d : event.eventData) {
        if (d.name == null) {
          continue;
        }
        String value = d.value;
        if (value != null && value.length() > LIVE_VALUE_LIMIT) {
          value = value.substring(0, LIVE_VALUE_LIMIT) + "…";
        }
        data.put(d.name, value);
      }
    }
    body.put("experimentInstanceId", event.experimentInstance == null ? null : event.experimentInstance.id);
    ObjectNode message = Json.newObject();
    message.put("event", body);
    return message;
  }
}
