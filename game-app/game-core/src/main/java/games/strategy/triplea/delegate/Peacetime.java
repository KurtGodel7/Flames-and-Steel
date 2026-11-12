package games.strategy.triplea.delegate;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import java.util.OptionalInt;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;

/**
 * Flames and Steel peacetime rules. A nation with a game property "FS Peacetime Income
 * &lt;player&gt;" (and optionally "FS Peacetime Manpower &lt;player&gt;") is at peace while it is
 * at war with no one. While at peace it collects exactly those amounts instead of its income and
 * bonuses, may not move units and takes no part in the air phase; research, purchase, placement and
 * APC production are as normal.
 */
@UtilityClass
public class Peacetime {
  public static final String INCOME = "FS Peacetime Income ";
  public static final String MANPOWER = "FS Peacetime Manpower ";
  public static final String MANPOWER_RESOURCE = "Manpower";

  /** The nation's fixed PU income while at peace, if it has the rule. */
  public static OptionalInt fixedIncome(final GamePlayer player) {
    return property(player, INCOME);
  }

  /** The nation's fixed manpower while at peace, if it has the rule. */
  public static OptionalInt fixedManpower(final GamePlayer player) {
    return property(player, MANPOWER);
  }

  private static OptionalInt property(final GamePlayer player, final String prefix) {
    final int value = player.getData().getProperties().get(prefix + player.getName(), -1);
    return value < 0 ? OptionalInt.empty() : OptionalInt.of(value);
  }

  /** Whether the nation has the peacetime rule and is at war with no one. */
  public static boolean isAtPeace(final @Nullable GamePlayer player) {
    if (player == null || player.isNull()) {
      return false;
    }
    if (fixedIncome(player).isEmpty() && fixedManpower(player).isEmpty()) {
      return false;
    }
    final GameState data = player.getData();
    return data.getPlayerList().getPlayers().stream()
        // request 65: war with a neutral-like player (Vichy) is not war
        .filter(other -> !NeutralLikePlayers.isListed(other))
        .noneMatch(other -> data.getRelationshipTracker().isAtWar(player, other));
  }

  /** "Americans are at peace: units can't move until they are at war." */
  public static String noMoveMessage(final GamePlayer player) {
    return who(player) + " at peace: units can't move until they are at war.";
  }

  /** "Americans are at peace: no part in the air phase until they are at war." */
  public static String noAirMessage(final GamePlayer player) {
    return who(player) + " at peace: no part in the air phase until they are at war.";
  }

  /** "Americans are", or with a display name (request 32) "America Pacific is". */
  private static String who(final GamePlayer player) {
    final String shown = games.strategy.triplea.formatter.PlayerNames.display(player);
    return shown.equals(player.getName()) ? shown + " are" : shown + " is";
  }
}
