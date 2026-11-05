package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import lombok.experimental.UtilityClass;

/**
 * Flames and Steel air range: an aircraft's range is its movement (with tech bonuses), counted in
 * steps, every border crossed, land or sea. Where aircraft may land is in {@link AirBases}: the
 * nation's own land and allied land where the relationship lets aircraft land (so not Soviet land
 * for the Western Allies, nor Chinese land for non-Chinese aircraft), and its own carriers.
 */
@UtilityClass
public class AirRange {

  /** An aircraft type's range for this nation (movement, with tech bonuses). */
  public static int range(final UnitType type, final GamePlayer owner) {
    return type.getUnitAttachment().getMovement(owner);
  }

  /** "Torpedo bombers", "Superfortresses". */
  static String pluralName(final UnitType type) {
    final String name = AirStage.typeName(type);
    return name + (name.endsWith("s") ? "es" : "s");
  }

  /** The nation's own land, or allied land where the relationship lets its aircraft land. */
  public static boolean isLandLandingSpot(final GamePlayer player, final Territory t) {
    // request 56: never land on impassable land, even where aircraft may fly over it
    if (t.isWater()
        || AirBases.isAirborneTerritory(t)
        || t.getOwner().isNull()
        || AirBases.isImpassableLand(t)) {
      return false;
    }
    if (t.getOwner().equals(player)) {
      return true;
    }
    return player
        .getData()
        .getRelationshipTracker()
        .getRelationshipType(player, t.getOwner())
        .getRelationshipTypeAttachment()
        .canLandAirUnitsOnOwnedLand();
  }
}
