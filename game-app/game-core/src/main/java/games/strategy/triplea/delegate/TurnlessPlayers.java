package games.strategy.triplea.delegate;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import java.util.List;

/**
 * Flames and Steel (request 12): a player with no steps in the game sequence never takes a turn
 * (the Dutch). Such a player is not offered as a seat (it is played by "Does Nothing"), has no air
 * phase steps and is left out of loops that assume every player has a turn.
 */
public final class TurnlessPlayers {
  private TurnlessPlayers() {}

  /**
   * True for a real player (not the neutral player) with no step of its own, or a neutral-like
   * player (request 65, Vichy) whose only step collects income.
   */
  public static boolean isTurnless(final GamePlayer player) {
    if (player == null || player.isNull()) {
      return false;
    }
    // request 65: a neutral-like player (Vichy) only collects income: no seat either
    if (NeutralLikePlayers.isListed(player)) {
      return true;
    }
    final GameData data = player.getData();
    if (data == null) {
      return false;
    }
    for (final GameStep step : data.getSequence()) {
      if (player.equals(step.getPlayerId())) {
        return false;
      }
    }
    return true;
  }

  /** The players that take turns, in the order of the player list. */
  public static List<GamePlayer> withTurns(final GameData data) {
    return data.getPlayerList().getPlayers().stream().filter(p -> !isTurnless(p)).toList();
  }
}
