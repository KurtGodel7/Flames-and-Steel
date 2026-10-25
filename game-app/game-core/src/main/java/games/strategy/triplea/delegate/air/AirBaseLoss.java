package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.player.Player;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.formatter.MyFormatter;
import games.strategy.triplea.formatter.PlayerNames;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * Flames and Steel (request 38 section 5): aircraft that lose their base mid-round. When a carrier
 * is sunk with aircraft on it (by an air strike or in a sea battle), or land with aircraft on it is
 * captured, those aircraft get a free move of one space to a legal landing spot: land their nation
 * may use, or one of its own carriers with room. The owner is asked where; any that cannot land
 * are lost. Island-based aircraft may sometimes be lost this way: intended.
 */
@Slf4j
@UtilityClass
public class AirBaseLoss {

  /**
   * The aircraft on the map that have no base under them: on land their nation may no longer use,
   * or in a sea zone beyond its own carriers' room there (the ones that don't fit), by owner and
   * place.
   *
   * @param seaOnly only sea zones (after a sea battle or an air strike)
   */
  public static Map<GamePlayer, Map<Territory, List<Unit>>> stranded(
      final GameState data, final boolean seaOnly) {
    final Map<GamePlayer, Map<Territory, List<Unit>>> result = new LinkedHashMap<>();
    for (final Territory t : data.getMap().getTerritories()) {
      if (AirBases.isAirborneTerritory(t) || (seaOnly && !t.isWater())) {
        continue;
      }
      final Map<GamePlayer, List<Unit>> byOwner = new LinkedHashMap<>();
      t.getUnitCollection().getMatches(AirBases::isAircraft)
          .forEach(u -> byOwner.computeIfAbsent(u.getOwner(), k -> new ArrayList<>()).add(u));
      for (final Map.Entry<GamePlayer, List<Unit>> e : byOwner.entrySet()) {
        final List<Unit> without =
            t.isWater()
                ? overflow(e.getKey(), t, e.getValue())
                : AirBases.isLandBase(e.getKey(), t) ? List.of() : e.getValue();
        if (!without.isEmpty()) {
          result.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>()).put(t, without);
        }
      }
    }
    return result;
  }

  /** The aircraft in the sea zone that its owner's carriers there cannot hold. */
  static List<Unit> overflow(final GamePlayer player, final Territory zone, final List<Unit> planes) {
    final List<Unit> kept = new ArrayList<>();
    final List<Unit> out = new ArrayList<>();
    // jets first, so they take the fleet carriers' room
    final List<Unit> order = new ArrayList<>(planes);
    order.sort(
        Comparator.comparing((Unit u) -> !AirUnitAttachment.isFleetCarrierOnly(u))
            .thenComparing(Unit::getHits));
    for (final Unit u : order) {
      final List<UnitType> types = new ArrayList<>(kept.stream().map(Unit::getType).toList());
      types.add(u.getType());
      if (AirHome.fit(player, zone, types)) {
        kept.add(u);
      } else {
        out.add(u);
      }
    }
    return out;
  }

  /**
   * Gives every stranded aircraft its free move: each owner is asked where they land, one space
   * away at most; what is not answered lands at its first spot; what cannot land is lost.
   *
   * @param why for the question and the history, e.g. "Carrier sunk in Sea Zone 12"
   */
  public static void freeMoves(final IDelegateBridge bridge, final boolean seaOnly, final String why) {
    final GameState data = bridge.getData();
    if (!AirBases.usesBases(data)) {
      return;
    }
    final Map<GamePlayer, Map<Territory, List<Unit>>> stranded = stranded(data, seaOnly);
    if (stranded.isEmpty()) {
      return;
    }
    bridge.getHistoryWriter().startEvent(why + ": aircraft without a base move one space");
    for (final Map.Entry<GamePlayer, Map<Territory, List<Unit>>> e : stranded.entrySet()) {
      final GamePlayer player = e.getKey();
      final Map<Unit, Territory> from = new LinkedHashMap<>();
      final Map<Unit, List<Territory>> options = new LinkedHashMap<>();
      e.getValue()
          .forEach(
              (t, units) ->
                  units.forEach(
                      u -> {
                        from.put(u, t);
                        options.put(u, spots(player, u, t));
                      }));
      Map<Unit, Territory> chosen = Map.of();
      if (options.values().stream().anyMatch(o -> !o.isEmpty())) {
        try {
          final Player remote = bridge.getRemotePlayer(player);
          if (remote != null) {
            final Map<Unit, Territory> answer = remote.selectFreeLandings(options, why);
            chosen = answer == null ? Map.of() : answer;
          }
        } catch (final RuntimeException ex) {
          log.warn("Free landings: " + player.getName() + " could not be asked", ex);
        }
      }
      land(bridge, player, from, options, chosen);
    }
  }

  /** Where the aircraft could land with its free move: one space at most, nearest first. */
  public static List<Territory> spots(final GamePlayer player, final Unit plane, final Territory at) {
    final List<Territory> result = new ArrayList<>();
    AirBases.distances(player, at, 1).entrySet().stream()
        .filter(x -> !x.getKey().equals(at) || !at.isWater() && AirBases.isLandBase(player, at))
        .sorted(
            Comparator.comparing((Map.Entry<Territory, Integer> x) -> x.getValue())
                .thenComparing(x -> x.getKey().isWater())
                .thenComparing(x -> x.getKey().getName()))
        .map(Map.Entry::getKey)
        .filter(t -> t.isWater() ? AirBases.canUseCarriers(plane.getType()) && hasCarrier(player, t, plane) : AirBases.isLandBase(player, t))
        .forEach(result::add);
    return result;
  }

  private static boolean hasCarrier(final GamePlayer player, final Territory zone, final Unit plane) {
    return zone.anyUnitsMatch(u -> u.isOwnedBy(player) && AirBases.fits(plane.getType(), u));
  }

  /** Moves each aircraft to its chosen spot if it still has room, else its first with room. */
  private static void land(
      final IDelegateBridge bridge,
      final GamePlayer player,
      final Map<Unit, Territory> from,
      final Map<Unit, List<Territory>> options,
      final Map<Unit, Territory> chosen) {
    final Map<Territory, List<UnitType>> arriving = new LinkedHashMap<>();
    final List<Unit> lost = new ArrayList<>();
    final Map<String, Hop> moved = new LinkedHashMap<>();
    final CompositeChange change = new CompositeChange();
    for (final Map.Entry<Unit, List<Territory>> e : options.entrySet()) {
      final Unit u = e.getKey();
      final List<Territory> order = new ArrayList<>();
      final Territory wanted = chosen.get(u);
      if (wanted != null && e.getValue().contains(wanted)) {
        order.add(wanted);
      }
      e.getValue().stream().filter(t -> !order.contains(t)).forEach(order::add);
      Territory to = null;
      for (final Territory t : order) {
        if (t.isWater()) {
          final List<UnitType> planes = new ArrayList<>(arriving.getOrDefault(t, List.of()));
          t.getUnitCollection()
              .getMatches(x -> x.isOwnedBy(player) && AirBases.isAircraft(x))
              .forEach(x -> planes.add(x.getType()));
          planes.add(u.getType());
          if (!AirHome.fit(player, t, planes)) {
            continue;
          }
        }
        to = t;
        break;
      }
      if (to == null) {
        lost.add(u);
        continue;
      }
      if (to.isWater()) {
        arriving.computeIfAbsent(to, k -> new ArrayList<>()).add(u.getType());
      }
      final Territory dest = to;
      moved
          .computeIfAbsent(
              from.get(u).getName() + "|" + to.getName(),
              k -> new Hop(from.get(u), dest, new ArrayList<>()))
          .units()
          .add(u);
      change.add(ChangeFactory.moveUnits(from.get(u), to, List.of(u)));
    }
    for (final Hop hop : moved.values()) {
      bridge
          .getHistoryWriter()
          .addChildToEvent(
              PlayerNames.display(player)
                  + ": "
                  + MyFormatter.unitsToTextNoOwner(hop.units())
                  + " move from "
                  + hop.from().getName()
                  + " to "
                  + hop.to().getName(),
              hop.units());
    }
    if (!change.isEmpty()) {
      bridge.addChange(change);
    }
    if (!lost.isEmpty()) {
      final Map<Territory, List<Unit>> where = new LinkedHashMap<>();
      lost.forEach(u -> where.computeIfAbsent(from.get(u), k -> new ArrayList<>()).add(u));
      where.forEach(
          (t, units) -> {
            bridge
                .getHistoryWriter()
                .addChildToEvent(
                    PlayerNames.display(player)
                        + ": "
                        + MyFormatter.unitsToTextNoOwner(units)
                        + " at "
                        + t.getName()
                        + " have nowhere to land and are lost",
                    units);
            bridge.addChange(ChangeFactory.removeUnits(t, units));
          });
    }
  }

  /** Aircraft moving from one place to another with their free move. */
  private record Hop(Territory from, Territory to, List<Unit> units) {}
}
