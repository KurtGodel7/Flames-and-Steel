package games.strategy.triplea.delegate;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import java.util.Collection;

/**
 * Flames and Steel (request 10): a player with the game property "FS Free Conscript Placement
 * &lt;player&gt;" = N may place up to N conscripts in each land territory it held at the start of
 * its turn, with no factory needed. In a territory with a factory those first N conscripts don't
 * use the factory's capacity. Every other unit needs factory capacity as usual, and territories
 * conquered this turn don't qualify.
 */
public final class FreeConscripts {
  public static final String PROPERTY_PREFIX = "FS Free Conscript Placement ";
  public static final String UNIT_TYPE = "conscript";

  private FreeConscripts() {}

  /** Free conscripts per territory for this player (0: none). */
  public static int allowance(final GamePlayer player) {
    final GameData data = player.getData();
    return data == null ? 0 : data.getProperties().get(PROPERTY_PREFIX + player.getName(), 0);
  }

  public static boolean isConscript(final Unit unit) {
    return unit.getType().getName().equals(UNIT_TYPE);
  }

  public static int count(final Collection<Unit> units) {
    return (int) units.stream().filter(FreeConscripts::isConscript).count();
  }

  /** Land the player owns and didn't conquer this turn (so held at the start of its turn). */
  public static boolean qualifies(final Territory t, final GamePlayer player) {
    return !t.isWater()
        && t.isOwnedBy(player)
        && !t.getData().getBattleDelegate().getBattleTracker().wasConquered(t);
  }

  /**
   * Free conscripts the player may still place in the territory.
   *
   * @param conscriptsPlacedThisTurn conscripts already placed there this turn (the first N of them
   *     were the free ones)
   */
  public static int left(
      final Territory t, final GamePlayer player, final int conscriptsPlacedThisTurn) {
    final int allowance = allowance(player);
    if (allowance <= 0 || !qualifies(t, player)) {
      return 0;
    }
    return Math.max(0, allowance - conscriptsPlacedThisTurn);
  }
}
