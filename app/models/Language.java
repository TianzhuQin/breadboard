package models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import play.db.ebean.Model;
import play.libs.Json;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Entity
@Table(name = "languages")
public class Language extends Model {
  @Id
  public Long id;

  public String code;

  public String name;

  @ManyToMany
  @JoinTable(name = "experiments_languages")
  public List<Experiment> experiments = new ArrayList<>();

  @JsonIgnore
  public static Finder<Long, Language> find = new Finder(Long.class, Language.class);

  @JsonIgnore
  public static List<Language> findAll() {
    return find.all();
  }

  @JsonIgnore
  public static Language findById(Long id) {
    return find.where().eq("id", id).findUnique();
  }

  @JsonIgnore
  /** The language with this code; the oldest, should two have it (findUnique threw then). */
  public static Language findByIso3(String iso3) {
    java.util.List<Language> found = find.where().eq("code", iso3).orderBy("id").findList();
    return found.isEmpty() ? null : found.get(0);
  }

  @JsonIgnore
  public static Language fromIso3(String iso3) {
    Locale locale = new Locale(iso3);
    Language language = new Language();
    language.setCode(iso3);
    language.setName(locale.getDisplayName(Locale.ENGLISH));
    return language;
  }

  public Language() {}

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  @JsonIgnore
  public ObjectNode toJson() {
    ObjectNode language = Json.newObject();
    language.put("id", id);
    language.put("code", code);
    language.put("name", name);
    return language;
  }

  @JsonIgnore
  public String toString() {
    return "Language(" + id + ")";
  }
}
