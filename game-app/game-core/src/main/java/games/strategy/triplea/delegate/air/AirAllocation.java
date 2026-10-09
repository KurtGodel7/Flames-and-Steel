package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.Change;
import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment.ZoneType;
import games.strategy.triplea.delegate.Matches;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel air allocation rules. At the start of each round every nation moves its aircraft
 * between air zones, bombers first and then fighters, without seeing the other nations' choices
 * until all have chosen. An aircraft may stay, move to an adjacent zone, go to the reserve, or
 * leave the reserve for any zone; a zone may name the nations allowed in it; and an open-ocean zone
 * holds no more of a nation's aircraft than its own carriers there can carry (jets only on fleet
 * carriers).
 */
@UtilityClass
public class AirAllocation {

  /** The two allocation stages. */
  public enum Stage {
    BOMBERS,
    FIGHTERS
  }

  /** Move {@code count} aircraft of one type from one air zone to another. */
  public record AirMove(Territory from, Territory to, UnitType unitType, int count)
      implements Serializable {
    @Serial private static final long serialVersionUID = 3348520199613407552L;
  }

  /** Whether the aircraft is moved in this stage (fighter types in the fighter stage). */
  public static boolean isInStage(final Unit unit, final Stage stage) {
    return Matches.unitIsAir().test(unit)
        && AirUnitAttachment.isFighter(unit) == (stage == Stage.FIGHTERS);
  }

  /** The player's aircraft of this stage in the zone. */
  public static List<Unit> getAircraft(
      final GamePlayer player, final Territory zone, final Stage stage) {
    return zone.getUnitCollection().getMatches(u -> u.isOwnedBy(player) && isInStage(u, stage));
  }

  /** Air zones the player's aircraft may move to from this zone this round. */
  public static List<Territory> getDestinations(
      final GamePlayer player, final Territory from, final GameMap map) {
    final List<Territory> result = new ArrayList<>();
    for (final Territory to : AirZoneAttachment.getAirZones(map)) {
      if (!to.equals(from) && canMove(from, to) && mayEnter(player, to)) {
        result.add(to);
      }
    }
    return result;
  }

  /** Adjacent zones, or into or out of the reserve. */
  public static boolean canMove(final Territory from, final Territory to) {
    return isReserve(from)
        || isReserve(to)
        || AirZoneAttachment.get(from).orElseThrow().getAdjacentZones().contains(to);
  }

  public static boolean mayEnter(final GamePlayer player, final Territory zone) {
    final List<GamePlayer> players = AirZoneAttachment.get(zone).orElseThrow().getPlayers();
    return players.isEmpty() || players.contains(player);
  }

  private static boolean isReserve(final Territory zone) {
    return AirZoneAttachment.get(zone).orElseThrow().getZoneType() == ZoneType.RESERVE;
  }

  /**
   * Why {@code count} aircraft of a type may not move from one zone to another on top of the moves
   * already staged, or empty if they may. Quicker than {@link #validate} for checking every zone;
   * {@link #validate} still checks the whole allocation when it is submitted.
   */
  public static Optional<String> destinationProblem(
      final GamePlayer player,
      final Stage stage,
      final List<AirMove> staged,
      final Territory from,
      final UnitType unitType,
      final int count,
      final Territory to) {
    if (from.equals(to)) {
      return Optional.of("The aircraft are already in " + zoneName(to) + ".");
    }
    if (!canMove(from, to)) {
      return Optional.of(
          zoneName(to)
              + " is not next to "
              + zoneName(from)
              + ": go to the reserve first and move on next round.");
    }
    if (!mayEnter(player, to)) {
      return Optional.of(player.getName() + " aircraft may not enter " + zoneName(to) + ".");
    }
    final ZoneType type = AirZoneAttachment.get(to).orElseThrow().getZoneType();
    if ((type == ZoneType.LAND || type == ZoneType.BOMB)
        && !AirRange.canOperateIn(player, to, unitType)) {
      return Optional.of(
          "Out of range: no "
              + (type == ZoneType.BOMB ? "target" : "part")
              + " of "
              + zoneName(to)
              + " is within "
              + AirRange.range(unitType, player) / 2
              + " of a friendly landing spot.");
    }
    if (type == ZoneType.OCEAN || type == ZoneType.COASTAL) {
      final List<AirMove> moves = new ArrayList<>(staged);
      moves.add(new AirMove(from, to, unitType, count));
      final CarrierLoad load =
          carrierLoad(player, to, aircraftAfterMoves(player, stage, moves, to));
      if (load.unfit() != null) {
        return Optional.of(
            load.unfit().getName()
                + (type == ZoneType.COASTAL
                    ? " has no friendly land in range and cannot fly from carriers."
                    : " cannot fly from carriers."));
      }
      if (load.cost() > load.capacity()) {
        return Optional.of(
            "No carrier capacity: " + load.cost() + "/" + load.capacity() + " in " + zoneName(to));
      }
      if (load.jetCost() > load.fleetCapacity()) {
        return Optional.of(
            "Jets need fleet carriers: "
                + load.jetCost()
                + "/"
                + load.fleetCapacity()
                + " in "
                + zoneName(to));
      }
    }
    return Optional.empty();
  }

  /** "North Atlantic" for the zone "Air - North Atlantic". */
  public static String zoneName(final Territory zone) {
    return zone.getName().replaceFirst("^Air - ", "");
  }

  /** Why these moves are not allowed, if they are not. */
  public static Optional<String> validate(
      final GamePlayer player, final Stage stage, final List<AirMove> moves, final GameMap map) {
    final IntegerMap<String> requested = new IntegerMap<>();
    for (final AirMove move : moves) {
      if (move.count() <= 0) {
        return Optional.of("Move counts must be positive.");
      }
      if (!AirZoneAttachment.isAirZone(move.from()) || !AirZoneAttachment.isAirZone(move.to())) {
        return Optional.of("Aircraft may only move between air zones.");
      }
      if (move.from().equals(move.to())) {
        return Optional.of("An aircraft must move to a different zone.");
      }
      if (!canMove(move.from(), move.to())) {
        return Optional.of(
            move.from().getName()
                + " is not next to "
                + move.to().getName()
                + ": go to the reserve first and move on next round.");
      }
      if (!mayEnter(player, move.to())) {
        return Optional.of(player.getName() + " may not enter " + move.to().getName() + ".");
      }
      requested.add(key(move.from(), move.unitType()), move.count());
    }
    for (final AirMove move : moves) {
      final long available =
          getAircraft(player, move.from(), stage).stream()
              .filter(u -> u.getType().equals(move.unitType()))
              .count();
      if (requested.getInt(key(move.from(), move.unitType())) > available) {
        return Optional.of(
            "Only "
                + available
                + " "
                + move.unitType().getName()
                + " available in "
                + move.from().getName()
                + ".");
      }
    }
    final Optional<String> rangeError = checkRange(player, stage, moves, map);
    if (rangeError.isPresent()) {
      return rangeError;
    }
    return checkCarrierCapacity(player, stage, moves, map);
  }

  /**
   * Every aircraft of this stage that would be in a land front or Bomb zone after the moves (moving
   * in or staying) must be able to fly there and back.
   */
  private static Optional<String> checkRange(
      final GamePlayer player, final Stage stage, final List<AirMove> moves, final GameMap map) {
    for (final Territory zone : AirZoneAttachment.getAirZones(map)) {
      final ZoneType type = AirZoneAttachment.get(zone).orElseThrow().getZoneType();
      if (type != ZoneType.LAND && type != ZoneType.BOMB) {
        continue;
      }
      for (final UnitType unitType :
          new HashSet<>(stageAircraftAfterMoves(player, stage, moves, zone))) {
        if (!AirRange.canOperateIn(player, zone, unitType)) {
          return Optional.of(
              unitType.getName()
                  + " cannot operate in "
                  + zone.getName()
                  + ": no "
                  + (type == ZoneType.BOMB ? "target" : "part of it")
                  + " is within half its range ("
                  + AirRange.range(unitType, player) / 2
                  + ") of a friendly landing spot. Move it elsewhere or to the reserve.");
        }
      }
    }
    return Optional.empty();
  }

  /** Types of the player's aircraft of this stage in the zone once the moves are made. */
  private static List<UnitType> stageAircraftAfterMoves(
      final GamePlayer player, final Stage stage, final List<AirMove> moves, final Territory zone) {
    final List<UnitType> result = new ArrayList<>();
    for (final Unit unit : getAircraft(player, zone, stage)) {
      result.add(unit.getType());
    }
    for (final AirMove move : moves) {
      for (int i = 0; i < move.count(); i++) {
        if (move.from().equals(zone)) {
          result.remove(move.unitType());
        }
        if (move.to().equals(zone)) {
          result.add(move.unitType());
        }
      }
    }
    return result;
  }

  private static Optional<String> checkCarrierCapacity(
      final GamePlayer player, final Stage stage, final List<AirMove> moves, final GameMap map) {
    final Set<Territory> destinations = new HashSet<>();
    for (final AirMove move : moves) {
      final ZoneType type = AirZoneAttachment.get(move.to()).orElseThrow().getZoneType();
      if (type == ZoneType.OCEAN || type == ZoneType.COASTAL) {
        destinations.add(move.to());
      }
    }
    for (final Territory zone : destinations) {
      final ZoneType type = AirZoneAttachment.get(zone).orElseThrow().getZoneType();
      final CarrierLoad load =
          carrierLoad(player, zone, aircraftAfterMoves(player, stage, moves, zone));
      if (load.unfit() != null) {
        return Optional.of(
            load.unfit().getName()
                + (type == ZoneType.COASTAL
                    ? " has no friendly land in range and cannot fly from carriers, so cannot go to "
                    : " cannot fly from carriers, so cannot go to ")
                + zone.getName());
      }
      if (load.cost() > load.capacity()) {
        return Optional.of(
            zone.getName()
                + ": "
                + player.getName()
                + " carriers there can take "
                + load.capacity()
                + " aircraft, not "
                + load.cost()
                + ".");
      }
      if (load.jetCost() > load.fleetCapacity()) {
        return Optional.of(
            zone.getName()
                + ": jets need fleet carriers; "
                + player.getName()
                + " fleet carriers there can take "
                + load.fleetCapacity()
                + ".");
      }
    }
    return Optional.empty();
  }

  /**
   * How a nation's aircraft in an open-ocean or coastal zone sit on its carriers there.
   *
   * @param cost carrier spaces the aircraft that need carriers take
   * @param capacity carrier spaces of the nation's carriers in the zone's sea zones
   * @param jetCost spaces taken by aircraft that need fleet carriers (jets)
   * @param fleetCapacity spaces on fleet carriers
   * @param unfit an aircraft type that needs a carrier but cannot use one, if any
   */
  public record CarrierLoad(
      int cost, int capacity, int jetCost, int fleetCapacity, @Nullable UnitType unfit) {}

  /**
   * Carrier load of these aircraft types in the zone. In a coastal zone only aircraft with no
   * friendly land within reach need carriers.
   */
  public static CarrierLoad carrierLoad(
      final GamePlayer player, final Territory zone, final Collection<UnitType> aircraft) {
    final AirZoneAttachment zoneAttachment = AirZoneAttachment.get(zone).orElseThrow();
    int cost = 0;
    int jetCost = 0;
    UnitType unfit = null;
    for (final UnitType type : aircraft) {
      if (zoneAttachment.getZoneType() == ZoneType.COASTAL
          && AirRange.landWithinReach(
              player, zoneAttachment.getCoveredTerritories(), AirRange.range(type, player))) {
        continue;
      }
      final int carrierCost = type.getUnitAttachment().getCarrierCost();
      if (carrierCost < 1) {
        unfit = unfit == null ? type : unfit;
        continue;
      }
      cost += carrierCost;
      if (AirUnitAttachment.get(type).map(AirUnitAttachment::isFleetCarrierOnly).orElse(false)) {
        jetCost += carrierCost;
      }
    }
    int capacity = 0;
    int fleetCapacity = 0;
    for (final Territory seaZone : zoneAttachment.getCoveredTerritories()) {
      for (final Unit carrier :
          seaZone
              .getUnitCollection()
              .getMatches(
                  u -> u.isOwnedBy(player) && u.getUnitAttachment().getCarrierCapacity() > 0)) {
        capacity += carrier.getUnitAttachment().getCarrierCapacity();
        if (AirUnitAttachment.isFleetCarrier(carrier)) {
          fleetCapacity += carrier.getUnitAttachment().getCarrierCapacity();
        }
      }
    }
    return new CarrierLoad(cost, capacity, jetCost, fleetCapacity, unfit);
  }

  /** A nation's carrier load in an ocean or coastal zone once these moves are made. */
  public static CarrierLoad carrierLoadAfter(
      final GamePlayer player, final Stage stage, final List<AirMove> moves, final Territory zone) {
    return carrierLoad(player, zone, aircraftAfterMoves(player, stage, moves, zone));
  }

  /** Types of the player's aircraft in the zone once the moves are made (bomber stage: bombers). */
  private static List<UnitType> aircraftAfterMoves(
      final GamePlayer player, final Stage stage, final List<AirMove> moves, final Territory zone) {
    final List<UnitType> result = new ArrayList<>();
    for (final Unit unit :
        zone.getUnitCollection()
            .getMatches(
                u ->
                    u.isOwnedBy(player)
                        && Matches.unitIsAir().test(u)
                        && (stage == Stage.FIGHTERS || isInStage(u, stage)))) {
      result.add(unit.getType());
    }
    for (final AirMove move : moves) {
      for (int i = 0; i < move.count(); i++) {
        if (move.from().equals(zone)) {
          result.remove(move.unitType());
        }
        if (move.to().equals(zone)) {
          result.add(move.unitType());
        }
      }
    }
    return result;
  }

  /** The change that carries out the moves, picking the actual aircraft. */
  public static Change toChange(
      final GamePlayer player, final Stage stage, final List<AirMove> moves) {
    final CompositeChange change = new CompositeChange();
    final Map<Territory, Set<Unit>> taken = new HashMap<>();
    for (final AirMove move : moves) {
      final Set<Unit> alreadyTaken = taken.computeIfAbsent(move.from(), t -> new HashSet<>());
      final List<Unit> units =
          getAircraft(player, move.from(), stage).stream()
              .filter(u -> u.getType().equals(move.unitType()) && !alreadyTaken.contains(u))
              .limit(move.count())
              .toList();
      alreadyTaken.addAll(units);
      change.add(ChangeFactory.moveUnits(move.from(), move.to(), units));
    }
    return change;
  }

  /** One line per move, for history and display. */
  public static List<String> describe(final Collection<AirMove> moves) {
    final List<String> lines = new ArrayList<>();
    for (final AirMove move : moves) {
      lines.add(
          move.count()
              + " "
              + move.unitType().getName()
              + ": "
              + move.from().getName()
              + " to "
              + move.to().getName());
    }
    return lines;
  }

  private static String key(final Territory from, final UnitType type) {
    return from.getName() + "|" + type.getName();
  }
}
