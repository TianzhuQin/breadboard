package models;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * player._system. The participant's socket thread, the autopilot's timers and scripts write to it
 * while the consoles' and pages' updates read it on other threads; a plain map could throw
 * ConcurrentModificationException half-way through such an update. Setting a key to null removes
 * it, as a ConcurrentHashMap can't hold null.
 *
 * <p>A Java class rather than one defined in groovy/graph.groovy: Jackson keeps a serializer for
 * every class it writes, and a class of the engine's scripts kept the whole engine in memory after
 * it was rebuilt, one engine per session.
 */
public class SystemMap extends ConcurrentHashMap<String, Object> {

  private static final long serialVersionUID = 1L;

  @Override
  public Object put(String key, Object value) {
    return value == null ? remove(key) : super.put(key, value);
  }

  @Override
  public void putAll(Map<? extends String, ?> values) {
    for (Map.Entry<? extends String, ?> entry : values.entrySet()) {
      put(entry.getKey(), entry.getValue());
    }
  }
}
