package games.strategy.triplea.formatter;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;

/**
 * Flames and Steel (request 32): the name people see for a player. The game property "FS Display
 * Name &lt;player&gt;" gives it (e.g. "America Atlantic" for Americans); without it the player's
 * own name is used. Internal names never change.
 */
@UtilityClass
public class PlayerNames {
  public static final String PREFIX = "FS Display Name ";

  public static String display(final @Nullable GamePlayer player) {
    if (player == null) {
      return "";
    }
    if (player.getData() == null) {
      return player.getName();
    }
    final String name = player.getData().getProperties().get(PREFIX + player.getName(), "");
    return name.isBlank() ? player.getName() : name;
  }

  /**
   * The text with every player that has a display name written with it: "Americans fire" becomes
   * "America Atlantic fire". Whole words only, the longest names first (so "AmericansPacific" is not
   * read as "Americans"), and never inside a file name such as "flags/Americans.png".
   */
  public static String inText(final @Nullable String text, final @Nullable GameState data) {
    if (text == null || text.isEmpty() || data == null) {
      return text;
    }
    final List<GamePlayer> players =
        data.getPlayerList().getPlayers().stream()
            .filter(p -> !data.getProperties().get(PREFIX + p.getName(), "").isBlank())
            .sorted(Comparator.comparingInt((GamePlayer p) -> -p.getName().length()))
            .toList();
    String result = text;
    for (final GamePlayer p : players) {
      if (!result.contains(p.getName())) {
        continue;
      }
      final Pattern pattern =
          Pattern.compile(
              "(?<![A-Za-z0-9_/])"
                  + Pattern.quote(p.getName())
                  + "(?![A-Za-z0-9_]|\\.(?:png|gif|jpg))");
      result =
          pattern.matcher(result).replaceAll(Matcher.quoteReplacement(display(p)));
    }
    return result;
  }
}
