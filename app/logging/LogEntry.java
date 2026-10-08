package logging;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One captured log line: a logback event, a line printed to stdout/stderr, or a groovy
 * {@code println}. {@code seq} is unique and strictly increasing across restarts, so it is the
 * cursor the admin UI pages by.
 */
public class LogEntry {
  public final long seq;
  public final long ts;
  public final String level;
  public final String source;
  public final String msg;

  public LogEntry(long seq, long ts, String level, String source, String msg) {
    this.seq = seq;
    this.ts = ts;
    this.level = level;
    this.source = source;
    this.msg = msg;
  }

  public Map<String, Object> toMap() {
    Map<String, Object> m = new LinkedHashMap<String, Object>();
    m.put("seq", seq);
    m.put("ts", ts);
    m.put("level", level);
    m.put("src", source);
    m.put("msg", msg);
    return m;
  }

  public static LogEntry fromMap(Map<?, ?> m) {
    return new LogEntry(
        ((Number) m.get("seq")).longValue(),
        ((Number) m.get("ts")).longValue(),
        (String) m.get("level"),
        (String) m.get("src"),
        (String) m.get("msg"));
  }

  /** TRACE=0 .. ERROR=4; unknown levels rank as INFO so they are never hidden by a WARN filter by accident. */
  public static int levelRank(String level) {
    if (level == null) return 2;
    String l = level.toUpperCase(Locale.ROOT);
    if (l.equals("TRACE")) return 0;
    if (l.equals("DEBUG")) return 1;
    if (l.equals("WARN")) return 3;
    if (l.equals("ERROR")) return 4;
    return 2;
  }
}
