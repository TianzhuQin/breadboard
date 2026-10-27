package models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import play.Logger;
import play.libs.Json;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class EventTracker {

  /** Longer event values are cut to this many characters in the live feed (the database keeps them whole). */
  static final int LIVE_VALUE_LIMIT = 2000;

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

  public void setExperimentInstance(ExperimentInstance experimentInstance) {
    this.experimentInstance = experimentInstance;
  }

  public void track(String name, LinkedHashMap<Object, Object> data) {
    List<Map<String, String>> nameValues = new ArrayList<>();

    for (Object key : data.keySet()) {
      Map<String, String> dataMap = new HashMap<>();
      dataMap.put("name", key.toString());
      dataMap.put("value", (data.get(key) == null) ? "null" : data.get(key).toString());
      nameValues.add(dataMap);
    }

    this.track(name, nameValues);
  }

  public void track(String name, List<Map<String, String>> nameValues) {
    if (!enabled) {
      return;
    }
    if (experimentInstance == null) {
      Logger.warn("No experimentInstance. Skips event tracking.");
      return;
    }
    Event event = new Event();
    event.name = name;
    event.experimentInstance = experimentInstance;
    for (Map<?, ?> nameValue : nameValues) {
      EventData eventData = new EventData();
      // Scripts pass Groovy maps, whose values need not be strings (a number used to throw here).
      eventData.name = text(nameValue.get("name"));
      eventData.value = text(nameValue.get("value"));
      event.addEventData(eventData);
    }
    event.save();
    publish(event);
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
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
