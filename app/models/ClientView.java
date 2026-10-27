package models;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a participant's browser receives about other players.
 *
 * <p>Configured from experiment scripts through {@code g} (see graph.groovy):
 * <ul>
 *   <li>{@code g.setVisibility("ego")} (default): yourself, your neighbors and your own ties.
 *       {@code g.setVisibility("egoTies")}: also the ties among your neighbors.</li>
 *   <li>{@code g.setNeighborIds("alias")} (default): neighbors are identified by a random alias
 *       instead of their client id, which used to double as their login.
 *       {@code g.setNeighborIds("real")} restores the old behaviour.</li>
 *   <li>{@code g.hideFromNeighbors("score")} / {@code g.showToNeighbors("ai")}: adjust which
 *       vertex properties neighbors receive. Engine-internal properties (timers, AI flag, system
 *       state) are hidden by default; {@code private}, {@code text} and {@code choices} can never
 *       be shown.</li>
 * </ul>
 * Settings live with the script engine and are reset whenever it is.
 */
public final class ClientView {

  public enum Visibility { EGO, EGO_TIES }

  /** Never sent to anyone but the player themselves. */
  private static final Set<String> NEVER_SHOWN =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("private", "text", "choices")));
  /** Engine bookkeeping, hidden from neighbors unless a script opts in. */
  private static final Set<String> HIDDEN_BY_DEFAULT =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("_system", "timers", "ai", "lastupdated")));

  private static final String ALIAS_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  private static volatile Visibility visibility = Visibility.EGO;
  private static volatile boolean aliasNeighbors = true;
  private static final Set<String> extraHidden = ConcurrentHashMap.newKeySet();
  private static final Set<String> shownDefaults = ConcurrentHashMap.newKeySet();
  private static final ConcurrentHashMap<String, String> aliasByRealId = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, String> realIdByAlias = new ConcurrentHashMap<>();

  private ClientView() {}

  /** Back to defaults; called whenever the script engine is reset. */
  public static void reset() {
    visibility = Visibility.EGO;
    aliasNeighbors = true;
    extraHidden.clear();
    shownDefaults.clear();
    aliasByRealId.clear();
    realIdByAlias.clear();
  }

  public static Visibility getVisibility() {
    return visibility;
  }

  public static void setVisibility(String mode) {
    if (mode == null) {
      throw new IllegalArgumentException("visibility must be \"ego\" or \"egoTies\"");
    }
    switch (mode.toLowerCase()) {
      case "ego":
        visibility = Visibility.EGO;
        break;
      case "egoties":
      case "ego+ties":
        visibility = Visibility.EGO_TIES;
        break;
      default:
        throw new IllegalArgumentException("visibility must be \"ego\" or \"egoTies\", not \"" + mode + "\"");
    }
  }

  public static boolean aliasesNeighbors() {
    return aliasNeighbors;
  }

  public static void setNeighborIds(String mode) {
    if ("alias".equalsIgnoreCase(mode)) {
      aliasNeighbors = true;
    } else if ("real".equalsIgnoreCase(mode)) {
      aliasNeighbors = false;
    } else {
      throw new IllegalArgumentException("neighbor ids must be \"alias\" or \"real\", not \"" + mode + "\"");
    }
  }

  public static void hideFromNeighbors(String... keys) {
    for (String key : keys) {
      shownDefaults.remove(key);
      extraHidden.add(key);
    }
  }

  public static void showToNeighbors(String... keys) {
    for (String key : keys) {
      if (NEVER_SHOWN.contains(key)) {
        throw new IllegalArgumentException("\"" + key + "\" is only ever sent to the player themselves");
      }
      extraHidden.remove(key);
      if (HIDDEN_BY_DEFAULT.contains(key)) {
        shownDefaults.add(key);
      }
    }
  }

  /** Whether a neighbor may receive this vertex property. */
  public static boolean neighborMaySee(String key) {
    if (NEVER_SHOWN.contains(key) || extraHidden.contains(key)) {
      return false;
    }
    return !HIDDEN_BY_DEFAULT.contains(key) || shownDefaults.contains(key);
  }

  /** The id a viewer sees for another player: a stable random alias, or the real id. */
  public static String publicId(Object realId) {
    String id = String.valueOf(realId);
    if (!aliasNeighbors) {
      return id;
    }
    return aliasByRealId.computeIfAbsent(id, k -> {
      String alias;
      do {
        alias = randomAlias();
      } while (realIdByAlias.putIfAbsent(alias, k) != null);
      return alias;
    });
  }

  /**
   * The real id behind an id received from a browser. Aliases are translated; anything else
   * (a player's own id, or a real id when aliasing is off) is returned unchanged, so callers must
   * still check that the id exists and that the sender may refer to it.
   */
  public static String realId(String publicId) {
    if (publicId == null) {
      return null;
    }
    String real = realIdByAlias.get(publicId);
    return real != null ? real : publicId;
  }

  private static String randomAlias() {
    StringBuilder sb = new StringBuilder("n");
    for (int i = 0; i < 7; i++) {
      sb.append(ALIAS_ALPHABET.charAt(RANDOM.nextInt(ALIAS_ALPHABET.length())));
    }
    return sb.toString();
  }
}
