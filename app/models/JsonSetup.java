package models;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import play.libs.Json;

/** JSON conversion used for everything sent to browsers. */
public final class JsonSetup {

  private JsonSetup() {}

  /**
   * Groovy's interpolated strings ("Score: ${score}") are GString objects, not java.lang.String.
   * Jackson serialized them as a bean ({"values": [...], "strings": [...], "bytes": ...}), so text
   * an experiment set with interpolation reached the browser as JSON garbage. Write them as the
   * string they stand for.
   */
  public static ObjectMapper mapper() {
    ObjectMapper mapper = new ObjectMapper();
    SimpleModule groovyStrings = new SimpleModule("GroovyStrings");
    groovyStrings.addSerializer(groovy.lang.GString.class, ToStringSerializer.instance);
    mapper.registerModule(groovyStrings);
    return mapper;
  }

  /**
   * Install a new mapper for Play's Json: at startup, and each time the script engine is replaced
   * (EngineCleanup), so the serializers the old mapper kept for the old engine's classes go with it.
   */
  public static void install() {
    Json.setObjectMapper(mapper());
  }
}
