import com.tinkerpop.blueprints.Vertex
import groovy.transform.ToString
import org.joda.time.DateTime

// Recruitment source identifiers. Kept as a plain map of strings (not an enum) so the
// value written to player._system.recruitment.source is exactly the string sent to the
// client -- the frontend compares it directly (see BBMain.vue isProlific / isMTurk).
RecruitmentSource = [
  PROLIFIC: 'prolific',
  MTURK: 'mturk',
].asImmutable()

@ToString(includeNames = true)
class ProlificRegisterOpts {
}

@ToString(includeNames = true)
class MTurkRegisterOpts {
}

@ToString(includeNames = true)
class ProlificCompleteOpts {
  String completionCode
  String message
  DateTime completedAt
  Double bonus
  Boolean noFeedback
}

@ToString(includeNames = true)
class MTurkCompleteOpts {
  Double bonus
  Boolean noFeedback
  String reason
}


recruitment = [
  registerProlific: { Vertex v, Map opts = [:] ->
    ProlificRegisterOpts registerOpts = new ProlificRegisterOpts(opts)
    _ensureSystem(v, 'recruitment')
    v._system.recruitment.source = RecruitmentSource.PROLIFIC
  },
  registerMturk: { Vertex v, Map opts = [:] ->
    MTurkRegisterOpts registerOpts = new MTurkRegisterOpts(opts)
    _ensureSystem(v, 'recruitment')
    v._system.recruitment.source = RecruitmentSource.MTURK
  },
  // True once the player is completed; false, with nothing done, for someone who didn't come from
  // Prolific.
  completeProlific: { Vertex v, Map opts = [:] ->
    ProlificCompleteOpts completeOpts = new ProlificCompleteOpts(opts)
    _ensureSystem(v, 'recruitment')
    // Someone who didn't come through the Prolific link (the session's own sign-in link, a test
    // player, a computer player) has no Prolific submission to complete, so nothing is done for them
    // and the same call can be made for everyone. It used to throw, which stopped the step: in the
    // autopilot's session, the Prolific participants then got no code either.
    if (v._system.recruitment.source != RecruitmentSource.PROLIFIC) {
      play.Logger.info("recruitment.completeProlific: " + v.id + " didn't come from Prolific, so they get no completion code")
      return false
    }
    if (!completeOpts.completionCode) {
      throw new IllegalArgumentException("Completion code is required")
    }
    v._system.recruitment.completed = true
    v._system.recruitment.completedAt = DateTime.now()
    v._system.recruitment.completionCode = completeOpts.completionCode
    v._system.recruitment.bonus = completeOpts.bonus
    v._system.recruitment.message = completeOpts.message
    v._system.recruitment.noFeedback = completeOpts.noFeedback
    // Also in the session's data, which outlives the player (players and their end screens are kept
    // in memory only): someone who opens the study link again, e.g. after a server restart, is given
    // this code again instead of a seat in the next session (ClientLogin.prolificEntry). Recording
    // it never stands in the way of the completion itself.
    try {
      def data = [player: v.id, completionCode: completeOpts.completionCode]
      if (completeOpts.bonus != null) data.bonus = completeOpts.bonus
      eventTracker?.track("prolificComplete", data)
    } catch (Exception e) {
      play.Logger.error("recruitment: could not record the Prolific completion of " + v.id, e)
    }
    return true
  },
  completeMturk: { Vertex v, Map opts = [:] ->
    MTurkCompleteOpts completeOpts = new MTurkCompleteOpts(opts)
    _ensureSystem(v, 'recruitment')
    if (v._system.recruitment.source != RecruitmentSource.MTURK) {
      throw new IllegalArgumentException("Cannot complete mturk registration for non-mturk source")
    }
    if (!completeOpts.bonus) {
      throw new IllegalArgumentException("Bonus is required")
    }
    v._system.recruitment.completed = true
    v._system.recruitment.noFeedback = completeOpts.noFeedback
    v._system.recruitment.reason = completeOpts.reason
    v._system.recruitment.bonus = completeOpts.bonus
  }
]
