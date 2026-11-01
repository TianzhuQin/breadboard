package models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringEscapeUtils;
import play.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Breadboard's own words to participants: its sign-in, holding and end pages, the end screens and
 * notices the server sends, the engine's waiting room and refusals (groovy/), in the language of the
 * session's texts (SessionLanguage: what its steps chose with c.setDefaultLanguage). Built in are
 * English (eng) and Simplified Chinese (zho); a session in any other language gets English. They used
 * to be English only, so a study written in Chinese showed its participants a mix of the two.
 *
 * <p>The words are in conf/i18n/&lt;language&gt;.json, which the participant page (frontend/client)
 * reads too: each text is written once, for the server and the page. A text has {name} parameters; a
 * text written as {"one": ..., "other": ...} is chosen by the number {n} ("1 person", "2 people"), as
 * English needs and Chinese doesn't. A text missing in Chinese is given in English.
 */
public final class SystemTexts {
  public static final String ENGLISH = "eng";
  public static final String CHINESE = "zho";
  /** The languages Breadboard has its own words in (conf/i18n). */
  public static final String[] LANGUAGES = {ENGLISH, CHINESE};

  // Each language's texts, read once: key -> a text, or {"one", "other"}.
  private static final Map<String, Map<String, JsonNode>> TEXTS = new ConcurrentHashMap<String, Map<String, JsonNode>>();
  // (each missing key named in the log once)
  private static final Set<String> MISSING = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

  private SystemTexts() {}

  /**
   * The language Breadboard's own words are in for a session whose texts are in {@code contentLanguage}
   * (a code the Content page shows, as c.setDefaultLanguage takes it): Chinese for zho (and zh, chi,
   * cmn), English for any other, or none.
   */
  public static String languageOf(String contentLanguage) {
    if (contentLanguage == null) {
      return ENGLISH;
    }
    String code = contentLanguage.trim().toLowerCase(java.util.Locale.ROOT);
    if (code.equals("zho") || code.equals("chi") || code.equals("cmn") || code.equals("zh") || code.startsWith("zh-")
        || code.startsWith("zh_")) {
      return CHINESE;
    }
    return ENGLISH;
  }

  /** The HTML lang of a page in that language: zh-CN or en. */
  public static String htmlLang(String contentLanguage) {
    return CHINESE.equals(languageOf(contentLanguage)) ? "zh-CN" : "en";
  }

  /**
   * The text {@code key} for a session whose texts are in {@code contentLanguage}, with {@code params}
   * (name, value, name, value, ...) filled in. As text: a page's template escapes it.
   */
  public static String text(String contentLanguage, String key, Object... params) {
    return format(template(languageOf(contentLanguage), key, params), pairs(params), false);
  }

  /** text(contentLanguage, key, params), with the parameters in a map (for Groovy: [n: 3]). */
  public static String text(String contentLanguage, String key, Map<String, ?> params) {
    Map<String, Object> values = params == null ? Collections.<String, Object>emptyMap() : new HashMap<String, Object>(params);
    return format(template(languageOf(contentLanguage), key, values), values, false);
  }

  /** text(contentLanguage, key), with no parameters (for Groovy, which can't tell the two above apart). */
  public static String text(String contentLanguage, String key) {
    return text(contentLanguage, key, new Object[0]);
  }

  /**
   * As text, to go into HTML (an end screen's player.text): the parameters are escaped, except those
   * given as {@link Html}. The texts themselves hold no markup.
   */
  public static String html(String contentLanguage, String key, Object... params) {
    return format(template(languageOf(contentLanguage), key, params), pairs(params), true);
  }

  /** Markup to put into html() as it is (a link). */
  public static final class Html {
    final String markup;

    public Html(String markup) {
      this.markup = markup;
    }

    public String toString() {
      return markup;
    }
  }

  /**
   * Sentences one after the other, as the language writes them: with a space between them in English,
   * none in Chinese. Empty ones are left out.
   */
  public static String sentences(String contentLanguage, String... parts) {
    String gap = gap(languageOf(contentLanguage));
    StringBuilder joined = new StringBuilder();
    for (String part : parts) {
      if (part == null || part.isEmpty()) {
        continue;
      }
      if (joined.length() > 0) {
        joined.append(gap);
      }
      joined.append(part);
    }
    return joined.toString();
  }

  /** An end screen's words as the participant page shows them: a heading and a paragraph. */
  public static String screen(String title, String paragraph) {
    return "<h3>" + title + "</h3><p>" + paragraph + "</p>";
  }

  /** The texts of a page, in the language of the session it is for: for the server's page templates. */
  public static Page page(String contentLanguage) {
    return new Page(contentLanguage);
  }

  /** The texts of one page in one language (app/views). */
  public static final class Page {
    private final String contentLanguage;

    Page(String contentLanguage) {
      this.contentLanguage = contentLanguage;
    }

    /** The language of the session's texts, as SessionLanguage knows it (null: none). */
    public String contentLanguage() {
      return contentLanguage;
    }

    public String t(String key) {
      return text(contentLanguage, key);
    }

    public String t(String key, String name, Object value) {
      return text(contentLanguage, key, name, value);
    }

    /** The page's lang attribute. */
    public String lang() {
      return htmlLang(contentLanguage);
    }

    /** The text {@code key} up to its parameter {@code name}, for a page that shows that value its own way. */
    public String before(String key, String name) {
      String template = template(languageOf(contentLanguage), key, new Object[0]);
      int at = template.indexOf("{" + name + "}");
      return at < 0 ? template : template.substring(0, at);
    }

    /** The text {@code key} after its parameter {@code name} (see before). */
    public String after(String key, String name) {
      String template = template(languageOf(contentLanguage), key, new Object[0]);
      String placeholder = "{" + name + "}";
      int at = template.indexOf(placeholder);
      return at < 0 ? "" : template.substring(at + placeholder.length());
    }
  }

  // --- the files ---

  private static String gap(String language) {
    JsonNode gap = texts(language).get("_sentenceGap");
    return gap != null && gap.isTextual() ? gap.textValue() : " ";
  }

  /** The template of {@code key} in {@code language} (else in English), its form chosen by the number n. */
  private static String template(String language, String key, Object[] params) {
    return template(language, key, pairs(params));
  }

  private static String template(String language, String key, Map<String, Object> params) {
    JsonNode node = texts(language).get(key);
    if (node == null && !ENGLISH.equals(language)) {
      language = ENGLISH;
      node = texts(ENGLISH).get(key);
    }
    if (node == null) {
      if (MISSING.add(key)) {
        Logger.warn("SystemTexts: there is no text \"" + key + "\" in conf/i18n");
      }
      return key;
    }
    if (node.isTextual()) {
      return node.textValue();
    }
    if (node.isObject()) {
      JsonNode form = node.get(pluralForm(language, params.get("n")));
      if (form == null || !form.isTextual()) {
        form = node.get("other");
      }
      return form != null && form.isTextual() ? form.textValue() : key;
    }
    return node.asText();
  }

  /** "one" or "other": English has 1 person and 2 people; Chinese has one form. */
  static String pluralForm(String language, Object n) {
    if (!ENGLISH.equals(language) || !(n instanceof Number)) {
      return "other";
    }
    double value = ((Number) n).doubleValue();
    return value == 1d ? "one" : "other";
  }

  private static Map<String, Object> pairs(Object[] params) {
    Map<String, Object> values = new LinkedHashMap<String, Object>();
    for (int i = 0; params != null && i + 1 < params.length; i += 2) {
      values.put(String.valueOf(params[i]), params[i + 1]);
    }
    return values;
  }

  /** {@code template} with each {name} of {@code values} filled in; others are left as they are. */
  static String format(String template, Map<String, ?> values, boolean html) {
    if (values.isEmpty() || template.indexOf('{') < 0) {
      return template;
    }
    StringBuilder out = new StringBuilder(template.length() + 16);
    int i = 0;
    while (i < template.length()) {
      char ch = template.charAt(i);
      int close = ch == '{' ? template.indexOf('}', i + 1) : -1;
      if (close > i) {
        String name = template.substring(i + 1, close);
        if (values.containsKey(name)) {
          Object value = values.get(name);
          String shown = value == null ? "" : value instanceof Number ? number((Number) value) : value.toString();
          out.append(html && !(value instanceof Html) ? StringEscapeUtils.escapeHtml4(shown) : shown);
          i = close + 1;
          continue;
        }
      }
      out.append(ch);
      i++;
    }
    return out.toString();
  }

  // A whole number without ".0" (Groovy's numbers may come as doubles).
  private static String number(Number n) {
    double d = n.doubleValue();
    return d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : n.toString();
  }

  /** The texts of {@code language} (English for one Breadboard has none in), read once. */
  static Map<String, JsonNode> texts(String language) {
    Map<String, JsonNode> texts = TEXTS.get(language);
    if (texts == null) {
      texts = read(language);
      TEXTS.put(language, texts);
    }
    return texts;
  }

  private static Map<String, JsonNode> read(String language) {
    Map<String, JsonNode> texts = new HashMap<String, JsonNode>();
    InputStream in = null;
    try {
      String name = "i18n/" + language + ".json";
      ClassLoader loader = SystemTexts.class.getClassLoader();
      in = loader == null ? null : loader.getResourceAsStream(name);
      if (in == null) {
        // (the repository's conf/, e.g. for scripts run without the server's classpath)
        File file = new File("conf/" + name);
        if (file.isFile()) {
          in = new FileInputStream(file);
        }
      }
      if (in == null) {
        Logger.error("SystemTexts: conf/" + name + " is missing, so participants see the texts' names");
        return texts;
      }
      JsonNode all = new ObjectMapper().readTree(new java.io.InputStreamReader(in, "UTF-8"));
      Iterator<Map.Entry<String, JsonNode>> fields = all.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        texts.put(field.getKey(), field.getValue());
      }
    } catch (Exception e) {
      Logger.error("SystemTexts: could not read conf/i18n/" + language + ".json", e);
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (Exception ignored) {}
      }
    }
    return texts;
  }
}
