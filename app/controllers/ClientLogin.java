package controllers;

import com.avaje.ebean.Ebean;
import com.avaje.ebean.SqlQuery;
import com.avaje.ebean.SqlRow;
import com.fasterxml.jackson.databind.JsonNode;
import models.*;
import play.Logger;
import play.data.Form;
import play.mvc.Controller;
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
    return ok(clientLogin.render(experimentId, experimentInstanceId, Form.form(CLogin.class)));
  }

  public static Result amtLogin(Long experimentId, Long experimentInstanceId, String hitId, String assignmentId, String workerId) {
    Experiment experiment = Experiment.findById(experimentId);
    ExperimentInstance experimentInstance = ExperimentInstance.findById(experimentInstanceId);
    AMTHit amtHit = experimentInstance.getHit();

    if (experiment == null || experimentInstance == null || experimentInstance.status != ExperimentInstance.Status.RUNNING) {
      return ok(amtError.render());
    }

    if (Boolean.TRUE.equals(experimentInstance.hasStarted) && (workerId == null || (!amtHit.hasWorker(workerId)))) {
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
    if (experimentInstance == null || experiment == null || experimentInstance.status != ExperimentInstance.Status.RUNNING) {
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
      String clientId = loginForm.get().id;
      if (clientId == null || clientId.trim().isEmpty()) {
        loginForm.reject("Please enter your ID.");
        return badRequest(clientLogin.render(experimentId, experimentInstanceId, loginForm));
      }
      String token = ParticipantAuth.claim(experimentInstanceId, clientId);
      if (token == null) {
        loginForm.reject("This ID is already in use.");
        return forbidden(clientLogin.render(experimentId, experimentInstanceId, loginForm));
      }
      return enterGame(experimentId, experimentInstanceId, clientId, token, null);
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
