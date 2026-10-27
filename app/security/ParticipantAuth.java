package security;

import play.Logger;
import play.Play;
import play.mvc.Http;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-participant credentials.
 *
 * <p>A participant's page used to be reachable by anyone who knew its URL,
 * {@code /game/{experiment}/{instance}/{clientId}/connected}, because the client id in the URL
 * was the only credential. Client ids are typed by participants, copied from recruitment
 * platforms, and were shown to every neighbor, so taking over someone else's seat was trivial.
 *
 * <p>Now every entry path (login form, AMT, Prolific, signed invitation link) claims the seat
 * and receives a random token bound to that one (instance, clientId) pair. The page data
 * ({@code /state}) and the websocket refuse requests that don't present it. The token travels
 * as an HttpOnly cookie, and also in the URL fragment of the first redirect so that pages
 * embedded in another site's iframe (where third-party cookies are usually blocked) can send it
 * explicitly instead.
 *
 * <p>Only a SHA-256 of each token is kept, in memory: the game itself lives in memory, so a
 * server restart ends the session either way.
 */
public final class ParticipantAuth {

  /** Request header a client uses to present its token when cookies are unavailable. */
  public static final String HEADER = "X-Breadboard-Token";
  /** Query parameter used for the websocket handshake, where headers can't be set. */
  public static final String QUERY_PARAM = "bbt";
  /** URL fragment key the client reads after the login redirect. */
  public static final String FRAGMENT_KEY = "bbt";

  private static final String COOKIE_PREFIX = "bb_pt_";
  private static final String SEP = "\u0000";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Set<String> PUBLIC_DEFAULT_SECRETS =
      new HashSet<>(Arrays.asList("changeme", "pleasechangethis"));

  private static final ConcurrentHashMap<String, byte[]> seats = new ConcurrentHashMap<>();

  private ParticipantAuth() {}

  public static String cookieName(String instanceId) {
    return COOKIE_PREFIX + instanceId;
  }

  public static String cookiePath(String experimentId, String instanceId) {
    return "/game/" + experimentId + "/" + instanceId;
  }

  /**
   * Claim a seat for a participant.
   *
   * @return a fresh token, or null if the seat already belongs to someone and
   *     {@code breadboard.participantLogin.allowReclaim} is off (the default)
   */
  public static String claim(String instanceId, String clientId) {
    String token = newToken();
    byte[] hash = sha256(token);
    String key = key(instanceId, clientId);
    if (allowReclaim()) {
      seats.put(key, hash);
      return token;
    }
    return seats.putIfAbsent(key, hash) == null ? token : null;
  }

  public static boolean isClaimed(String instanceId, String clientId) {
    return seats.containsKey(key(instanceId, clientId));
  }

  /** True if {@code token} is the current credential for this seat. Constant-time. */
  public static boolean verify(String instanceId, String clientId, String token) {
    if (token == null || token.isEmpty() || instanceId == null || clientId == null) {
      return false;
    }
    byte[] expected = seats.get(key(instanceId, clientId));
    return expected != null && MessageDigest.isEqual(expected, sha256(token));
  }

  /** Free a single seat, e.g. when an admin lets a participant log in again. */
  public static void release(String instanceId, String clientId) {
    seats.remove(key(instanceId, clientId));
  }

  /** Forget every seat of an instance, e.g. when it is stopped or deleted. */
  public static void releaseInstance(String instanceId) {
    String prefix = instanceId + SEP;
    seats.keySet().removeIf(k -> k.startsWith(prefix));
  }

  /** The token a request presents: header first, then query parameter, then cookie. */
  public static String presentedToken(Http.Request request, String instanceId) {
    String header = request.getHeader(HEADER);
    if (header != null && !header.isEmpty()) {
      return header;
    }
    String query = request.getQueryString(QUERY_PARAM);
    if (query != null && !query.isEmpty()) {
      return query;
    }
    Http.Cookie cookie = request.cookie(cookieName(instanceId));
    return cookie == null ? null : cookie.value();
  }

  /** True when the request authenticated with the cookie rather than an explicit token. */
  public static boolean presentedViaCookie(Http.Request request) {
    String header = request.getHeader(HEADER);
    String query = request.getQueryString(QUERY_PARAM);
    return (header == null || header.isEmpty()) && (query == null || query.isEmpty());
  }

  /**
   * Signature for an invitation link, so researchers can still hand each participant a personal
   * link. Unlike the bare page URL it can't be derived from the client id. Returns null when the
   * application secret is one of the public defaults, since anyone could then forge invitations.
   */
  public static String inviteSignature(String instanceId, String clientId) {
    String secret = applicationSecret();
    if (secret == null) {
      return null;
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] sig = mac.doFinal(("invite" + SEP + instanceId + SEP + clientId).getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(sig, 18));
    } catch (Exception e) {
      Logger.error("Could not compute invitation signature", e);
      return null;
    }
  }

  public static boolean verifyInvite(String instanceId, String clientId, String signature) {
    String expected = inviteSignature(instanceId, clientId);
    return expected != null && signature != null
        && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * The internal client id for a Prolific participant in an instance: the same PROLIFIC_PID always
   * maps to the same seat (so a participant can come back), but the id doesn't reveal the PID.
   */
  public static String prolificClientId(String instanceId, String prolificPid) {
    byte[] digest;
    String secret = applicationSecret();
    String input = "prolific" + SEP + instanceId + SEP + prolificPid;
    try {
      if (secret != null) {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        digest = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
      } else {
        digest = sha256(input);
      }
    } catch (Exception e) {
      digest = sha256(input);
    }
    StringBuilder id = new StringBuilder("pp-");
    for (int k = 0; k < 6; k++) {
      id.append(String.format("%02x", digest[k]));
    }
    return id.toString();
  }

  /** The configured application secret, or null if it is missing or a published default. */
  static String applicationSecret() {
    String secret = Play.application().configuration().getString("application.secret");
    if (secret == null || secret.isEmpty() || PUBLIC_DEFAULT_SECRETS.contains(secret)) {
      Logger.warn("application.secret is unset or a public default; invitation links are disabled");
      return null;
    }
    return secret;
  }

  static boolean allowReclaim() {
    return Play.application().configuration().getBoolean("breadboard.participantLogin.allowReclaim", false);
  }

  /** Test hook: forget all seats. */
  public static void reset() {
    seats.clear();
  }

  private static String key(String instanceId, String clientId) {
    return instanceId + SEP + clientId;
  }

  // Also used for preview tokens (PreviewTokens), which share the format but not the store.
  static String newToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
