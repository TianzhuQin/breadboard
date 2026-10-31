package controllers;

import com.avaje.ebean.Ebean;
import com.avaje.ebean.SqlQuery;
import com.avaje.ebean.SqlRow;
import com.fasterxml.jackson.databind.JsonNode;
import models.*;
import play.Logger;
import play.data.Form;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.WebSocket;
import security.CommentTokens;
import security.ParticipantAuth;
import views.html.*;
import java.util.List;

public class ClientLogin extends Controller {
  public static class AMTLogin {
    public Long experimentId;
    public Long experimentInstanceId;
    public String hitId;
    public String assignmentId;
    public String workerId;
    public String connectionSpeed;

    public String validate() {
      Logger.debug("validating experimentId: " + experimentId);
      Logger.debug("validating experimentInstanceId: " + experimentInstanceId);
      Logger.debug("validating hitId: " + hitId);
      Logger.debug("validating assignmentId: " + assignmentId);
      Logger.debug("validating workerId: " + workerId);
      Logger.debug("validating connectionSpeed: " + connectionSpeed);
      // TODO: save hitId with ExperimentInstance when creating it, then validate here.
      return null;
    }
  }

  public static class CLogin {
    public String id;
    public String password;
    public String experimentId;
    public String experimentInstanceId;

    public String validate() {
      // (Before any check, so only at debug level, shortened and without control characters: anyone
      // can post an ID of any length here.)
      if (Logger.isDebugEnabled()) {
        String shown = id == null ? "" : id.replaceAll("\\p{Cntrl}", "?");
        Logger.debug("Client Login, ID: " + (shown.length() > 64 ? shown.substring(0, 64) + "…" : shown));
      }

      // TODO: make password a groovy closure to allow for hash functions or custom passwords
      if (!ScriptBoard.checkPassword(password)) {
        // (the key of the words, which the sign-in page shows in the session's language)
        return "signIn.wrongPassword";
      }
      return null;
    }
  }

  public static Result login(String experimentId, String experimentInstanceId) {
    if (!isTestSession(experimentInstanceId) && !ScriptBoard.runsSession(experimentId, experimentInstanceId)) {
      // Say so before anyone types their ID in (e.g. a participant sent here after a restart).
      return ok(notRunning(experimentId, experimentInstanceId));
    }
    return ok(signInPage(experimentId, experimentInstanceId, Form.form(CLogin.class)));
  }

  /**
   * The sign-in page, in the language of the session's texts (SessionLanguage), as everything
   * Breadboard itself says to the session's participants (SystemTexts). Its errors are the keys of
   * their words (signIn.*).
   */
  static play.api.templates.Html signInPage(String experimentId, String experimentInstanceId, Form<CLogin> form) {
    return clientLogin.render(experimentId, experimentInstanceId, form, SystemTexts.page(SessionLanguage.of(experimentInstanceId)));
  }

  /** A page with a heading and a message, both texts of conf/i18n, in the language {@code lang} (a session's texts'). */
  static play.api.templates.Html message(String lang, String titleKey, String messageKey) {
    return participantMessage.render(SystemTexts.text(lang, titleKey), SystemTexts.text(lang, messageKey), SystemTexts.htmlLang(lang));
  }

  /**
   * The page for a session that isn't running: it finished, or the engine no longer runs it (the
   * server restarted, or the researcher moved the engine to another session). A study's own link
   * (e.g. /play/{experimentId}/login, or the one on Prolific) leads to whichever session is open.
   * In the language of that session's texts ({@code lang}).
   */
  static play.api.templates.Html notRunning(String lang) {
    return message(lang, "notRunning.pageTitle", "notRunning.page");
  }

  /** The heading and words of the Mechanical Turk page for a game that isn't running (amtError). */
  public static final String[] AMT_NOT_RUNNING = {"This game isn't running",
      "The game you are trying to join has finished, or is having technical difficulties. Please return "
      + "this HIT. We hope you will take part in another of our games in the future."};

  /** notRunning(), or, for a session that doesn't exist at all (a mistyped or cut-off link), invalidLink(). */
  static play.api.templates.Html notRunning(String experimentId, String experimentInstanceId) {
    return notRunning(experimentId, experimentInstanceId, null);
  }

  /**
   * As notRunning(experimentId, experimentInstanceId), but for a session a restart of the server cut
   * short (ScriptBoard.interruptedByRestart), the page that says the server stopped, as its pages are
   * told when they come back (ClientController.notRunningScreen): in the words for Prolific when
   * {@code clientId} entered it from there (null: someone not known, as on the sign-in page).
   */
  static play.api.templates.Html notRunning(String experimentId, String experimentInstanceId, String clientId) {
    if (!sessionExists(experimentId, experimentInstanceId)) {
      // (in the language of the experiment's sessions, if it has any)
      return invalidLink(SessionLanguage.ofExperiment(experimentId));
    }
    String lang = SessionLanguage.of(experimentInstanceId);
    if (ScriptBoard.interruptedByRestart(experimentInstanceId)) {
      boolean prolific = clientId != null && ScriptBoard.enteredFromProlific(Long.valueOf(experimentInstanceId), clientId);
      return participantMessage.render(SystemTexts.text(lang, "end.title"), ScriptBoard.serverStoppedText(prolific, lang),
          SystemTexts.htmlLang(lang));
    }
    return notRunning(lang);
  }

  /** invalidLink(), for Global's not-found handler: in English, as no session is known. */
  public static play.api.templates.Html invalidLinkPage() {
    return invalidLink(null);
  }

  static play.api.templates.Html invalidLink(String lang) {
    return message(lang, "link.invalidTitle", "link.invalid");
  }

  /** Whether the experiment has a session with this id. */
  static boolean sessionExists(String experimentId, String experimentInstanceId) {
    try {
      ExperimentInstance instance = ExperimentInstance.findById(Long.valueOf(experimentInstanceId));
      return instance != null && instance.experiment != null
          && instance.experiment.id.equals(Long.valueOf(experimentId));
    } catch (NumberFormatException e) {
      return false;
    }
  }

  public static Result amtLogin(Long experimentId, Long experimentInstanceId, String hitId, String assignmentId, String workerId) {
    Experiment experiment = Experiment.findById(experimentId);
    ExperimentInstance experimentInstance = ExperimentInstance.findById(experimentInstanceId);

    if (experiment == null || experimentInstance == null || experimentInstance.status != ExperimentInstance.Status.RUNNING) {
      return ok(amtError.render());
    }
    AMTHit amtHit = experimentInstance.getHit();

    if (Boolean.TRUE.equals(experimentInstance.hasStarted) && (workerId == null || amtHit == null || !amtHit.hasWorker(workerId))) {
      return ok(amtGameStarted.render());
    }

    if (experimentInstance.getHit() != null) {
      if (amtHit != null) {
        if (amtHit.isExtended()) {
          return ok(amtExtended.render(Experiment.findById(experimentId), ExperimentInstance.findById(experimentInstanceId), hitId, assignmentId, workerId, Form.form(AMTLogin.class)));
        }

        if ("any".equals(amtHit.disallowPrevious)) {
          int rowCount = AMTAssignment.findRowCountByWorkerId(workerId);
          if (rowCount > 0) {
            return ok(amtPreviousWorker.render());
          }
        } // if ("any".equals(amtHit.disallowPrevious)) {

        if ("type".equals(amtHit.disallowPrevious)) {
          String sql
              = " select a.worker_id "
              + " from amt_assignments a "
              + " join amt_hits h on a.amt_hit_id = h.id"
              + " join experiment_instances i on h.experiment_instance_id = i.id"
              + " join experiments e on i.experiment_id = e.id"
              + " where e.id = :eid"
              + " and a.worker_id = :wid"
              + " and a.assignment_completed = 1";

          SqlQuery sqlQuery = Ebean.createSqlQuery(sql);
          sqlQuery.setParameter("eid", experimentId);
          sqlQuery.setParameter("wid", workerId);

          List<SqlRow> list = sqlQuery.findList();

          if (list.size() > 0) {
            return ok(amtPreviousWorker.render());
          }
        } // if ("type".equals(amtHit.disallowPrevious)) {
      } // if (amtHit != null) {
    } // if (experimentInstance.getHit() != null) {
    return ok(amtClientLogin.render(Experiment.findById(experimentId), ExperimentInstance.findById(experimentInstanceId), hitId, assignmentId, workerId, Form.form(AMTLogin.class)));
  }

  public static Result dummyHit(String assignmentId, String sandboxString) {
    boolean sandbox = false;
    if (sandboxString != null && sandboxString.equals("true")) {
      sandbox = true;
    }
    return ok(amtDummy.render(assignmentId, sandbox));
  }

  public static Result amtAuthenticate(Long experimentId, Long experimentInstanceId, String hitId, String assignmentId, String workerId) {
    //Logger.info("Got to amtAuthenticate.");
    Form<AMTLogin> loginForm = Form.form(AMTLogin.class).bindFromRequest();
    String connectionSpeed = loginForm.get().connectionSpeed;

    ExperimentInstance experimentInstance = ExperimentInstance.findById(experimentInstanceId);
    Experiment experiment = Experiment.findById(experimentId);
    if (experimentInstance == null || experiment == null || experimentInstance.status != ExperimentInstance.Status.RUNNING
        || !ScriptBoard.runsSession(experimentId, experimentInstance)) {
      // (Also when the engine runs another session: this one's game is not there to join.)
      return ok(amtError.render());
    }

    AMTHit amtHit = null;
    if (experimentInstance.getHit() != null) {
      amtHit = experimentInstance.getHit();
    }

    if (amtHit == null) {
      return ok(amtError.render());
    }

    if (loginForm.hasErrors()) {
      Logger.debug("loginForm.hasErrors()");
      return badRequest(amtClientLogin.render(Experiment.findById(experimentId), experimentInstance, hitId, assignmentId, workerId, Form.form(AMTLogin.class)));
    } else {
      // If the game has already started, they can't join.
      if (Boolean.TRUE.equals(ExperimentInstance.findById(experimentInstanceId).hasStarted) && (workerId == null || (!amtHit.hasWorker(workerId)))) {
        Logger.debug("Got to amtAuthenticate -> amtGameStarted, workerId = " + workerId + ", AMTWorker.countByWorkerId(workerId) = " + AMTWorker.countByWorkerId(workerId));
        return ok(amtGameStarted.render());
      }

      Logger.debug("! loginForm.hasErrors()");
      String clientId = assignmentId;
      String token = ParticipantAuth.claim(experimentInstanceId.toString(), clientId);
      if (token == null) {
        // Someone already entered with this assignment.
        return ok(amtGameStarted.render());
      }
      if (!amtHit.hasWorker(workerId)) {
        AMTWorker amtWorker = new AMTWorker();
        amtWorker.workerId = workerId;
        amtHit.amtWorkers.add(amtWorker);
        amtHit.save();
      }
      return enterGame(experimentId.toString(), experimentInstanceId.toString(), clientId, token, connectionSpeed);
    }
  }

  public static Result authenticate(String experimentId, String experimentInstanceId) {
    Form<CLogin> loginForm = Form.form(CLogin.class).bindFromRequest();

    if (loginForm.hasErrors()) {
      Logger.debug("loginForm.hasErrors()");
      return badRequest(signInPage(experimentId, experimentInstanceId, loginForm));
    } else {
      Logger.debug("! loginForm.hasErrors()");
      // (Pasted ids often bring a space along: " p1 " would be another seat that looks like p1.)
      String clientId = loginForm.get().id == null ? null : loginForm.get().id.trim();
      // (the errors are the keys of their words, which the page shows in the session's language)
      if (clientId == null || clientId.isEmpty()) {
        loginForm.reject("signIn.enterId");
        return badRequest(signInPage(experimentId, experimentInstanceId, loginForm));
      }
      if (clientId.length() > 64) {
        // (the data's columns hold 255 characters; an id this long is a pasted mistake anyway)
        loginForm.reject("signIn.idTooLong");
        return badRequest(signInPage(experimentId, experimentInstanceId, loginForm));
      }
      if (Client.isComputer(clientId)) {
        // Ids starting with "_" are the computer players' (g.addAI makes _1, _2, ...): signing in as
        // one took over its seat.
        loginForm.reject("signIn.idReserved");
        return badRequest(signInPage(experimentId, experimentInstanceId, loginForm));
      }
      if (isTestSession(experimentInstanceId)) {
        // The test session (0) has no seats for participants: it only exists while the engine has the
        // experiment loaded, and a seat token for it used to open the game of whatever session ran.
        loginForm.reject("signIn.testSession");
        return forbidden(signInPage(experimentId, experimentInstanceId, loginForm));
      }
      if (!ScriptBoard.runsSession(experimentId, experimentInstanceId)) {
        // The engine runs another session (or none, since a restart): this one's game is not there
        // to join. Checked first: someone coming back to a session that started is told it's gone.
        return forbidden(notRunning(experimentId, experimentInstanceId));
      }
      if (ParticipantAuth.verify(experimentInstanceId, clientId, ParticipantAuth.presentedToken(request(), experimentInstanceId))) {
        // Coming back (e.g. after closing the tab) in the browser that holds this seat: straight in.
        return redirect(routes.ClientController.index(experimentId, experimentInstanceId, clientId, null).url());
      }
      if (closedToNewcomers(experimentInstanceId, clientId)) {
        return forbidden(message(SessionLanguage.of(experimentInstanceId), "started.title", "started.signIn"));
      }
      String token = ParticipantAuth.claim(experimentInstanceId, clientId);
      if (token == null) {
        loginForm.reject("signIn.idInUse");
        return forbidden(signInPage(experimentId, experimentInstanceId, loginForm));
      }
      return enterGame(experimentId, experimentInstanceId, clientId, token, null);
    }
  }

  /**
   * Entry from a Prolific study link:
   * /game/{experimentId}/{instanceId}/prolific?PROLIFIC_PID={{%PROLIFIC_PID%}}&STUDY_ID={{%STUDY_ID%}}&SESSION_ID={{%SESSION_ID%}}
   *
   * The PROLIFIC_PID is mapped to a pseudonymous client id, so it never appears in URLs or reaches
   * other participants. The PID, study and session ids are written to the researcher's event log
   * (prolificEntry) and to the player's own _system.recruitment for payment and the completion
   * screen. The same browser coming back returns to its seat; anyone else using the same link
   * is refused. Someone who already took part in the experiment, in this session or an earlier
   * one, is given their completion code again instead of a seat.
   */
  public static Result prolificEntry(String experimentId, String experimentInstanceId) {
    String pid = request().getQueryString("PROLIFIC_PID");
    String studyId = request().getQueryString("STUDY_ID");
    String sessionId = request().getQueryString("SESSION_ID");
    // (the language of the session's texts, which Breadboard's own words here follow)
    String lang = SessionLanguage.of(experimentInstanceId);
    if (pid == null || pid.trim().isEmpty()) {
      return badRequest(message(lang, "prolific.missingIdTitle", "prolific.missingId"));
    }
    pid = pid.trim();
    ExperimentInstance instance = null;
    try {
      instance = ExperimentInstance.findById(Long.valueOf(experimentInstanceId));
    } catch (NumberFormatException ignored) {}
    // (The test session, 0, has no row, so it is refused below too.)
    boolean running = instance != null && instance.status == ExperimentInstance.Status.RUNNING;

    String clientId = ParticipantAuth.prolificClientId(experimentInstanceId, pid);
    if (running && ParticipantAuth.verify(experimentInstanceId, clientId, ParticipantAuth.presentedToken(request(), experimentInstanceId))) {
      // The same browser coming back: it already holds this seat.
      return redirect(routes.ClientController.index(experimentId, experimentInstanceId, clientId, null).url());
    }
    // Someone who already took part (e.g. back for their code after a server restart, which forgets
    // seats and end screens) gets the code again: not a seat in this session, nor "return the study".
    play.api.templates.Html takenPart = takenPartPage(experimentId, pid, lang);
    if (takenPart != null) {
      return ok(takenPart);
    }
    if (!running) {
      return ok(message(lang, "prolific.notRunningTitle", "prolific.notRunning"));
    }
    if (!ScriptBoard.runsSession(experimentId, experimentInstanceId)) {
      // The engine runs another session (or none since a restart): this one's game is not there to join.
      return ok(message(lang, "prolific.notRunningTitle", "prolific.notRunning"));
    }
    if (closedToNewcomers(experimentInstanceId, clientId)) {
      return ok(message(lang, "started.title", "started.prolific"));
    }
    String token = ParticipantAuth.claim(experimentInstanceId, clientId);
    if (token == null) {
      return forbidden(message(lang, "prolific.joinedTitle", "prolific.joined"));
    }
    Recruitment.prolificEntry(clientId, pid, studyId, sessionId);
    java.util.LinkedHashMap<Object, Object> data = new java.util.LinkedHashMap<Object, Object>();
    data.put("clientId", clientId);
    data.put("prolificPid", pid);
    data.put("studyId", studyId);
    data.put("sessionId", sessionId);
    ScriptBoard.trackEvent("prolificEntry", data);
    return enterGame(experimentId, experimentInstanceId, clientId, token, null);
  }

  /**
   * Stable entry links that always lead to the session currently accepting participants, so a
   * study can publish one link (e.g. on Prolific) while sessions come and go:
   *   /play/{experimentId}/login
   *   /play/{experimentId}/prolific?PROLIFIC_PID=...&STUDY_ID=...&SESSION_ID=...
   */
  public static Result playLogin(String experimentId) {
    return toOpenSession(experimentId, "login");
  }

  public static Result playProlific(String experimentId) {
    return toOpenSession(experimentId, "prolific");
  }

  private static Result toOpenSession(String experimentId, String entry) {
    String query = request().uri().contains("?") ? request().uri().substring(request().uri().indexOf('?')) : "";
    // "Join as someone else" (participantSeat): a new seat, whatever this browser has already.
    boolean another = "login".equals(entry) && "1".equals(request().getQueryString("another"));
    // Coming back to a session this browser has a seat in, while the engine still runs it (its game
    // may have started, so it is no longer open to newcomers). Not to a seat its game left out (the
    // room was full, or it started without them), which only says so: they are someone new for the
    // next session.
    Http.Cookie back = request().cookie(PLAY_COOKIE + experimentId);
    if (!another && back != null && back.value() != null && back.value().contains("/")) {
      String instanceId = back.value().substring(0, back.value().indexOf('/'));
      String clientId = urlDecode(back.value().substring(back.value().indexOf('/') + 1));
      if (!clientId.isEmpty() && ScriptBoard.runsSession(experimentId, instanceId)
          && ParticipantAuth.isClaimed(instanceId, clientId) && !ScriptBoard.leftOutOf(instanceId, clientId)) {
        if ("prolific".equals(entry)) {
          return redirect("/game/" + experimentId + "/" + instanceId + "/prolific" + query);
        }
        // Asked, not sent straight back: a researcher trying the study in several tabs of one
        // browser would otherwise land in the first tab's seat every time, and the tabs fight over it.
        return ok(participantSeat.render(clientId, routes.ClientController.index(experimentId, instanceId, clientId, null).url(),
            "/play/" + urlEncode(experimentId) + "/login?another=1", SystemTexts.page(SessionLanguage.of(instanceId))));
      }
    }
    if (another) {
      query = "";
    }
    ExperimentInstance open = null;
    Experiment experiment = null;
    try {
      experiment = Experiment.findById(Long.valueOf(experimentId));
      open = Autopilot.openInstance(Long.valueOf(experimentId));
    } catch (NumberFormatException ignored) {}
    if (experiment == null) {
      return notFound(invalidLink(null));
    }
    if (open == null) {
      boolean prolific = "prolific".equals(entry);
      // No session leads anywhere yet: the language of the experiment's most recent session's texts.
      String lang = SessionLanguage.ofExperiment(experiment.id);
      if (prolific) {
        // Someone who already took part is given their code again, not asked to return the study.
        // (With a session open, prolificEntry checks, after the redirect below.)
        String pid = request().getQueryString("PROLIFIC_PID");
        play.api.templates.Html takenPart = pid == null ? null : takenPartPage(experimentId, pid.trim(), lang);
        if (takenPart != null) {
          return ok(takenPart);
        }
      }
      if (Autopilot.opensNextSoon(experiment.id)) {
        // The autopilot opens the next session by itself, within seconds of the one under way (its
        // game, or a full room counting down) ending: a page that waits for it, asking again by
        // itself, rather than one that turned people away until they tried again by hand. Not while
        // something keeps it from opening one (the disk almost full, another experiment's session
        // in the engine): the page promised one "in a moment" then, for as long as that lasted.
        return holding(experiment.id, prolific);
      }
      if (prolific) {
        // A Prolific study's places are timed: waiting makes no sense, returning lets someone else in.
        return ok(message(lang, "noSession.title", "noSession.prolific"));
      }
      return ok(message(lang, "noSession.title", "noSession.text"));
    }
    return redirect("/game/" + experimentId + "/" + open.id + "/" + entry + query);
  }

  /** How often (seconds) the holding page asks again whether a session is open. */
  static final int HOLDING_RETRY_SECONDS = 4;

  /**
   * The page for someone who opens the study's link while the autopilot has no session open but opens
   * the next by itself, and nothing keeps it from doing so (Autopilot.opensNextSoon: between sessions,
   * during a game, or while a full room counts down): it says a session is in progress (or opens in a
   * moment) and the next opens when it ends, and loads the same link again every few seconds, which
   * goes into the session once one is open. A {@code prolific} participant may keep it open, or
   * return the study if they can't wait.
   */
  static Result holding(Long experimentId, boolean prolific) {
    boolean underWay = Autopilot.sessionUnderWay(experimentId);
    // (in the language of the session it leads to, not open yet: the experiment's most recent one's)
    SystemTexts.Page texts = SystemTexts.page(SessionLanguage.ofExperiment(experimentId));
    String title = texts.t(underWay ? "holding.underWayTitle" : "holding.soonTitle");
    String message = texts.t((underWay ? "holding.underWay" : "holding.soon") + (prolific ? "Prolific" : ""));
    response().setHeader("Cache-Control", "no-store");
    return ok(participantHolding.render(title, message, HOLDING_RETRY_SECONDS, texts));
  }

  private static final String PLAY_COOKIE = "bb_seat_";

  /** The longest comment kept (characters); a longer one is cut there. */
  static final int COMMENT_LIMIT = 10000;

  /**
   * A participant's comment for the researcher, from the Prolific finish screen's "Comments for the
   * researcher": {@code POST /game/{experimentId}/{instanceId}/{clientId}/comment} with
   * {@code {"text": "..."}} as JSON (no form: a page of another site can't send this) and the seat's
   * token (its cookie, or the X-Breadboard-Token header), or the comment token the finish screen got
   * for it (commentToken) as {@code "token"} in the JSON. Recorded in that participant's own session
   * as participantComment (player, text), whatever the engine runs now: the screen comes once their
   * game is over, and the autopilot may have opened the next session since. It used to go to the
   * engine as a message nothing listened to, and was lost. Only the seat's own token counts, or a
   * comment token, which only the seat's own token gets: not a researcher's preview. The comment
   * token is good for a week, also once the server has forgotten the seat (ten more sessions have
   * ended since, or it restarted), which refused the comment: the finish screen then went on to
   * Prolific, and the comment was lost without anyone knowing. The same comment from the same
   * participant again is kept once (alreadyKept): the finish screen sends it again (Try again) when the
   * answer to the first was lost on the way, and the server may have kept that one. Answers 204 (also
   * for an empty comment, which records nothing, and for one kept already), 403 without either token,
   * 400 without a text, 404 for a session that isn't this experiment's.
   */
  public static Result comment(String experimentId, String experimentInstanceId, String clientId) {
    JsonNode body = request().body().asJson();
    JsonNode commentToken = body == null ? null : body.get("token");
    if (!ParticipantAuth.verify(experimentInstanceId, clientId, ParticipantAuth.presentedToken(request(), experimentInstanceId))
        && !(commentToken != null && commentToken.isTextual()
             && CommentTokens.verify(experimentId, experimentInstanceId, clientId, commentToken.textValue()))) {
      return forbidden(commentRefused("This page can't send comments for this participant."));
    }
    JsonNode text = body == null ? null : body.get("text");
    if (text == null || !text.isTextual()) {
      return badRequest(commentRefused("Send {\"text\": \"...\"} as JSON."));
    }
    ExperimentInstance instance = null;
    if (sessionExists(experimentId, experimentInstanceId)) {
      instance = ExperimentInstance.findById(Long.valueOf(experimentInstanceId));
    }
    if (instance == null) {
      return notFound(commentRefused("There is no such session."));
    }
    String comment = text.textValue().trim();
    if (comment.isEmpty()) {
      return noContent();
    }
    if (comment.length() > COMMENT_LIMIT) {
      // (not between the two halves of a character outside the BMP, such as an emoji)
      int end = Character.isHighSurrogate(comment.charAt(COMMENT_LIMIT - 1)) ? COMMENT_LIMIT - 1 : COMMENT_LIMIT;
      comment = comment.substring(0, end);
    }
    java.util.LinkedHashMap<Object, Object> data = new java.util.LinkedHashMap<Object, Object>();
    data.put("player", clientId);
    data.put("text", comment);
    // (one at a time for each participant, so that the same comment twice at once is kept once too)
    synchronized (commentLock(instance.id, clientId)) {
      if (alreadyKept(instance.id, clientId, comment)) {
        return noContent();
      }
      if (EventTracker.record(instance, "participantComment", data) == null) {
        return internalServerError(commentRefused("The comment could not be saved."));
      }
    }
    return noContent();
  }

  // What comment holds while it looks for a participant's comment and keeps it: one of these, by the
  // session and the participant.
  private static final Object[] COMMENT_LOCKS = new Object[32];
  static {
    for (int i = 0; i < COMMENT_LOCKS.length; i++) {
      COMMENT_LOCKS[i] = new Object();
    }
  }

  private static Object commentLock(Long instanceId, String clientId) {
    return COMMENT_LOCKS[((instanceId + "/" + clientId).hashCode() & 0x7fffffff) % COMMENT_LOCKS.length];
  }

  /**
   * Whether the session already keeps this comment from this participant (participantComment with
   * this player and text, as comment keeps it): their finish screen sends it again (Try again) when
   * the answer to the first was lost on the way (a connection that dropped while the server kept it),
   * and the researcher got it twice. False when it can't be told: then it is kept again, not lost.
   */
  static boolean alreadyKept(Long instanceId, String clientId, String comment) {
    try {
      List<Event> kept = Event.find.fetch("eventData").where().eq("experimentInstance.id", instanceId)
          .eq("name", "participantComment").findList();
      for (Event event : kept) {
        String player = null;
        String text = null;
        for (EventData d : event.eventData) {
          if ("player".equals(d.name)) {
            player = d.value;
          } else if ("text".equals(d.name)) {
            text = d.value;
          }
        }
        if (clientId.equals(player) && comment.equals(text)) {
          return true;
        }
      }
    } catch (RuntimeException e) {
      Logger.warn("Could not tell whether session " + instanceId + " already keeps this comment of " + clientId + ": " + e);
    }
    return false;
  }

  /**
   * The token the Prolific finish screen sends its comment with (comment, as {@code "token"}), asked
   * for as the screen appears: {@code GET /game/{experimentId}/{instanceId}/{clientId}/comment} with
   * the seat's own token, not a researcher's preview's. Good for this participant's comments for a
   * week (security.CommentTokens), also once the server has forgotten the seat's token. Answers
   * {@code {"token", "expiresAt"}} (both null while application.secret is a published default: the
   * seat's token alone counts then), 403 without the seat's token, 404 for a session that isn't this
   * experiment's.
   */
  public static Result commentToken(String experimentId, String experimentInstanceId, String clientId) {
    response().setHeader("Cache-Control", "no-store");
    if (!ParticipantAuth.verify(experimentInstanceId, clientId, ParticipantAuth.presentedToken(request(), experimentInstanceId))) {
      return forbidden(commentRefused("This page can't send comments for this participant."));
    }
    if (!sessionExists(experimentId, experimentInstanceId)) {
      return notFound(commentRefused("There is no such session."));
    }
    CommentTokens.Issued issued = CommentTokens.issue(experimentId, experimentInstanceId, clientId);
    com.fasterxml.jackson.databind.node.ObjectNode answer = play.libs.Json.newObject();
    if (issued == null) {
      answer.putNull("token");
      answer.putNull("expiresAt");
    } else {
      answer.put("token", issued.token);
      answer.put("expiresAt", models.Timestamps.iso(new java.util.Date(issued.expiresAt)));
    }
    return ok(answer);
  }

  private static com.fasterxml.jackson.databind.node.ObjectNode commentRefused(String why) {
    com.fasterxml.jackson.databind.node.ObjectNode error = play.libs.Json.newObject();
    error.put("error", why);
    return error;
  }

  /**
   * The page for a Prolific participant who already took part in this experiment (see
   * Recruitment.prolificCompletion): their completion code, if one was recorded, and the way back to
   * Prolific, as the participant page's own end card has. Null when they haven't: they may join.
   */
  private static play.api.templates.Html takenPartPage(String experimentId, String pid, String lang) {
    Recruitment.Completion completion;
    try {
      completion = Recruitment.prolificCompletion(Long.valueOf(experimentId), pid);
    } catch (NumberFormatException notAnId) {
      return null;
    }
    if (completion == null) {
      return null;
    }
    String prolific = completion.completionCode == null ? "https://app.prolific.com/"
        : "https://app.prolific.com/submissions/complete?cc=" + urlEncode(completion.completionCode);
    return participantTakenPart.render(completion.completionCode, prolific, SystemTexts.page(lang));
  }

  private static String urlEncode(String s) {
    try {
      return java.net.URLEncoder.encode(s, "UTF-8");
    } catch (java.io.UnsupportedEncodingException e) {
      return s;
    }
  }

  private static String urlDecode(String s) {
    try {
      return java.net.URLDecoder.decode(s, "UTF-8");
    } catch (Exception e) {
      return "";
    }
  }

  /** Whether this is the id of an experiment's test session (0), which takes no participants. */
  static boolean isTestSession(String experimentInstanceId) {
    try {
      return Experiment.TEST_INSTANCE_ID.equals(Long.valueOf(experimentInstanceId));
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /**
   * True when the instance has started and this id doesn't already hold a seat in it, so a new
   * participant would get a page that can never join the game.
   */
  static boolean closedToNewcomers(String experimentInstanceId, String clientId) {
    try {
      ExperimentInstance instance = ExperimentInstance.findById(Long.valueOf(experimentInstanceId));
      return instance != null && Boolean.TRUE.equals(instance.hasStarted)
          && !ParticipantAuth.isClaimed(experimentInstanceId, clientId);
    } catch (NumberFormatException e) {
      return false;
    }
  }

  // How long a browser keeps the cookies of the seat it took (seconds).
  static final int SEAT_COOKIE_SECONDS = 24 * 3600;

  /**
   * Finish a successful entry: store the seat token in an HttpOnly cookie scoped to this
   * instance, and redirect to the participant page with the token also in the URL fragment.
   * The fragment never reaches the server or its logs; the client moves it into session storage
   * and uses it when cookies are blocked (e.g. inside another site's iframe).
   */
  static Result enterGame(String experimentId, String experimentInstanceId, String clientId, String token, String connectionSpeed) {
    boolean secure = play.Play.application().configuration().getBoolean("breadboard.secureCookies", false);
    // For a day, as the cookie below: it used to go when the browser closed, so someone whose browser
    // was closed or killed (a phone does that) could not get back to their seat.
    response().setCookie(ParticipantAuth.cookieName(experimentInstanceId), token, SEAT_COOKIE_SECONDS,
        ParticipantAuth.cookiePath(experimentId, experimentInstanceId), null, secure, true);
    // Which seat this browser took, for the study's shared links (/play/...): someone who closes the
    // tab and opens the link again goes back to their seat, also once the game has started. Only
    // where to go: the seat's token stays in the cookie above.
    response().setCookie(PLAY_COOKIE + experimentId, experimentInstanceId + "/" + urlEncode(clientId), SEAT_COOKIE_SECONDS,
        "/play/" + experimentId, null, secure, true);
    String url = routes.ClientController.index(experimentId, experimentInstanceId, clientId, connectionSpeed).url();
    return redirect(url + "#" + ParticipantAuth.FRAGMENT_KEY + "=" + token);
  }

  /**
   * Handle the script websocket.
   */
  public static WebSocket<JsonNode> connect() {
    return new WebSocket<JsonNode>() {
      // Called when the Websocket Handshake is done.
      public void onReady(final WebSocket.In<JsonNode> in, final WebSocket.Out<JsonNode> out) {
        try {
          //TODO
          //ScriptBoard.connect(in, out);
        } catch (Exception ex) {
          ex.printStackTrace();
        }
      }
    };
  }

}
