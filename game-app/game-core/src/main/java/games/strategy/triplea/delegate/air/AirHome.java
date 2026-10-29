package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.delegate.air.AirStage.Landing;
import games.strategy.triplea.formatter.MyFormatter;
import games.strategy.triplea.formatter.PlayerNames;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.experimental.UtilityClass;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel (request 38 section 4): planes go home at the end of every round, every nation
 * at once. Each airborne aircraft lands within the range it has left, on land its nation may use
 * now or on one of its own carriers with room; aircraft that stayed home may rebase within their
 * full range. Damage, aborts and AA never reduce range. Airborne aircraft left with nowhere to land
 * are lost.
 *
 * <p>Default for an aircraft the player did not land: the nearest legal spot (fewest steps; land
 * before a carrier at the same distance; then by name), filling carriers in that order.
 */
@UtilityClass
public class AirHome {

  /** One kind of aircraft waiting to land or rebase: where it is, and how far it may fly. */
  public record Group(Territory from, boolean airborne, UnitType type, int range, List<Unit> units) {}

  /** The nation's airborne aircraft, by territory, type and range left. */
  public static List<Group> airborneGroups(final GamePlayer player) {
    final Map<String, Group> result = new LinkedHashMap<>();
    for (final AirBases.Flight f : AirStage.airborneFlights(player)) {
      final String key =
          f.over().getName() + "|" + f.plane().getType().getName() + "|" + f.rangeLeft();
      result
          .computeIfAbsent(
              key,
              k -> new Group(f.over(), true, f.plane().getType(), f.rangeLeft(), new ArrayList<>()))
          .units()
          .add(f.plane());
    }
    return new ArrayList<>(result.values());
  }

  /** The nation's aircraft on bases, by base and type: they may rebase with full range. */
  public static List<Group> basedGroups(final GamePlayer player) {
    final List<Group> result = new ArrayList<>();
    AirBases.basedAircraft(player)
        .forEach(
            (base, units) -> {
              final Map<UnitType, List<Unit>> byType = new LinkedHashMap<>();
              units.forEach(u -> byType.computeIfAbsent(u.getType(), k -> new ArrayList<>()).add(u));
              byType.forEach(
                  (type, list) ->
                      result.add(
                          new Group(base, false, type, AirRange.range(type, player), list)));
            });
    return result;
  }

  /** Where aircraft of the group could land now, nearest first, with the steps to each. */
  public static Map<Territory, Integer> spots(final GamePlayer player, final Group group) {
    final Map<Territory, Integer> result = new LinkedHashMap<>();
    AirBases.distances(player, group.from(), group.range()).entrySet().stream()
        .filter(e -> isSpot(player, e.getKey(), group.type()))
        .sorted(
            Comparator.comparing((Map.Entry<Territory, Integer> e) -> e.getValue())
                .thenComparing(e -> e.getKey().isWater())
                .thenComparing(e -> e.getKey().getName()))
        .forEach(e -> result.put(e.getKey(), e.getValue()));
    return result;
  }

  private static boolean isSpot(final GamePlayer player, final Territory t, final UnitType type) {
    if (t.isWater()) {
      return AirBases.canUseCarriers(type)
          && t.anyUnitsMatch(u -> u.isOwnedBy(player) && AirBases.fits(type, u));
    }
    return AirBases.isLandBase(player, t);
  }

  /**
   * The aircraft that land or rebase in these landings, by landing: from each group, that many of
   * the type (damaged first is not needed: all are healed).
   */
  public static Map<Landing, List<Unit>> unitsFor(
      final GamePlayer player, final Collection<Landing> landings) {
    final List<Group> groups = new ArrayList<>(airborneGroups(player));
    groups.addAll(basedGroups(player));
    final Set<Unit> used = new HashSet<>();
    final Map<Landing, List<Unit>> result = new LinkedHashMap<>();
    for (final Landing l : landings) {
      final List<Unit> units = new ArrayList<>();
      for (final Group g : groups) {
        if (g.from().equals(l.from()) && g.airborne() == l.airborne() && g.type().equals(l.unitType())) {
          for (final Unit u : g.units()) {
            if (units.size() < l.count() && used.add(u)) {
              units.add(u);
            }
          }
        }
      }
      result.put(l, units);
    }
    return result;
  }

  /** Why these landings are not allowed, or empty. */
  public static Optional<String> problem(final GamePlayer player, final Collection<Landing> landings) {
    final Map<Landing, List<Unit>> units = unitsFor(player, landings);
    for (final Landing l : landings) {
      if (l.count() <= 0) {
        return Optional.of("Counts must be positive.");
      }
      final List<Unit> these = units.get(l);
      if (these.size() < l.count()) {
        return Optional.of(
            "Only "
                + these.size()
                + " "
                + AirStage.typeName(l.unitType())
                + (l.airborne() ? " over " : " at ")
                + l.from().getName()
                + ".");
      }
      for (final Unit u : these) {
        final int range = l.airborne() ? u.getFlightRangeLeft() : AirRange.range(u.getType(), player);
        final Group g = new Group(l.from(), l.airborne(), u.getType(), range, List.of(u));
        if (!spots(player, g).containsKey(l.to())) {
          return Optional.of(
              AirStage.typeName(u.getType())
                  + (l.airborne() ? " over " : " at ")
                  + l.from().getName()
                  + " can't land at "
                  + l.to().getName()
                  + " (out of range, or not a spot it may use).");
        }
      }
    }
    return roomProblem(player, units);
  }

  /** Whether the carriers in every sea zone hold the aircraft there once all have landed. */
  private static Optional<String> roomProblem(
      final GamePlayer player, final Map<Landing, List<Unit>> units) {
    final Set<Unit> moving = new HashSet<>();
    units.values().forEach(moving::addAll);
    final Map<Territory, List<UnitType>> arriving = new LinkedHashMap<>();
    units.forEach(
        (l, list) -> {
          if (l.to().isWater()) {
            list.forEach(u -> arriving.computeIfAbsent(l.to(), k -> new ArrayList<>()).add(u.getType()));
          }
        });
    for (final Map.Entry<Territory, List<UnitType>> e : arriving.entrySet()) {
      final Territory zone = e.getKey();
      final List<UnitType> planes = new ArrayList<>(e.getValue());
      zone.getUnitCollection()
          .getMatches(u -> u.isOwnedBy(player) && AirBases.isAircraft(u) && !moving.contains(u))
          .forEach(u -> planes.add(u.getType()));
      if (!fit(player, zone, planes)) {
        return Optional.of("Not enough carrier room in " + zone.getName() + ".");
      }
    }
    return Optional.empty();
  }

  /** Whether the nation's carriers in the zone hold these aircraft (jets on fleet carriers). */
  static boolean fit(final GamePlayer player, final Territory zone, final List<UnitType> planes) {
    int fleet = 0;
    int other = 0;
    for (final Unit c :
        zone.getUnitCollection().getMatches(u -> u.isOwnedBy(player) && AirBases.isCarrier(u))) {
      if (AirUnitAttachment.isFleetCarrier(c)) {
        fleet += c.getUnitAttachment().getCarrierCapacity();
      } else {
        other += c.getUnitAttachment().getCarrierCapacity();
      }
    }
    int jets = 0;
    int rest = 0;
    for (final UnitType type : planes) {
      if (!AirBases.canUseCarriers(type)) {
        return false;
      }
      final int cost = type.getUnitAttachment().getCarrierCost();
      if (AirUnitAttachment.get(type).map(AirUnitAttachment::isFleetCarrierOnly).orElse(false)) {
        jets += cost;
      } else {
        rest += cost;
      }
    }
    return jets <= fleet && jets + rest <= fleet + other;
  }

  /**
   * Request 45: the player's landings plus, for every aircraft sent to rebase and left out, a
   * landing at its destination: the land it flew to if the nation may still land there, or the
   * carrier it flew to (wherever that carrier is now, within the range left), else an own carrier
   * in the sea zone it flew to. A landing that no longer fits (room) is left out; those aircraft
   * then land like any other airborne aircraft, or are lost.
   */
  public static List<Landing> withRebases(final GamePlayer player, final List<Landing> chosen) {
    List<Landing> result = new ArrayList<>(chosen);
    final Set<Unit> landed = new HashSet<>();
    unitsFor(player, chosen).values().forEach(landed::addAll);
    for (final AirBases.Flight f : AirStage.airborneFlights(player)) {
      final Unit u = f.plane();
      if (!u.getFlightRebase() || landed.contains(u)) {
        continue;
      }
      final Optional<Territory> spot = rebaseSpot(player, u);
      if (spot.isEmpty()) {
        continue;
      }
      final List<Landing> more = new ArrayList<>(result);
      more.add(new Landing(f.over(), true, u.getType(), 1, spot.get()));
      final List<Landing> merged = merge(more);
      if (problem(player, merged).isEmpty()) {
        result = merged;
      }
    }
    return result;
  }

  /** Request 45: where a rebasing aircraft lands, if its destination is still legal. */
  private static Optional<Territory> rebaseSpot(final GamePlayer player, final Unit u) {
    final Territory over = u.getFlightOver();
    if (over == null) {
      return Optional.empty();
    }
    final Map<Territory, Integer> reach =
        spots(player, new Group(over, true, u.getType(), u.getFlightRangeLeft(), List.of(u)));
    if (!over.isWater()) {
      return reach.containsKey(over) ? Optional.of(over) : Optional.empty();
    }
    final Unit carrier = u.getFlightCarrier();
    if (carrier != null && carrier.isOwnedBy(player) && AirBases.fits(u.getType(), carrier)) {
      for (final Territory zone : reach.keySet()) {
        if (zone.isWater() && zone.getUnitCollection().contains(carrier)) {
          return Optional.of(zone);
        }
      }
    }
    return reach.containsKey(over) ? Optional.of(over) : Optional.empty();
  }

  /** Request 45: whether the landing lands aircraft that were sent to rebase. */
  public static boolean isRebaseLanding(final GamePlayer player, final Landing landing) {
    return landing.airborne()
        && AirBases.airborneOver(player, landing.from()).stream()
            .anyMatch(u -> u.getType().equals(landing.unitType()) && u.getFlightRebase());
  }

  /**
   * The player's landings plus the default for every airborne aircraft left out: the nearest spot
   * with room, in the order of {@link #spots}. Aircraft that stayed home and are left out stay.
   */
  public static List<Landing> withDefaults(final GamePlayer player, final List<Landing> landings) {
    // request 45: aircraft sent to rebase land at their destination first, if it is still legal
    final List<Landing> chosen = withRebases(player, landings);
    final List<Landing> result = new ArrayList<>(chosen);
    final Map<Landing, List<Unit>> units = unitsFor(player, chosen);
    final Set<Unit> landed = new HashSet<>();
    units.values().forEach(landed::addAll);
    // the room the chosen landings already take
    final Map<Territory, List<UnitType>> load = new LinkedHashMap<>();
    final Set<Unit> leaving = new HashSet<>(landed);
    units.forEach(
        (l, list) -> {
          if (l.to().isWater()) {
            list.forEach(u -> load.computeIfAbsent(l.to(), k -> new ArrayList<>()).add(u.getType()));
          }
        });
    for (final Group g : airborneGroups(player)) {
      for (final Unit u : g.units()) {
        if (landed.contains(u)) {
          continue;
        }
        final Group one = new Group(g.from(), true, u.getType(), u.getFlightRangeLeft(), List.of(u));
        for (final Territory spot : spots(player, one).keySet()) {
          if (spot.isWater()) {
            final List<UnitType> planes = new ArrayList<>(load.getOrDefault(spot, List.of()));
            spot.getUnitCollection()
                .getMatches(x -> x.isOwnedBy(player) && AirBases.isAircraft(x) && !leaving.contains(x))
                .forEach(x -> planes.add(x.getType()));
            planes.add(u.getType());
            if (!fit(player, spot, planes)) {
              continue;
            }
            load.computeIfAbsent(spot, k -> new ArrayList<>()).add(u.getType());
          }
          result.add(new Landing(g.from(), true, u.getType(), 1, spot));
          break;
        }
      }
    }
    return merge(result);
  }

  /** Landings merged by place, type and destination. */
  public static List<Landing> merge(final List<Landing> landings) {
    final Map<String, Landing> result = new LinkedHashMap<>();
    for (final Landing l : landings) {
      result.merge(
          l.from().getName() + "|" + l.airborne() + "|" + l.unitType().getName() + "|" + l.to().getName(),
          l,
          (a, b) -> new Landing(a.from(), a.airborne(), a.unitType(), a.count() + b.count(), a.to()));
    }
    return new ArrayList<>(result.values());
  }

  /** The nation's airborne aircraft these landings leave in the air (they will be lost). */
  public static List<Unit> leftInTheAir(final GamePlayer player, final List<Landing> landings) {
    final Set<Unit> landed = new HashSet<>();
    unitsFor(player, landings).values().forEach(landed::addAll);
    final List<Unit> result = new ArrayList<>();
    airborneGroups(player).forEach(g -> g.units().stream().filter(u -> !landed.contains(u)).forEach(result::add));
    return result;
  }

  /**
   * Lands and rebases the aircraft, then the nation's airborne aircraft left in the air are lost,
   * each with a history line.
   */
  public static void apply(
      final IDelegateBridge bridge, final GamePlayer player, final List<Landing> landings) {
    final Territory airborne = AirBases.airborne(player.getData()).orElseThrow();
    final Map<Landing, List<Unit>> units = unitsFor(player, landings);
    for (final Map.Entry<Landing, List<Unit>> e : units.entrySet()) {
      final Landing l = e.getKey();
      final List<Unit> list = e.getValue();
      if (list.isEmpty()) {
        continue;
      }
      final CompositeChange change = new CompositeChange();
      if (l.airborne()) {
        for (final Unit u : list) {
          change.add(ChangeFactory.unitPropertyChange(u, null, Unit.PropertyName.FLIGHT_OVER));
          change.add(ChangeFactory.unitPropertyChange(u, 0, Unit.PropertyName.FLIGHT_RANGE_LEFT));
          if (u.getFlightRebase()) {
            change.add(ChangeFactory.unitPropertyChange(u, false, Unit.PropertyName.FLIGHT_REBASE));
            change.add(ChangeFactory.unitPropertyChange(u, null, Unit.PropertyName.FLIGHT_CARRIER));
          }
        }
        change.add(ChangeFactory.moveUnits(airborne, l.to(), list));
      } else if (!l.from().equals(l.to())) {
        change.add(ChangeFactory.moveUnits(l.from(), l.to(), list));
      } else {
        continue;
      }
      bridge
          .getHistoryWriter()
          .addChildToEvent(
              PlayerNames.display(player)
                  + ": "
                  + MyFormatter.unitsToTextNoOwner(list)
                  + (l.airborne() ? " from over " : " rebase from ")
                  + l.from().getName()
                  + (l.airborne() ? " land at " : " to ")
                  + l.to().getName(),
              list);
      bridge.addChange(change);
    }
    final List<Unit> lost =
        airborne.getUnitCollection().getMatches(u -> u.isOwnedBy(player) && AirBases.isAirborne(u));
    if (!lost.isEmpty()) {
      final IntegerMap<Territory> where = new IntegerMap<>();
      lost.forEach(u -> where.add(u.getFlightOver(), 1));
      for (final Territory t : where.keySet()) {
        final List<Unit> here = lost.stream().filter(u -> t.equals(u.getFlightOver())).toList();
        bridge
            .getHistoryWriter()
            .addChildToEvent(
                PlayerNames.display(player)
                    + ": "
                    + MyFormatter.unitsToTextNoOwner(here)
                    + " over "
                    + t.getName()
                    + " have nowhere to land and are lost",
                here);
      }
      bridge.addChange(ChangeFactory.removeUnits(airborne, lost));
    }
  }
}
