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
      Logger.info("Client Login, ID: " + id);

      // TODO: make password a groovy closure to allow for hash functions or custom passwords
      if (!ScriptBoard.checkPassword(password)) {
        return "Invalid user or password";
      }
      return null;
    }
  }

  public static Result login(String experimentId, String experimentInstanceId) {
    if (!isTestSession(experimentInstanceId) && !ScriptBoard.runsSession(experimentId, experimentInstanceId)) {
      // Say so before anyone types their ID in (e.g. a participant sent here after a restart).
      return ok(notRunning(experimentId, experimentInstanceId));
    }
    return ok(clientLogin.render(experimentId, experimentInstanceId, Form.form(CLogin.class)));
  }

  /**
   * The page for a session that isn't running: it finished, or the engine no longer runs it (the
   * server restarted, or the researcher moved the engine to another session). A study's own link
   * (e.g. /play/{experimentId}/login, or the one on Prolific) leads to whichever session is open.
   */
  static play.api.templates.Html notRunning() {
    return participantMessage.render("This session isn't running right now",
        "It has finished, or was interrupted (for example by a server restart). If you were given a "
        + "link to the study, open it again to join the session that is open now; otherwise, please "
        + "contact the researcher.");
  }

  /** notRunning(), or, for a session that doesn't exist at all (a mistyped or cut-off link), invalidLink(). */
  static play.api.templates.Html notRunning(String experimentId, String experimentInstanceId) {
    return sessionExists(experimentId, experimentInstanceId) ? notRunning() : invalidLink();
  }

  /** invalidLink(), for Global's not-found handler. */
  public static play.api.templates.Html invalidLinkPage() {
    return invalidLink();
  }

  static play.api.templates.Html invalidLink() {
    return participantMessage.render("This link isn't valid",
        "There is no study session at this address. Please check that you opened the whole link you "
        + "were given, or contact the researcher.");
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
      return badRequest(clientLogin.render(experimentId, experimentInstanceId, loginForm));
    } else {
      Logger.debug("! loginForm.hasErrors()");
      // (Pasted ids often bring a space along: " p1 " would be another seat that looks like p1.)
      String clientId = loginForm.get().id == null ? null : loginForm.get().id.trim();
      if (clientId == null || clientId.isEmpty()) {
        loginForm.reject("Please enter your ID.");
        return badRequest(clientLogin.render(experimentId, experimentInstanceId, loginForm));
      }
      if (clientId.length() > 64) {
        // (the data's columns hold 255 characters; an id this long is a pasted mistake anyway)
        loginForm.reject("This ID is too long. Please use the ID you were given (at most 64 characters).");
        return badRequest(clientLogin.render(experimentId, experimentInstanceId, loginForm));
      }
      if (clientId.startsWith("_")) {
        // Ids starting with "_" are the computer players' (g.addAI makes _1, _2, ...): signing in as
        // one took over its seat.
        loginForm.reject("This ID is reserved. Please use the ID you were given.");
        return badRequest(clientLogin.render(experimentId, experimentInstanceId, loginForm));
      }
      if (isTestSession(experimentInstanceId)) {
        // The test session (0) has no seats for participants: it only exists while the engine has the
        // experiment loaded, and a seat token for it used to open the game of whatever session ran.
        loginForm.reject("This session isn't running right now. Please check the link you were given.");
        return forbidden(clientLogin.render(experimentId, experimentInstanceId, loginForm));
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
        return forbidden(participantMessage.render("The session has already started",
            "This session began before you arrived, so it can't take anyone new. Please contact the researcher."));
      }
      String token = ParticipantAuth.claim(experimentInstanceId, clientId);
      if (token == null) {
        loginForm.reject("This ID is already in use. If it is yours, open the study in the browser "
            + "where you first signed in; otherwise, check the ID you were given.");
        return forbidden(clientLogin.render(experimentId, experimentInstanceId, loginForm));
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
    if (pid == null || pid.trim().isEmpty()) {
      return badRequest(participantMessage.render("Missing Prolific ID",
          "This link must be opened from Prolific. Please return to Prolific and start the study again."));
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
    play.api.templates.Html takenPart = takenPartPage(experimentId, pid);
    if (takenPart != null) {
      return ok(takenPart);
    }
    if (!running) {
      return ok(participantMessage.render("This study is not running",
          "There is no session running at the moment. Please return the study on Prolific."));
    }
    if (!ScriptBoard.runsSession(experimentId, experimentInstanceId)) {
      // The engine runs another session (or none since a restart): this one's game is not there to join.
      return ok(participantMessage.render("This study is not running",
          "There is no session running at the moment. Please return the study on Prolific."));
    }
    if (closedToNewcomers(experimentInstanceId, clientId)) {
      return ok(participantMessage.render("The session has already started",
          "This session began before you arrived. Please return the study on Prolific."));
    }
    String token = ParticipantAuth.claim(experimentInstanceId, clientId);
    if (token == null) {
      return forbidden(participantMessage.render("Already joined",
          "You have already joined this study in another browser or tab. Please continue there."));
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
    // may have started, so it is no longer open to newcomers).
    Http.Cookie back = request().cookie(PLAY_COOKIE + experimentId);
    if (!another && back != null && back.value() != null && back.value().contains("/")) {
      String instanceId = back.value().substring(0, back.value().indexOf('/'));
      String clientId = urlDecode(back.value().substring(back.value().indexOf('/') + 1));
      if (!clientId.isEmpty() && ScriptBoard.runsSession(experimentId, instanceId)
          && ParticipantAuth.isClaimed(instanceId, clientId)) {
        if ("prolific".equals(entry)) {
          return redirect("/game/" + experimentId + "/" + instanceId + "/prolific" + query);
        }
        // Asked, not sent straight back: a researcher trying the study in several tabs of one
        // browser would otherwise land in the first tab's seat every time, and the tabs fight over it.
        return ok(participantSeat.render(clientId, routes.ClientController.index(experimentId, instanceId, clientId, null).url(),
            "/play/" + urlEncode(experimentId) + "/login?another=1"));
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
      return notFound(invalidLink());
    }
    if (open == null) {
      if ("prolific".equals(entry)) {
        // Someone who already took part is given their code again, not asked to return the study.
        // (With a session open, prolificEntry checks, after the redirect below.)
        String pid = request().getQueryString("PROLIFIC_PID");
        play.api.templates.Html takenPart = pid == null ? null : takenPartPage(experimentId, pid.trim());
        if (takenPart != null) {
          return ok(takenPart);
        }
        // A Prolific study's places are timed: waiting makes no sense, returning lets someone else in.
        return ok(participantMessage.render("No session is open right now",
            "There is no session taking participants at the moment. Please return the study on Prolific."));
      }
      return ok(participantMessage.render("No session is open right now",
          "There is no session taking participants at the moment. Please try again in a few minutes, or contact the researcher."));
    }
    return redirect("/game/" + experimentId + "/" + open.id + "/" + entry + query);
  }

  private static final String PLAY_COOKIE = "bb_seat_";

  /**
   * The page for a Prolific participant who already took part in this experiment (see
   * Recruitment.prolificCompletion): their completion code, if one was recorded, and the way back to
   * Prolific, as the participant page's own end card has. Null when they haven't: they may join.
   */
  private static play.api.templates.Html takenPartPage(String experimentId, String pid) {
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
    return participantTakenPart.render(completion.completionCode, prolific);
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

  /**
   * Finish a successful entry: store the seat token in an HttpOnly cookie scoped to this
   * instance, and redirect to the participant page with the token also in the URL fragment.
   * The fragment never reaches the server or its logs; the client moves it into session storage
   * and uses it when cookies are blocked (e.g. inside another site's iframe).
   */
  static Result enterGame(String experimentId, String experimentInstanceId, String clientId, String token, String connectionSpeed) {
    boolean secure = play.Play.application().configuration().getBoolean("breadboard.secureCookies", false);
    response().setCookie(ParticipantAuth.cookieName(experimentInstanceId), token, null,
        ParticipantAuth.cookiePath(experimentId, experimentInstanceId), null, secure, true);
    // Which seat this browser took, for the study's shared links (/play/...): someone who closes the
    // tab and opens the link again goes back to their seat, also once the game has started. Only
    // where to go: the seat's token stays in the cookie above.
    response().setCookie(PLAY_COOKIE + experimentId, experimentInstanceId + "/" + urlEncode(clientId), 24 * 3600,
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
