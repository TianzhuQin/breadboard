package controllers;

import models.Experiment;
import models.ProlificParticipants;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security;

/**
 * The Prolific participants of every session of an experiment, for the console's Recruitment page
 * (models.ProlificParticipants): who joined, who finished, their codes and bonuses. Every researcher
 * works on every experiment, so any signed-in account may read them.
 */
public class ProlificController extends Controller {

  @Security.Authenticated(Secured.class)
  public static Result participants(Long experimentId) {
    Experiment experiment = Experiment.findById(experimentId);
    if (experiment == null) {
      return notFound("Experiment not found");
    }
    return ok(ProlificParticipants.forExperiment(experiment.id));
  }
}
