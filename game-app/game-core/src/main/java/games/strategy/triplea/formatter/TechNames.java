package games.strategy.triplea.formatter;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.triplea.delegate.TechAdvance;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;

/**
 * Flames and Steel (request 37): the name people see for a tech. The game property "FS Tech Name
 * &lt;tech&gt; &lt;player&gt;" gives a nation its own name for it (e.g. "AI Mk. IV Radar" for the
 * British), "FS Tech Name &lt;tech&gt;" the name for everyone else; without either, the name is
 * built from the XML name ("AirborneInterceptRadar" becomes "Airborne Intercept Radar"). Internal
 * names never change.
 */
@UtilityClass
public class TechNames {
  public static final String PREFIX = "FS Tech Name ";

  /** The tech's name as this nation sees it (a null player means no nation in particular). */
  public static String display(final TechAdvance tech, final @Nullable GamePlayer player) {
    return display(tech.getData(), tech.getName(), player);
  }

  /** As {@link #display(TechAdvance, GamePlayer)}, from the tech's XML name. */
  public static String display(
      final @Nullable GameState data, final String techName, final @Nullable GamePlayer player) {
    if (data != null) {
      if (player != null) {
        final String own = data.getProperties().get(PREFIX + techName + " " + player.getName(), "");
        if (!own.isBlank()) {
          return own;
        }
      }
      final String general = data.getProperties().get(PREFIX + techName, "");
      if (!general.isBlank()) {
        return general;
      }
    }
    return readable(techName);
  }

  /**
   * The nations that have their own name for this tech, as "Nation: name" lines (display names for
   * the nations). Empty when no nation has one.
   */
  public static List<String> nationNames(
      final TechAdvance tech, final Collection<GamePlayer> players) {
    final List<String> lines = new ArrayList<>();
    if (tech.getData() == null) {
      return lines;
    }
    for (final GamePlayer player : players) {
      final String own =
          tech.getData().getProperties().get(PREFIX + tech.getName() + " " + player.getName(), "");
      if (!own.isBlank()) {
        lines.add(PlayerNames.display(player) + ": " + own);
      }
    }
    return lines;
  }

  /** "A, B and C" from the techs' names as this nation sees them. */
  public static String list(
      final Collection<TechAdvance> techs, final @Nullable GamePlayer player) {
    final List<String> names = techs.stream().map(t -> display(t, player)).toList();
    if (names.size() <= 1) {
      return String.join("", names);
    }
    return String.join(", ", names.subList(0, names.size() - 1))
        + " and "
        + names.get(names.size() - 1);
  }

  /**
   * A readable name from an XML name: "MechanizedInfantry" becomes "Mechanized Infantry",
   * "TypeXXIUboat" "Type XXI Uboat", "Propaganda1" "Propaganda 1". A unit type with a display name
   * keeps that name.
   */
  public static String readable(final String name) {
    if (UnitNames.isRenamed(name)) {
      return UnitNames.of(name);
    }
    final StringBuilder sb = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      final char c = name.charAt(i);
      if (i > 0 && sb.charAt(sb.length() - 1) != ' ') {
        final char prev = name.charAt(i - 1);
        final boolean nextLower =
            i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1));
        if ((Character.isUpperCase(c) && Character.isLowerCase(prev))
            || (Character.isUpperCase(c) && Character.isUpperCase(prev) && nextLower)
            || (Character.isDigit(c) && Character.isLowerCase(prev))) {
          sb.append(' ');
        }
      }
      sb.append(c);
    }
    return sb.toString();
  }
}
