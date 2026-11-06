package games.strategy.engine.stats;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Resource;
import games.strategy.triplea.delegate.AbstractEndTurnDelegate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Flames and Steel (request 60): how the statistics tabs total players and count manpower. Display
 * only: nothing here changes the TUV the AI or the air system use.
 *
 * <ul>
 *   <li>Game property {@value #SIDES_PROPERTY}: {@code Side:player:player;Side:player:...}. When
 *       set, the total rows are these sides instead of the alliances.
 *   <li>Game property {@value #MP_VALUE_PROPERTY}: PUs per manpower point. When above 0, the
 *       Players tab shows "Income+MP" and the Resources tab a "PU value" column.
 * </ul>
 */
public final class StatsSides {
  public static final String SIDES_PROPERTY = "FS Stats Sides";
  public static final String MP_VALUE_PROPERTY = "FS Stats MP Value";
  public static final String MANPOWER = "Manpower";

  private StatsSides() {}

  /**
   * The total rows, name to players: the sides of {@value #SIDES_PROPERTY} in its order, or else
   * the stock rows (alliances of more than one player, by name).
   */
  public static Map<String, List<GamePlayer>> totalRows(final GameData data) {
    final Map<String, List<GamePlayer>> sides = sides(data);
    if (!sides.isEmpty()) {
      return sides;
    }
    final Map<String, List<GamePlayer>> result = new LinkedHashMap<>();
    data.getAllianceTracker().getAlliances().stream()
        .filter(a -> data.getAllianceTracker().getPlayersInAlliance(a).size() > 1)
        .sorted()
        .forEach(
            a -> result.put(a, new ArrayList<>(data.getAllianceTracker().getPlayersInAlliance(a))));
    return result;
  }

  /** The sides of {@value #SIDES_PROPERTY}, or empty when it is not set. Unknown names skipped. */
  public static Map<String, List<GamePlayer>> sides(final GameData data) {
    final Map<String, List<GamePlayer>> result = new LinkedHashMap<>();
    final String text = data.getProperties().get(SIDES_PROPERTY, "").trim();
    for (final String side : text.split(";")) {
      final String[] parts = side.trim().split(":");
      if (parts.length < 2 || parts[0].isBlank()) {
        continue;
      }
      final List<GamePlayer> players = new ArrayList<>();
      for (int i = 1; i < parts.length; i++) {
        final GamePlayer p = data.getPlayerList().getPlayerId(parts[i].trim());
        if (p != null && !players.contains(p)) {
          players.add(p);
        }
      }
      result.put(parts[0].trim(), players);
    }
    return result;
  }

  /** PUs per manpower point, or 0 when not set (the manpower columns are then hidden). */
  public static int mpValue(final GameData data) {
    if (manpower(data).isEmpty()) {
      return 0;
    }
    final @Nullable Object value = data.getProperties().get(MP_VALUE_PROPERTY);
    if (value == null) {
      return 0;
    }
    try {
      return Math.max(0, Integer.parseInt(String.valueOf(value).trim()));
    } catch (final NumberFormatException e) {
      return 0;
    }
  }

  public static Optional<Resource> manpower(final GameData data) {
    return data.getResourceList().getResourceOptional(MANPOWER);
  }

  /** The manpower the player gets next turn, as the Resources tab shows it "(+N)". */
  public static int mpIncome(final GamePlayer player, final GameData data) {
    return manpower(data)
        .map(mp -> AbstractEndTurnDelegate.findEstimatedIncome(player, data).getInt(mp))
        .orElse(0);
  }

  /** Request 60: production plus the manpower income at the set PU value. */
  public static class IncomeMpStat implements IStat {
    private final ProductionStat production = new ProductionStat();

    @Override
    public String getName() {
      return "Income+MP";
    }

    @Override
    public double getValue(
        final GamePlayer player,
        final GameData data,
        final games.strategy.triplea.ui.mapdata.MapData mapData) {
      return production.getValue(player, data, mapData)
          + (double) mpIncome(player, data) * mpValue(data);
    }
  }
}
