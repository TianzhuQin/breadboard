package models;

public interface PlayerActionsInterface {
  void choose(String uid, String params);

  /** Id of the player a pending choice uid was offered to, or null if there is no such choice. */
  String ownerOf(String uid);

  void remove(String pid);

  void turnAIOff();

  void turnAIOn();
}
