package games.strategy.triplea.delegate;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * Flames and Steel (request 12 section 5): a player with the game property "FS Factory Area
 * &lt;player&gt;" (a colon-separated territory list) may place new factories only in those
 * territories. Existing and captured factories elsewhere keep working. The optional property "FS
 * Factory Area Text &lt;player&gt;" gives the reason shown to the player.
 */
public final class FactoryArea {
  public static final String PREFIX = "FS Factory Area ";
  public static final String TEXT_PREFIX = "FS Factory Area Text ";

  private FactoryArea() {}

  /** The territory names the player may build factories in, if it is limited. */
  public static Optional<Set<String>> of(final GamePlayer player) {
    if (player == null || player.getData() == null) {
      return Optional.empty();
    }
    final String list = player.getData().getProperties().get(PREFIX + player.getName(), "");
    if (list.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(Set.of(list.split(":")));
  }

  public static boolean allows(final GamePlayer player, final Territory t) {
    return of(player).map(area -> area.contains(t.getName())).orElse(true);
  }

  /** Whether any of the units is a factory (a unit that can produce units). */
  public static boolean includesFactory(final Collection<Unit> units) {
    return units.stream().anyMatch(Matches.unitCanProduceUnits());
  }

  /** "Britain may build factories only in the Isles, Canada, India, Australia and South Africa". */
  public static String reason(final GamePlayer player) {
    final String text = player.getData().getProperties().get(TEXT_PREFIX + player.getName(), "");
    if (!text.isBlank()) {
      return text;
    }
    return player.getName()
        + " may build factories only in "
        + of(player).map(Set::size).orElse(0)
        + " listed territories";
  }
}
