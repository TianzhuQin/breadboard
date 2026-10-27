package security;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tokens that let a researcher's console show a participant's page without the researcher's sign-in.
 *
 * <p>The console used to load participant pages in frames of its own origin, so the page's scripts
 * (the experiment's client HTML, written by whoever wrote the experiment) ran with the researcher's
 * session cookie and could call every console route. Now the console asks for a preview token
 * ({@code POST /preview-token}) and loads the page sandboxed, in an opaque origin without cookies,
 * with the token in the URL fragment; the page presents it the way a participant presents a seat
 * token (header, {@code bbt} query parameter).
 *
 * <p>A token is good for one session and client id (of one experiment) for 12 hours, and goes when
 * the account that asked for it signs out or changes its password. It never claims, changes or
 * releases the participant's own seat: preview tokens are kept apart from ParticipantAuth's seats, in
 * memory only (the game itself lives in memory, so a restart ends what a preview could show anyway).
 * As for seats, only a SHA-256 of each token is kept.
 */
public final class PreviewTokens {

  /** How long a preview token is good for. */
  public static final long LIFETIME_MS = 12L * 60 * 60 * 1000;
  // Tokens kept per account at most, the oldest going first. A console asks for one per preview it
  // opens, so this is far more than a day's work; it only keeps a runaway client from filling memory.
  static final int MAX_PER_ACCOUNT = 1000;

  private static final class Grant {
    final String account;
    final String experimentId;
    final String instanceId;
    final String clientId;
    final long expiresAt;

    Grant(String account, String experimentId, String instanceId, String clientId, long expiresAt) {
      this.account = account;
      this.experimentId = experimentId;
      this.instanceId = instanceId;
      this.clientId = clientId;
      this.expiresAt = expiresAt;
    }
  }

  /** A new token, and when it stops working (milliseconds since the epoch). */
  public static final class Issued {
    public final String token;
    public final long expiresAt;

    Issued(String token, long expiresAt) {
      this.token = token;
      this.expiresAt = expiresAt;
    }
  }

  // By the SHA-256 of the token.
  private static final ConcurrentHashMap<String, Grant> grants = new ConcurrentHashMap<>();
  // Added to the clock; only tests change it.
  private static volatile long clockOffsetMs = 0L;

  private PreviewTokens() {}

  /**
   * A new token for {@code account} (the researcher's email) to show the page of {@code clientId} in
   * that session. The caller has checked that the session is of that experiment.
   */
  public static Issued issue(String account, String experimentId, String instanceId, String clientId) {
    if (account == null || experimentId == null || instanceId == null || clientId == null) {
      throw new IllegalArgumentException("A preview token needs an account, an experiment, a session and a client id");
    }
    long now = now();
    forgetExpired(now);
    String token = ParticipantAuth.newToken();
    Grant grant = new Grant(account, experimentId, instanceId, clientId, now + LIFETIME_MS);
    grants.put(key(token), grant);
    keepNewest(account);
    return new Issued(token, grant.expiresAt);
  }

  /** True if {@code token} is a live preview token for exactly this page (experiment, session, client id). */
  public static boolean verify(String experimentId, String instanceId, String clientId, String token) {
    if (token == null || token.isEmpty() || experimentId == null || instanceId == null || clientId == null) {
      return false;
    }
    String key = key(token);
    Grant grant = grants.get(key);
    if (grant == null) {
      return false;
    }
    if (grant.expiresAt <= now()) {
      grants.remove(key, grant);
      return false;
    }
    return grant.experimentId.equals(experimentId) && grant.instanceId.equals(instanceId)
        && grant.clientId.equals(clientId);
  }

  /** Forget every token of an account: it signed out or changed its password. */
  public static void dropAccount(String account) {
    if (account == null) {
      return;
    }
    grants.values().removeIf(grant -> grant.account.equalsIgnoreCase(account));
  }

  /** Test hook: forget all preview tokens and set the clock right. */
  public static void reset() {
    grants.clear();
    clockOffsetMs = 0L;
  }

  /** Test hook: move the clock on (or back) by this much. */
  public static void moveClockForTests(long ms) {
    clockOffsetMs += ms;
  }

  private static long now() {
    return System.currentTimeMillis() + clockOffsetMs;
  }

  private static void forgetExpired(long now) {
    grants.values().removeIf(grant -> grant.expiresAt <= now);
  }

  private static void keepNewest(String account) {
    List<Map.Entry<String, Grant>> own = new ArrayList<>();
    for (Map.Entry<String, Grant> entry : grants.entrySet()) {
      if (entry.getValue().account.equalsIgnoreCase(account)) {
        own.add(entry);
      }
    }
    if (own.size() <= MAX_PER_ACCOUNT) {
      return;
    }
    own.sort(Comparator.comparingLong(entry -> entry.getValue().expiresAt));
    for (int i = 0; i < own.size() - MAX_PER_ACCOUNT; i++) {
      grants.remove(own.get(i).getKey(), own.get(i).getValue());
    }
  }

  private static String key(String token) {
    return Base64.getEncoder().encodeToString(ParticipantAuth.sha256(token));
  }
}
