package games.strategy.triplea.delegate;

import games.strategy.engine.data.GamePlayer;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/**
 * Flames and Steel mobilization (request 19). A nation with a game property "FS Mobilization
 * &lt;player&gt;" valued "round=percent,..." (e.g. "3=50,4=60,...,8=100") collects only that share
 * of the PUs it would otherwise collect, rounded down, while it is not at peace (at peace the
 * {@link Peacetime} rules apply). A round between listed ones uses the last listed round before it;
 * a round before the first listed one uses the first; after the last listed round it is 100%.
 * Manpower is never scaled.
 */
@UtilityClass
public class Mobilization {
  public static final String PROPERTY = "FS Mobilization ";

  /** The nation's schedule, round to percent, if it has one that parses. */
  public static Optional<NavigableMap<Integer, Integer>> schedule(final GamePlayer player) {
    if (player == null || player.isNull()) {
      return Optional.empty();
    }
    final String value = player.getData().getProperties().get(PROPERTY + player.getName(), "");
    if (value.isBlank()) {
      return Optional.empty();
    }
    final NavigableMap<Integer, Integer> result = new TreeMap<>();
    try {
      for (final String pair : value.split(",")) {
        final String[] parts = pair.trim().split("=");
        result.put(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
      }
    } catch (final RuntimeException e) {
      return Optional.empty();
    }
    return result.isEmpty() ? Optional.empty() : Optional.of(result);
  }

  /** The share of its PU income the nation collects in this round: 100 unless mobilizing. */
  public static int percent(final GamePlayer player, final int round) {
    if (Peacetime.isAtPeace(player)) {
      return 100;
    }
    return schedule(player).map(s -> percent(s, round)).orElse(100);
  }

  static int percent(final NavigableMap<Integer, Integer> schedule, final int round) {
    if (round > schedule.lastKey()) {
      return 100;
    }
    final Map.Entry<Integer, Integer> entry = schedule.floorEntry(round);
    return Math.max(0, Math.min(100, (entry == null ? schedule.firstEntry() : entry).getValue()));
  }

  /** The PUs collected out of a full income at this share, rounded down. */
  public static int scale(final int fullIncome, final int percent) {
    return fullIncome <= 0 || percent >= 100 ? fullIncome : fullIncome * percent / 100;
  }

  /** The first round from which the nation collects its full income. */
  public static int fullIncomeRound(final NavigableMap<Integer, Integer> schedule) {
    int round = schedule.lastKey() + 1;
    for (final Map.Entry<Integer, Integer> e : schedule.descendingMap().entrySet()) {
      if (e.getValue() < 100) {
        break;
      }
      round = e.getKey();
    }
    return round;
  }

  /** "Americans: mobilization 60% (full income from round 8)", or empty when not mobilizing. */
  public static Optional<String> statusText(final GamePlayer player, final int round) {
    final int percent = percent(player, round);
    if (percent >= 100) {
      return Optional.empty();
    }
    final int full = schedule(player).map(Mobilization::fullIncomeRound).orElse(round + 1);
    return Optional.of(
        player.getName() + ": mobilization " + percent + "% (full income from round " + full + ")");
  }

  /** "Round 3: 50%, round 4: 60%, ..., round 8 onward: 100%". */
  public static String scheduleText(final GamePlayer player) {
    return schedule(player)
        .map(
            s -> {
              final int full = fullIncomeRound(s);
              final String rounds =
                  s.headMap(full, false).entrySet().stream()
                      .map(e -> "round " + e.getKey() + ": " + e.getValue() + "%")
                      .collect(Collectors.joining(", "));
              final String text =
                  (rounds.isEmpty() ? "" : rounds + ", ") + "round " + full + " onward: 100%";
              return Character.toUpperCase(text.charAt(0)) + text.substring(1);
            })
        .orElse("");
  }
}
