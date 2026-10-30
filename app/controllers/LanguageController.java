package controllers;

import com.avaje.ebean.Ebean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.Experiment;
import models.Language;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security;

import java.util.*;

public class LanguageController extends Controller {

  /** Add a language for each one Java knows (by its ISO 639-2 code), all in one transaction. */
  public static List<Language> seedLanguages() {
    Locale[] locales = Locale.getAvailableLocales();
    Set<String> addedLanguages = new HashSet<>();
    List<Language> returnLanguages = new ArrayList<>();
    // (all or none: a request reading them meanwhile never finds half the list, English maybe not yet)
    Ebean.beginTransaction();
    try {
      for (Locale locale : locales) {
        if (locale.getISO3Language().length() > 0 &&
            locale.getDisplayLanguage().length() > 0 &&
            (! addedLanguages.contains(locale.getISO3Language() + '-' + locale.getDisplayLanguage()))) {
          addedLanguages.add(locale.getISO3Language() + '-' + locale.getDisplayLanguage());
          Language language = new Language();
          language.setName(locale.getDisplayLanguage());
          language.setCode(locale.getISO3Language());
          language.save();
          returnLanguages.add(language);
        }
      }
      Ebean.commitTransaction();
    } finally {
      Ebean.endTransaction();
    }
    return returnLanguages;
  }

  /**
   * The server's languages, added first when there are none: at a new database's first start
   * (Global.onStart), before any request. They used to be added only when the console's setup page
   * asked for the list, so a first account requested before that (by a script, say) was told the
   * server was still setting up, for as long as no page had asked. One at a time: two first requests
   * at once, e.g. the Welcome page and another tab, both found the table empty and added every
   * language twice.
   */
  public static synchronized List<Language> languages() {
    List<Language> languages = Language.findAll();
    if (languages.isEmpty()) {
      languages = seedLanguages();
    }
    return languages;
  }

  /** The language a first account gets when it names none: English, or else the first there is. */
  public static Language english() {
    List<Language> languages = languages();
    Language english = Language.findByIso3("eng");
    return english != null || languages.isEmpty() ? english : languages.get(0);
  }

  public static Result getLanguages() {
    ObjectNode returnJson = Json.newObject();
    ArrayNode jsonLanguages = returnJson.putArray("languages");
    List<Language> languages = languages();
    for (Language language : languages) {
      jsonLanguages.add(language.toJson());
    }
    return ok(returnJson);
  }

  @Security.Authenticated(Secured.class)
  public static Result addLanguage() {
    Long experimentId;
    Long languageId;
    JsonNode json = request().body().asJson();
    if(json == null) {
      return badRequest("Expecting Json data");
    } else {
      experimentId = json.findValue("experimentId").asLong();
      languageId = json.findValue("languageId").asLong();
    }

    Experiment experiment = Experiment.findById(experimentId);
    Language language = Language.findById(languageId);

    if (experiment == null || language == null) {
      return badRequest("Invalid experiment or language ID");
    }

    if (!experiment.languages.contains(language)) {
      experiment.languages.add(language);
      experiment.save();
    }

    return ok(language.toJson());
  }

  @Security.Authenticated(Secured.class)
  public static Result removeLanguage() {
    Long experimentId;
    Long languageId;
    JsonNode json = request().body().asJson();
    if(json == null) {
      return badRequest("Expecting Json data");
    } else {
      experimentId = json.findValue("experimentId").asLong();
      languageId = json.findValue("languageId").asLong();
    }

    Experiment experiment = Experiment.findById(experimentId);
    Language language = Language.findById(languageId);

    if (experiment == null || language == null) {
      return badRequest("Invalid experiment or language ID");
    }

    experiment.languages.remove(language);
    experiment.save();
    experiment.saveManyToManyAssociations("languages");

    return ok();
  }
}
