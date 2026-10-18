package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.Change;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment.ZoneType;
import games.strategy.triplea.formatter.MyFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel (request 9 item 3): aircraft sail with their carriers. When a nation's carriers
 * move from a sea zone in one air front to a sea zone in another, the nation's aircraft in the old
 * front go with them into the new front, up to the moved carriers' room: fighters before bombers,
 * jets only on fleet carriers. The player may choose fewer.
 */
public final class FleetAircraft {
  private FleetAircraft() {}

  /**
   * What may sail with one carrier move.
   *
   * @param available the nation's aircraft in the old front that could go, by type
   * @param capacity carrier room of the moved carriers
   * @param fleetCapacity the part of that room on fleet carriers (jets need it)
   */
  public record Plan(
      Territory from,
      Territory to,
      IntegerMap<UnitType> available,
      IntegerMap<UnitType> suggested,
      int capacity,
      int fleetCapacity) {

    /** "Pacific to Hawaiian Waters: the moving carriers have room for 8 (4 on fleet carriers)." */
    public String describe() {
      return AirAllocation.zoneName(from)
          + " to "
          + AirAllocation.zoneName(to)
          + ": the moving carriers have room for "
          + capacity
          + (fleetCapacity > 0 ? " (" + fleetCapacity + " on fleet carriers)" : "")
          + ".";
    }
  }

  /** The air front (not a bombing zone) whose area holds this map territory. */
  public static Optional<Territory> frontOf(final Territory t, final GameMap map) {
    for (final Territory zone : AirZoneAttachment.getAirZones(map)) {
      final AirZoneAttachment a = AirZoneAttachment.get(zone).orElseThrow();
      if (a.getZoneType() != ZoneType.BOMB
          && a.getZoneType() != ZoneType.RESERVE
          && a.getCoveredTerritories().contains(t)) {
        return Optional.of(zone);
      }
    }
    return Optional.empty();
  }

  /** Whether the unit carries aircraft. */
  public static boolean isCarrier(final Unit unit) {
    return unit.getUnitAttachment().getCarrierCapacity() > 0;
  }

  /** The aircraft that may sail with this move, or empty if the move takes no carrier across. */
  public static Optional<Plan> plan(
      final GamePlayer player, final Collection<Unit> moved, final Route route, final GameMap map) {
    final List<Unit> carriers =
        moved.stream().filter(u -> u.isOwnedBy(player) && isCarrier(u)).toList();
    if (carriers.isEmpty()) {
      return Optional.empty();
    }
    final Optional<Territory> from = frontOf(route.getStart(), map);
    final Optional<Territory> to = frontOf(route.getEnd(), map);
    if (from.isEmpty() || to.isEmpty() || from.get().equals(to.get())) {
      return Optional.empty();
    }
    final IntegerMap<UnitType> available = new IntegerMap<>();
    if (AirAllocation.mayEnter(player, to.get())) {
      for (final Unit u : from.get().getUnitCollection()) {
        if (u.isOwnedBy(player) && u.getUnitAttachment().getCarrierCost() > 0) {
          available.add(u.getType(), 1);
        }
      }
    }
    if (available.isEmpty()) {
      return Optional.empty();
    }
    int capacity = 0;
    int fleetCapacity = 0;
    for (final Unit carrier : carriers) {
      capacity += carrier.getUnitAttachment().getCarrierCapacity();
      if (AirUnitAttachment.isFleetCarrier(carrier)) {
        fleetCapacity += carrier.getUnitAttachment().getCarrierCapacity();
      }
    }
    return Optional.of(
        new Plan(
            from.get(),
            to.get(),
            available,
            suggest(available, capacity, fleetCapacity),
            capacity,
            fleetCapacity));
  }

  private static boolean jetOnly(final UnitType type) {
    return AirUnitAttachment.get(type).map(AirUnitAttachment::isFleetCarrierOnly).orElse(false);
  }

  private static boolean isFighter(final UnitType type) {
    return AirUnitAttachment.get(type).map(AirUnitAttachment::isFighter).orElse(false);
  }

  /** As many as fit: jets first (fleet carriers only), then other fighters, then bombers. */
  static IntegerMap<UnitType> suggest(
      final IntegerMap<UnitType> available, final int capacity, final int fleetCapacity) {
    final List<UnitType> order = new ArrayList<>(available.keySet());
    order.sort(
        Comparator.comparing((UnitType t) -> !jetOnly(t))
            .thenComparing(t -> !isFighter(t))
            .thenComparing(UnitType::getName));
    int fleetLeft = fleetCapacity;
    int otherLeft = capacity - fleetCapacity;
    final IntegerMap<UnitType> result = new IntegerMap<>();
    for (final UnitType type : order) {
      final int cost = type.getUnitAttachment().getCarrierCost();
      for (int i = 0; i < available.getInt(type); i++) {
        if (jetOnly(type)) {
          if (fleetLeft < cost) {
            break;
          }
          fleetLeft -= cost;
        } else if (otherLeft >= cost) {
          otherLeft -= cost;
        } else if (fleetLeft >= cost) {
          fleetLeft -= cost;
        } else {
          break;
        }
        result.add(type, 1);
      }
    }
    return result;
  }

  /** Why the player's choice doesn't fit, if it doesn't. */
  public static Optional<String> validate(final Plan plan, final IntegerMap<UnitType> chosen) {
    int cost = 0;
    int jetCost = 0;
    for (final UnitType type : chosen.keySet()) {
      final int n = chosen.getInt(type);
      if (n < 0 || n > plan.available().getInt(type)) {
        return Optional.of(
            "Only "
                + plan.available().getInt(type)
                + " "
                + AirAllocation.typeName(type)
                + " in "
                + AirAllocation.zoneName(plan.from())
                + ".");
      }
      cost += n * type.getUnitAttachment().getCarrierCost();
      if (jetOnly(type)) {
        jetCost += n * type.getUnitAttachment().getCarrierCost();
      }
    }
    if (cost > plan.capacity()) {
      return Optional.of("The carriers have room for " + plan.capacity() + ", not " + cost + ".");
    }
    if (jetCost > plan.fleetCapacity()) {
      return Optional.of("Jets need fleet carriers: room for " + plan.fleetCapacity() + ".");
    }
    return Optional.empty();
  }

  /** The aircraft that go (undamaged first) and the change that moves them. */
  public static List<Unit> aircraftFor(
      final Plan plan, final IntegerMap<UnitType> chosen, final GamePlayer player) {
    final List<Unit> result = new ArrayList<>();
    for (final UnitType type : chosen.keySet()) {
      plan.from().getUnitCollection().stream()
          .filter(u -> u.isOwnedBy(player) && u.getType().equals(type))
          .sorted(Comparator.comparingInt(Unit::getHits))
          .limit(chosen.getInt(type))
          .forEach(result::add);
    }
    return result;
  }

  public static Change change(final Plan plan, final Collection<Unit> aircraft) {
    return ChangeFactory.moveUnits(plan.from(), plan.to(), aircraft);
  }

  /** "2 fighters sail with the fleet from Pacific to Hawaiian Waters". */
  public static String historyText(final Plan plan, final Collection<Unit> aircraft) {
    return MyFormatter.unitsToTextNoOwner(aircraft)
        + " sail with the fleet from "
        + AirAllocation.zoneName(plan.from())
        + " to "
        + AirAllocation.zoneName(plan.to());
  }
}
