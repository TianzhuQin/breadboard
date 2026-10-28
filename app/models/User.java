package models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mindrot.jbcrypt.BCrypt;
import play.Logger;
import play.data.format.Formats;
import play.data.validation.Constraints;
import play.db.ebean.Model;
import play.libs.Json;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "users")
public class User extends Model {

  @Id
  @Constraints.Required
  @Formats.NonEmpty
  public String email;

  @Constraints.Required
  public String name;

  @Constraints.Required
  public String password;

  public String uid;

  @OneToOne
  public Language defaultLanguage;

  /*
   * Currently role can be admin or amt_admin, should expand this to include experiment runners vs.
   * experiment designers.
   */
  public String role;

  @ManyToMany
  public List<Experiment> ownedExperiments = new ArrayList<Experiment>();

  @OneToOne
  public Experiment selectedExperiment;

  @Column(columnDefinition = "text")
  public String currentScript;

  public Long experimentInstanceId = -1L;

  @JsonIgnore
  public static Model.Finder<String, User> find = new Model.Finder(String.class, User.class);

  public static int findRowCount() {
    return find.findRowCount();
  }

  public static List<User> findAll() {
    return find.all();
  }

  /**
   * The account with this email, ignoring case and surrounding spaces (a sign-in typed with a
   * capital letter used to be refused as a wrong password). An exact match wins, as an older
   * database may hold two addresses that differ only in case.
   */
  public static User findByEmail(String email) {
    if (email == null || email.trim().isEmpty()) {
      return null;
    }
    String wanted = email.trim();
    List<User> users = find.where().ieq("email", wanted).findList();
    for (User u : users) {
      if (wanted.equals(u.email)) {
        return u;
      }
    }
    return users.size() == 1 ? users.get(0) : null;
  }

  /**
   * The account signed in with this token, or null. Null for a null or blank token: Ebean turns
   * eq("uid", null) into "uid is null", which found an account that had never signed in (or was
   * added by SQL), so a request with no session at all was let in as that account.
   */
  public static User findByUID(String uid) {
    if (uid == null || uid.trim().isEmpty()) {
      return null;
    }
    return find.where().eq("uid", uid).findUnique();
  }

  public static User authenticate(String email, String password) {
    User user = findByEmail(email);
    if (user == null || password == null) {
      return null;
    }
    if (BCrypt.checkpw(password, user.password)) {
      return user;
    } else {
      return null;
    }
  }

  /** Whether {@code raw} is this account's password. False for a malformed stored hash, not an exception. */
  public boolean passwordMatches(String raw) {
    if (raw == null || password == null) {
      return false;
    }
    try {
      return BCrypt.checkpw(raw, password);
    } catch (IllegalArgumentException notAHash) {
      // e.g. a password the old websocket SaveUserSettings stored in plain text
      return false;
    }
  }

  /** Shortest password a password change accepts (the console's Settings page and the websocket). */
  public static final int PASSWORD_MIN_LENGTH = 8;

  /** Something@something.something, without spaces: enough to catch a typo or a name in the email field. */
  public static boolean looksLikeEmail(String email) {
    return email != null && email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
  }

  /** Store a new password, hashed (the caller saves the user). */
  public void changePassword(String raw) {
    this.password = BCrypt.hashpw(raw, BCrypt.gensalt());
  }

  public void setExperimentInstanceId(Long experimentInstanceId) {
    this.experimentInstanceId = experimentInstanceId;
  }

  public void setSelectedExperiment(Experiment selectedExperiment) {
    this.selectedExperiment = selectedExperiment;
  }

  public Experiment getExperiment() {
    return selectedExperiment;
  }

  public Experiment getExperimentByName(String name) {
    for (Experiment e : experiments()) {
      if (e.name.equals(name))
        return e;
    }
    return null;
  }

  /**
   * The experiments this account works on: every experiment on the server. Researchers on one
   * server work together (a lab's PI and assistants): any account can run any code on the server
   * from the script console, so separate lists never kept one account's studies from another's.
   * Run separate servers to keep studies apart. (ownedExperiments is still kept up to date.)
   */
  public List<Experiment> experiments() {
    return Experiment.findAll();
  }

  @JsonValue
  public ObjectNode toJson() {
    ObjectNode breadboard = Json.newObject();
    ObjectNode user = Json.newObject();

    ArrayList<String> experimentNames = new ArrayList<String>();

    user.put("email", this.email);

    user.put("defaultLanguage", (this.defaultLanguage == null) ? null : this.defaultLanguage.toJson());

    //user.put("experiments", Json.toJson(experimentNames));
    ArrayNode experiments = user.putArray("experiments");
    for (Experiment experiment: experiments()) {
      experiments.add(experiment.toJson());
    }

    if (selectedExperiment != null) {
      user.put("selectedExperiment", selectedExperiment.name);
    } else {
      Logger.debug("selectedExperiment == null?");
    }

    user.put("currentScript", currentScript);

    breadboard.put("user", user);

    if (selectedExperiment != null) {
      breadboard.put("experiment", selectedExperiment.toJson());
    }

    if (Experiment.TEST_INSTANCE_ID.equals(experimentInstanceId) && selectedExperiment != null) {
      breadboard.put("experimentInstance", selectedExperiment.getTestInstance().toJson());
    } else if (experimentInstanceId != null && experimentInstanceId != -1L) {
      ExperimentInstance ei = ExperimentInstance.findById(experimentInstanceId);
      Logger.debug("experimentInstanceId = " + experimentInstanceId.toString() + (ei == null ? " is null" : ""));
      if (ei != null) {
        breadboard.put("experimentInstance", ei.toJson());
      } else {
        experimentInstanceId = -1L;
        breadboard.put("experimentInstance", "");
      }
    } else {
      breadboard.put("experimentInstance", "");
    }

    // What the shared engine runs, which can be another experiment (or another account's session).
    try {
      breadboard.put("engine", ScriptBoard.engineJson());
    } catch (Exception e) {
      Logger.warn("Could not describe the engine for the User JSON: " + e);
      breadboard.putNull("engine");
    }

    return breadboard;
  }

  public String toString() {
    return "User(" + email + ")";
  }
}
