package security;

import play.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Tokens with which a participant's finish screen sends its comment for the researcher (POST
 * /game/{experiment}/{session}/{client}/comment, controllers.ClientLogin.comment), for a week after
 * the screen asked for one. The seat's own token, which the comment was sent with alone, is kept in
 * memory and for a while only: the server forgets it once ten more sessions have ended
 * (ScriptBoard.releaseSeatsIfOver), and at a restart. A comment written after that was refused, and
 * lost without anyone knowing.
 *
 * <p>A token names the experiment, session and participant it is good for, and until when, signed
 * with application.secret as invitation links are: nothing is kept, so it works after a restart too.
 * The finish screen asks for one with the seat's own token (a researcher's preview gets none). There
 * are none while application.secret is a published default: anyone could make them then.
 */
public final class CommentTokens {

  /** How long a token is good for: a week, far longer than any session runs after its finish screen. */
  public static final long LIFETIME_MS = 7L * 24 * 60 * 60 * 1000;

  private static final String SEP = "\u0000";
  // Added to the clock; only tests change it.
  private static volatile long clockOffsetMs = 0L;

  private CommentTokens() {}

  /** A token, and until when it is good (milliseconds since the epoch). */
  public static final class Issued {
    public final String token;
    public final long expiresAt;

    Issued(String token, long expiresAt) {
      this.token = token;
      this.expiresAt = expiresAt;
    }
  }

  /**
   * A token for this participant's comments in this session of this experiment, good for LIFETIME_MS
   * from now; null when application.secret is a published default (or the signature can't be made).
   * The caller has checked the seat's own token.
   */
  public static Issued issue(String experimentId, String instanceId, String clientId) {
    long until = now() + LIFETIME_MS;
    String signature = sign(experimentId, instanceId, clientId, until);
    return signature == null ? null : new Issued(until + "." + signature, until);
  }

  /** Whether {@code token} is a token for exactly this participant's comments (see issue), and still good. */
  public static boolean verify(String experimentId, String instanceId, String clientId, String token) {
    if (token == null || experimentId == null || instanceId == null || clientId == null) {
      return false;
    }
    int dot = token.indexOf('.');
    if (dot <= 0) {
      return false;
    }
    long until;
    try {
      until = Long.parseLong(token.substring(0, dot));
    } catch (NumberFormatException e) {
      return false;
    }
    String expected = sign(experimentId, instanceId, clientId, until);
    return expected != null && until > now()
        && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.substring(dot + 1).getBytes(StandardCharsets.UTF_8));
  }

  private static String sign(String experimentId, String instanceId, String clientId, long until) {
    String secret = ParticipantAuth.applicationSecret();
    if (secret == null) {
      return null;
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] sig = mac.doFinal(("comment" + SEP + experimentId + SEP + instanceId + SEP + clientId + SEP + until)
          .getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
    } catch (Exception e) {
      Logger.error("Could not sign a comment token", e);
      return null;
    }
  }

  static long now() {
    return System.currentTimeMillis() + clockOffsetMs;
  }

  /** Test hook: move the clock on (or back) by this much. */
  public static void moveClockForTests(long ms) {
    clockOffsetMs += ms;
  }

  /** Test hook: set the clock right. */
  public static void reset() {
    clockOffsetMs = 0L;
  }
}
