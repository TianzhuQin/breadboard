package models;

import com.tinkerpop.blueprints.Vertex;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recruitment details that arrive with the HTTP entry request (e.g. Prolific's PROLIFIC_PID), held
 * until the participant's vertex exists and then stored in {@code player._system.recruitment},
 * which only the player themselves receives. The client uses {@code source == "prolific"} to show
 * the Prolific completion screen (groovy/recruitment.groovy, BBMain.vue).
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
    }
  }

  public static void reset() {
    pending.clear();
  }
}
