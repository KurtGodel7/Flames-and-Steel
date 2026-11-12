package games.strategy.triplea.delegate;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import javax.annotation.Nullable;

/**
 * Flames and Steel (request 65): players listed in the game property {@value #PROPERTY} (colon
 * separated, e.g. {@code Vichy}) are treated like the neutral player wherever the rules treat
 * neutral land specially: moving into and through it, blitzing, flying over it, the neutral charge,
 * the air system and the AI. They are at war with everyone (a plain "War" relationship in the XML)
 * so their units defend, and they never take an active turn: like the Dutch they have no seat and
 * are played by "Does Nothing".
 */
public final class NeutralLikePlayers {
  public static final String PROPERTY = "FS Neutral-Like Players";

  private NeutralLikePlayers() {}

  /** The neutral player, or a player listed in {@value #PROPERTY}. */
  public static boolean isNeutral(final @Nullable GamePlayer player) {
    return player != null && (player.isNull() || isListed(player));
  }

  /** A real player listed in {@value #PROPERTY} (not the neutral player itself). */
  public static boolean isListed(final @Nullable GamePlayer player) {
    if (player == null || player.isNull() || player.getData() == null) {
      return false;
    }
    final String text = player.getData().getProperties().get(PROPERTY, "");
    if (text.isBlank()) {
      return false;
    }
    for (final String name : text.split(":")) {
      if (name.trim().equals(player.getName())) {
        return true;
      }
    }
    return false;
  }

  /** Land or sea owned by the neutral player or a neutral-like player. */
  public static boolean ownsAsNeutral(final Territory t) {
    return isNeutral(t.getOwner());
  }
}
