package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment.ZoneType;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Flames and Steel air range. A target must be within half an aircraft's range (its movement) of a
 * landing spot, counting every border crossed, land or sea. Landing spots: the nation's own land
 * and allied land where the relationship lets aircraft land (so not Soviet land for the Western
 * Allies, nor Chinese land for non-Chinese aircraft), and in open-ocean and coastal zones the
 * nation's own carriers (fleet carriers only for jets). Open-ocean strikes fly from carriers only.
 */
@UtilityClass
public class AirRange {

  /** An aircraft type's range for this nation (movement, with tech bonuses). */
  public static int range(final UnitType type, final GamePlayer owner) {
    return type.getUnitAttachment().getMovement(owner);
  }

  /** The shortest range among these aircraft (escorts limit strategic bombers). */
  public static int shortestRange(final Collection<Unit> aircraft) {
    return aircraft.stream().mapToInt(u -> range(u.getType(), u.getOwner())).min().orElse(0);
  }

  /** Whether an aircraft of this type and range, in this zone, can reach the target territory. */
  public static boolean canReach(
      final GamePlayer player,
      final Territory zone,
      final UnitType type,
      final int range,
      final Territory target) {
    final GameMap map = target.getData().getMap();
    final int reach = range / 2;
    final ZoneType zoneType = AirZoneAttachment.get(zone).orElseThrow().getZoneType();
    final boolean jet =
        AirUnitAttachment.get(type).map(AirUnitAttachment::isFleetCarrierOnly).orElse(false);
    final Set<Territory> around = new HashSet<>(map.getNeighbors(target, reach));
    around.add(target);
    for (final Territory t : around) {
      if (zoneType != ZoneType.OCEAN && isLandLandingSpot(player, t)) {
        return true;
      }
      if ((zoneType == ZoneType.OCEAN || zoneType == ZoneType.COASTAL)
          && type.getUnitAttachment().getCarrierCost() > 0
          && hasOwnCarrier(player, t, jet)) {
        return true;
      }
    }
    return false;
  }

  /** Whether some friendly land landing spot is within half the range of any of these places. */
  public static boolean landWithinReach(
      final GamePlayer player, final Collection<Territory> places, final int range) {
    if (places.isEmpty()) {
      return false;
    }
    final GameMap map = places.iterator().next().getData().getMap();
    for (final Territory place : places) {
      final Set<Territory> around = new HashSet<>(map.getNeighbors(place, range / 2));
      around.add(place);
      if (around.stream().anyMatch(t -> isLandLandingSpot(player, t))) {
        return true;
      }
    }
    return false;
  }

  /** The nation's own land, or allied land where the relationship lets its aircraft land. */
  public static boolean isLandLandingSpot(final GamePlayer player, final Territory t) {
    if (t.isWater() || AirZoneAttachment.isAirZone(t) || t.getOwner().isNull()) {
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

  private static boolean hasOwnCarrier(
      final GamePlayer player, final Territory seaZone, final boolean fleetOnly) {
    return seaZone.isWater()
        && seaZone.anyUnitsMatch(
            u ->
                u.isOwnedBy(player)
                    && u.getUnitAttachment().getCarrierCapacity() > 0
                    && (!fleetOnly || AirUnitAttachment.isFleetCarrier(u)));
  }
}
