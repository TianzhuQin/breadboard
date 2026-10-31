package models;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.avaje.ebean.Expr;
import play.Logger;

public class ContentFetcher {
  public Experiment selectedExperiment;
  public Language defaultLanguage;
  public ContentFetcher(Experiment selectedExperiment) {
    this.selectedExperiment = selectedExperiment;
  }

  public void setDefaultLanguage(String languageString) {
    Language language = languageString == null ? null : Language.find.where()
        .or(Expr.eq("code", languageString),
            Expr.eq("name", languageString))
        .setMaxRows(1)
        .findUnique();
    if (language == null && languageString != null) {
      Logger.warn("c.setDefaultLanguage(\"" + languageString + "\"): there is no such language (use a code shown on the "
          + "Content page, such as eng); texts are shown in the language each was written in first");
    }

    this.defaultLanguage = language;
  }

  public String get(String name) {
    String languageCode = null;
    if (defaultLanguage != null) {
      languageCode = defaultLanguage.getCode();
    }

    return getContentString(name, languageCode);
  }

  /**
   * Interpolate numbered curly brackets in a string. Ex: ("These are 3 numbers: {0}, {1}, and {2}", [1, 2, 3]) -> "This is one: 1, 2, and 3"
   * @param content
   * @param parameters
   * @return
   */
  public String interpolate (String content, Object[] parameters) {
    String returnContent = content;
    for (int i = 0; i < parameters.length; i++) {
      returnContent = returnContent.replace("{" + i + "}", parameters[i].toString());
    }
    return returnContent;
  }

  public String get(String name, Object... parameters) {
    String content = this.get(name);
    return this.interpolate(content, parameters);
  }

  public String getTranslated(String name, String languageCode, Object... parameters) {
    String returnString = getContentString(name, languageCode);
    for (int i = 0; i < parameters.length; i++) {
      returnString = returnString.replace("{" + i + "}", parameters[i].toString());
    }
    return returnString;
  }

  /**
   * The text {@code name} in the language {@code languageCode}, or in the language a script chose with
   * setDefaultLanguage when that is null. A text with no version in that language at all is given in
   * English, or else in the language it was written in first: an experiment translated text by text
   * (the network game example, whose English texts a researcher translates one at a time) shows every
   * text meanwhile. That used to stop the step (a NullPointerException), or give "" in file mode. A
   * version there is, even an empty one, is given as it is: the Content page tells the researcher that
   * participants would see nothing for an empty version, and so they do. (For a while an empty version
   * was given in English, while a version such as "&lt;p&gt;&lt;/p&gt;", which the Content page counts
   * as empty too, was not.) Without a language, the text's first version, as before.
   */
  private String getContentString(String name, String languageCode) {
    // Accessing selectedExperiment.fileMode directly does not update due to some Ebean caching going on
    Experiment experiment = Experiment.findById(selectedExperiment.id);
    String returnString = "";
    if (experiment.fileMode) {
      List<Translation> versions = new ArrayList<>();
      for (Content c : experiment.getContent()) {
        if (c.name.equals(name)) {
          for (Translation t : c.translations) {
            if (languageCode == null) {
              returnString = t.getHtml();
              break;
            }
            versions.add(t);
          }
        }
      }
      if (languageCode != null) {
        returnString = html(inLanguage(versions, languageCode));
      }
    } else {
      Content c = Content.find.where()
          .eq("experiment_id", selectedExperiment.id)
          .eq("name", name)
          .setMaxRows(1)
          .findUnique();
      if (c != null) {
        if (languageCode == null) {
          Translation t = Translation.find.where()
              .eq("content_id", c.id)
              .setMaxRows(1)
              .findUnique();
          returnString = html(t);
        } else {
          List<Translation> versions = Translation.find.where()
              .eq("content_id", c.id)
              .orderBy("id")
              .findList();
          returnString = html(inLanguage(versions, languageCode));
        }
      } else {
        Logger.debug("getTranslated -> c == null");
      }
    }
    return returnString;
  }

  /**
   * The version in {@code languageCode}, as it is, even empty; with none in that language, the English
   * one; else the first; null when the text has no version at all.
   */
  private static Translation inLanguage(List<Translation> versions, String languageCode) {
    for (String code : new String[]{languageCode, "eng"}) {
      for (Translation t : versions) {
        Language l = t.getLanguage();
        if (l != null && code.equals(l.getCode())) {
          return t;
        }
      }
    }
    return versions.isEmpty() ? null : versions.get(0);
  }

  private static String html(Translation t) {
    return t == null || t.getHtml() == null ? "" : t.getHtml();
  }
}
