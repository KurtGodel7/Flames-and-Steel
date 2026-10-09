package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment.ZoneType;
import games.strategy.triplea.attachments.ApcAttachment;
import games.strategy.triplea.delegate.Matches;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
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

  /**
   * How many steps (borders, land or sea) the target is from the nearest place this nation's
   * aircraft of this type, striking from this zone, could land, looking up to {@code maxSteps}
   * away; empty if there is none that close. Uses the same landing spots as {@link #canReach}.
   */
  public static OptionalInt landingDistance(
      final GamePlayer player,
      final Territory zone,
      final UnitType type,
      final Territory target,
      final int maxSteps) {
    final GameMap map = target.getData().getMap();
    final ZoneType zoneType = AirZoneAttachment.get(zone).orElseThrow().getZoneType();
    final boolean jet =
        AirUnitAttachment.get(type).map(AirUnitAttachment::isFleetCarrierOnly).orElse(false);
    for (int steps = 0; steps <= maxSteps; steps++) {
      final Set<Territory> around = new HashSet<>(map.getNeighbors(target, steps));
      around.add(target);
      for (final Territory t : around) {
        if (zoneType != ZoneType.OCEAN && isLandLandingSpot(player, t)) {
          return OptionalInt.of(steps);
        }
        if ((zoneType == ZoneType.OCEAN || zoneType == ZoneType.COASTAL)
            && type.getUnitAttachment().getCarrierCost() > 0
            && hasOwnCarrier(player, t, jet)) {
          return OptionalInt.of(steps);
        }
      }
    }
    return OptionalInt.empty();
  }

  /**
   * Whether an aircraft of this type could fly to the zone and back (fork request, 2026-10-08).
   * Land fronts: a covered territory within half its range of a friendly land landing spot. Bomb
   * zones: always for the bombed nation and its allies (they defend); for attackers, a factory or
   * APC territory of the bombed nation within half the range of a landing spot. Coastal and
   * open-ocean zones are checked with carrier capacity elsewhere; the reserve needs no check.
   */
  public static boolean canOperateIn(
      final GamePlayer player, final Territory zone, final UnitType type) {
    final AirZoneAttachment attachment = AirZoneAttachment.get(zone).orElseThrow();
    final int range = range(type, player);
    switch (attachment.getZoneType()) {
      case LAND:
        return landWithinReach(player, attachment.getCoveredTerritories(), range);
      case BOMB:
        final GamePlayer bombed = attachment.getBombedPlayer();
        if (bombed == null
            || bombed.equals(player)
            || player.getData().getRelationshipTracker().isAllied(player, bombed)) {
          return true;
        }
        return landWithinReach(player, bombingTargets(bombed), range);
      default:
        return true;
    }
  }

  /** The bombed nation's factory and APC territories. */
  private static List<Territory> bombingTargets(final GamePlayer bombed) {
    return bombed.getData().getMap().getTerritoriesOwnedBy(bombed).stream()
        .filter(
            t ->
                t.anyUnitsMatch(
                    Matches.unitCanProduceUnits().or(u -> ApcAttachment.isApc(u.getType()))))
        .toList();
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
