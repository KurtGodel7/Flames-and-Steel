package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment.ZoneType;
import java.util.Collection;
import java.util.HashSet;
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

  /**
   * How far from a landing spot an aircraft of this range may strike: half its range, rounded down
   * (range 3 reaches 1, range 4 reaches 2, range 8 reaches 4). The same for land and carriers.
   */
  public static int reach(final int range) {
    return range / 2;
  }

  /**
   * How far this nation's aircraft of this type strike from the front, in plain words (request 17):
   * "Torpedo bombers: 1 zone from your carriers", "Dive bombers: 1 step from your land or
   * carriers".
   */
  public static String reachText(
      final GamePlayer player, final Territory zone, final UnitType type) {
    final String name = pluralName(type);
    final String from = landingSpots(zone, type, false);
    if (from.isEmpty()) {
      return name + ": cannot strike from an ocean front (only carrier aircraft can)";
    }
    final int reach = reach(range(type, player));
    return name + ": " + reach + unit(zone, reach) + " from your " + from;
  }

  /**
   * Why a target is out of range, in plain words (request 17): "Sea Zone 225: 2 zones from your
   * nearest carrier; torpedo bombers reach 1".
   */
  public static String outOfRangeText(
      final GamePlayer player, final Territory zone, final UnitType type, final Territory target) {
    final String name = pluralName(type).toLowerCase(java.util.Locale.ROOT);
    final String from = landingSpots(zone, type, true);
    if (from.isEmpty()) {
      return target.getName()
          + ": "
          + reachText(player, zone, type).toLowerCase(java.util.Locale.ROOT);
    }
    final int reach = reach(range(type, player));
    final int look = reach + 6;
    final OptionalInt distance = landingDistance(player, zone, type, target, look);
    return target.getName()
        + ": "
        + (distance.isPresent()
            ? distance.getAsInt()
                + distanceUnit(zone, target, distance.getAsInt())
                + " from your nearest "
                + from
            : "more than " + look + distanceUnit(zone, target, look) + " from any " + from)
        + "; "
        + name
        + " reach "
        + reach;
  }

  /**
   * Where aircraft striking from this front may land: carriers in ocean fronts, land in land
   * fronts, both in coastal fronts (carriers only for carrier aircraft); empty when none (a
   * non-carrier type in an ocean front).
   */
  private static String landingSpots(final Territory zone, final UnitType type, final boolean one) {
    final boolean carrierType = type.getUnitAttachment().getCarrierCost() > 0;
    final String carrier = one ? "carrier" : "carriers";
    return switch (AirZoneAttachment.get(zone).orElseThrow().getZoneType()) {
      case OCEAN -> carrierType ? carrier : "";
      case COASTAL -> carrierType ? "land or " + carrier : (one ? "landing territory" : "land");
      default -> one ? "landing territory" : "land";
    };
  }

  /**
   * " zone"/" zones" in ocean fronts (all sea), " step"/" steps" (borders, land or sea) elsewhere.
   */
  private static String unit(final Territory zone, final int n) {
    final boolean ocean = AirZoneAttachment.get(zone).orElseThrow().getZoneType() == ZoneType.OCEAN;
    return (ocean ? " zone" : " step") + (n == 1 ? "" : "s");
  }

  /**
   * As {@link #unit}, but "steps" whenever the target is land (the last step reaches the shore).
   */
  private static String distanceUnit(final Territory zone, final Territory target, final int n) {
    return target.isWater() ? unit(zone, n) : " step" + (n == 1 ? "" : "s");
  }

  /** "Torpedo bombers", "Superfortresses". */
  static String pluralName(final UnitType type) {
    final String name = AirAllocation.typeName(type);
    return name + (name.endsWith("s") ? "es" : "s");
  }

  /**
   * Whether the nation has a landing spot in the front: a land territory there it (or a nation
   * whose land it may use) owns, or one of its own carriers in one of the front's sea zones.
   */
  public static boolean hasLandingSpot(final GamePlayer player, final Territory zone) {
    for (final Territory t : AirZoneAttachment.get(zone).orElseThrow().getCoveredTerritories()) {
      if (t.isWater() ? hasOwnCarrier(player, t, false) : isLandLandingSpot(player, t)) {
        return true;
      }
    }
    return false;
  }

  /** Whether an aircraft of this type and range, in this zone, can reach the target territory. */
  public static boolean canReach(
      final GamePlayer player,
      final Territory zone,
      final UnitType type,
      final int range,
      final Territory target) {
    final GameMap map = target.getData().getMap();
    final int reach = reach(range);
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

  /** Whether some friendly land landing spot is within half the range of any of these places. */
  public static boolean landWithinReach(
      final GamePlayer player, final Collection<Territory> places, final int range) {
    if (places.isEmpty()) {
      return false;
    }
    final GameMap map = places.iterator().next().getData().getMap();
    for (final Territory place : places) {
      final Set<Territory> around = new HashSet<>(map.getNeighbors(place, reach(range)));
      around.add(place);
      if (around.stream().anyMatch(t -> isLandLandingSpot(player, t))) {
        return true;
      }
    }
    return false;
  }

  /** The nation's own land, or allied land where the relationship lets its aircraft land. */
  public static boolean isLandLandingSpot(final GamePlayer player, final Territory t) {
    if (t.isWater()
        || AirZoneAttachment.isAirZone(t)
        || AirBases.isAirborneTerritory(t)
        || t.getOwner().isNull()) {
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
