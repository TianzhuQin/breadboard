package security;

import play.Logger;
import play.mvc.Http;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;

/**
 * While a server has no account yet, whoever reached it first could create its first (researcher)
 * account. On the server's own computer that is fine; from anywhere else (another computer, or any
 * request through a reverse proxy) the first account also needs this code, which the server prints
 * in its window and its log the first time someone asks for it. It is forgotten once the account
 * exists.
 */
public final class SetupCode {

  private static String code;

  private SetupCode() {}

  /** The code, made (and printed) the first time it is needed. */
  public static synchronized String current() {
    if (code == null) {
      String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
      SecureRandom random = new SecureRandom();
      StringBuilder made = new StringBuilder();
      for (int i = 0; i < 8; i++) {
        if (i == 4) {
          made.append('-');
        }
        made.append(alphabet.charAt(random.nextInt(alphabet.length())));
      }
      code = made.toString();
      System.out.println();
      System.out.println("  Someone opened this new Breadboard from another computer. To create its first account");
      System.out.println("  there, enter this setup code:  " + code);
      System.out.println();
      Logger.warn("The first account can be created from another computer with the setup code " + code
          + " (shown until the account exists)");
    }
    return code;
  }

  /** After the first account was created. */
  public static synchronized void forget() {
    code = null;
  }

  /**
   * A request made on the server's own computer, not through a proxy: from a loopback address, with
   * none of the headers a reverse proxy adds (a proxy on the same computer forwards requests from
   * anywhere).
   */
  public static boolean isLocal(Http.Request request) {
    String from = request.remoteAddress();
    boolean loopback = "127.0.0.1".equals(from) || "::1".equals(from) || "0:0:0:0:0:0:0:1".equals(from)
        || (from != null && from.startsWith("127."));
    return loopback && request.getHeader("X-Forwarded-For") == null && request.getHeader("Forwarded") == null
        && request.getHeader("X-Real-IP") == null && request.getHeader("X-Forwarded-Host") == null;
  }

  /** Whether {@code given} is the code (ignoring case, spaces and dashes). */
  public static boolean matches(String given) {
    String expected;
    synchronized (SetupCode.class) {
      expected = code;
    }
    if (expected == null || given == null) {
      return false;
    }
    byte[] a = normalize(expected).getBytes(StandardCharsets.UTF_8);
    byte[] b = normalize(given).getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(a, b);
  }

  private static String normalize(String s) {
    return s.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
  }
}
