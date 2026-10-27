package models;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/** Timestamp formatting for exports. */
public final class Timestamps {

  private Timestamps() {}

  /**
   * The CSV exports' timestamp format, kept character-for-character for existing analysis scripts
   * (including the ':' before milliseconds), but now really in UTC. It used to print the server's
   * local time followed by a literal 'Z', so on any server not running in UTC the times were off by
   * the server's offset.
   */
  public static String csv(Date date) {
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss:SSS'Z'");
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    return format.format(date);
  }
}
