package games.strategy.triplea.ai;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import javax.annotation.Nullable;

/**
 * Flames and Steel (request 69): lets the AI-only test runner see what the AI tries. Nothing is
 * listening in normal games.
 */
public final class AiTestHooks {
  /** Receives every move the AI tried and the engine refused. */
  public interface RefusedMoveListener {
    void refused(GamePlayer player, MoveDescription move, String reason);
  }

  private static volatile @Nullable RefusedMoveListener refusedMoves;

  private AiTestHooks() {}

  public static void setRefusedMoveListener(final @Nullable RefusedMoveListener listener) {
    refusedMoves = listener;
  }

  /** Called by the AI when the move delegate refuses a move. */
  public static void moveRefused(
      final GamePlayer player, final MoveDescription move, final String reason) {
    final RefusedMoveListener listener = refusedMoves;
    if (listener != null) {
      try {
        listener.refused(player, move, reason);
      } catch (final RuntimeException e) {
        // a logging problem must never stop the game
      }
    }
  }
}
