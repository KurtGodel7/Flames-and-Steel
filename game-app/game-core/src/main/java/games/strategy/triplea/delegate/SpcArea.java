package games.strategy.triplea.delegate;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.apc.Apcs;
import games.strategy.triplea.formatter.PlayerNames;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Flames and Steel (request 61): a player with the game property "FS SPC Area &lt;player&gt;" (a
 * colon-separated territory list) may place new SPCs only in those territories. SPCs already on the
 * map elsewhere keep working. The optional property "FS SPC Area Text &lt;player&gt;" names the
 * area in the refusal. Factories are not affected (see {@link FactoryArea}).
 */
public final class SpcArea {
  public static final String PREFIX = "FS SPC Area ";
  public static final String TEXT_PREFIX = "FS SPC Area Text ";

  private SpcArea() {}

  /** The territory names the player may place SPCs in, if it is limited. */
  public static Optional<Set<String>> of(final GamePlayer player) {
    if (player == null || player.getData() == null) {
      return Optional.empty();
    }
    final String list = player.getData().getProperties().get(PREFIX + player.getName(), "");
    if (list.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(
        Arrays.stream(list.split(":"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet()));
  }

  public static boolean allows(final GamePlayer player, final Territory t) {
    return of(player).map(area -> area.contains(t.getName())).orElse(true);
  }

  public static boolean includesSpc(final Collection<Unit> units) {
    return units.stream().anyMatch(Apcs::isApc);
  }

  /** "Germany may place SPCs only in majority-German land that was German in 1913, ...". */
  public static String reason(final GamePlayer player) {
    final String text = player.getData().getProperties().get(TEXT_PREFIX + player.getName(), "");
    return PlayerNames.display(player)
        + " may place SPCs only in "
        + (text.isBlank() ? of(player).map(Set::size).orElse(0) + " listed territories" : text);
  }
}
