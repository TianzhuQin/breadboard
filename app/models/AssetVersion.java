package models;

/**
 * A token that changes with every server start, added to the URLs of the front-end entry bundles
 * (?v=...) so browsers fetch them again after an update instead of running a cached old copy, which
 * would also ask for on-demand chunks that no longer exist.
 */
public final class AssetVersion {
  public static final String VALUE = Long.toString(System.currentTimeMillis(), 36);

  private AssetVersion() {}
}
