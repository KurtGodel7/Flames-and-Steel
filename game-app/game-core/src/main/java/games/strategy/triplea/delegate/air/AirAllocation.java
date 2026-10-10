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

  /** Air boxes the player's aircraft of this type may move to from this box this round. */
  public static List<Territory> getDestinations(
      final GamePlayer player, final Territory from, final UnitType type, final GameMap map) {
    final List<Territory> result = new ArrayList<>();
    for (final Territory to : AirZoneAttachment.getAirZones(map)) {
      if (!to.equals(from) && canMove(from, to) && entryProblem(player, type, to).isEmpty()) {
        result.add(to);
      }
    }
    return result;
  }

  /** An aircraft moves at most one box a round, to a box listed as adjacent. */
  public static boolean canMove(final Territory from, final Territory to) {
    return AirZoneAttachment.get(from).orElseThrow().getAdjacentZones().contains(to);
  }

  /** Whether the box lets this nation in at all (its players list). */
  public static boolean mayEnter(final GamePlayer player, final Territory zone) {
    final List<GamePlayer> players = AirZoneAttachment.get(zone).orElseThrow().getPlayers();
    return players.isEmpty() || players.contains(player);
  }

  /**
   * Why this nation's aircraft of this type may not be in the box, apart from distance and carrier
   * space, or empty if it may: the box must admit the nation; a front needs one of the nation's
   * landing spots; in a bombing zone the attackers bring only aircraft with a strategic bombing
   * value and the defenders only fighters; and an aircraft that strikes (not a fighter) needs at
   * least one target it can reach there.
   */
  public static Optional<String> entryProblem(
      final GamePlayer player, final UnitType type, final Territory zone) {
    final AirZoneAttachment attachment = AirZoneAttachment.get(zone).orElseThrow();
    if (!mayEnter(player, zone)) {
      return Optional.of(entryRefusal(player, attachment));
    }
    final boolean fighter =
        AirUnitAttachment.get(type).map(AirUnitAttachment::isFighter).orElse(false);
    if (attachment.getZoneType() == ZoneType.BOMB) {
      final GamePlayer bombed = attachment.getBombedPlayer();
      final boolean defending =
          bombed == null || !player.getData().getRelationshipTracker().isAtWar(player, bombed);
      if (defending) {
        return fighter
            ? Optional.empty()
            : Optional.of("Only fighters defend against strategic bombing.");
      }
      if (AirUnitAttachment.get(type).map(AirUnitAttachment::getBombingDamage).orElse(0) <= 0) {
        return Optional.of("No strategic bombing value.");
      }
      return AirCombat.countTargets(player, zone, type) > 0
          ? Optional.empty()
          : Optional.of("No enemy factories or APCs within reach.");
    }
    if (!AirRange.hasLandingSpot(player, zone)) {
      return Optional.of("No landing spot in this front.");
    }
    if (!fighter && AirCombat.countTargets(player, zone, type) == 0) {
      return Optional.of(
          attachment.getZoneType() == ZoneType.OCEAN
              ? "No enemy ships within reach."
              : "No targets within reach.");
    }
    return Optional.empty();
  }

  /** "Soviet aircraft may not enter Western fronts" and the like. */
  private static String entryRefusal(final GamePlayer player, final AirZoneAttachment attachment) {
    if (attachment.getFrontTag() != null) {
      return player.getName() + " aircraft may not enter " + attachment.getFrontTag() + " fronts.";
    }
    return player.getName() + " aircraft may not enter this zone.";
  }

  /**
   * Why {@code count} aircraft of a type may not move from one box to another on top of the moves
   * already staged, or empty if they may. {@link #validate} checks the whole allocation when it is
   * submitted.
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
      return Optional.of("Two boxes away: needs two rounds.");
    }
    final Optional<String> entry = entryProblem(player, unitType, to);
    if (entry.isPresent()) {
      return entry;
    }
    if (AirZoneAttachment.get(to).orElseThrow().getZoneType() == ZoneType.OCEAN) {
      final List<AirMove> moves = new ArrayList<>(staged);
      moves.add(new AirMove(from, to, unitType, count));
      return carrierProblem(
          player, to, carrierLoad(player, to, aircraftAfterMoves(player, stage, moves, to)));
    }
    return Optional.empty();
  }

  private static Optional<String> carrierProblem(
      final GamePlayer player, final Territory zone, final CarrierLoad load) {
    if (load.unfit() != null) {
      return Optional.of(
          typeName(load.unfit())
              + " cannot fly from carriers: "
              + zoneName(zone)
              + " is carrier-only.");
    }
    if (load.cost() > load.capacity()) {
      return Optional.of("No carrier capacity: " + load.cost() + "/" + load.capacity());
    }
    if (load.jetCost() > load.fleetCapacity()) {
      return Optional.of(
          "Jets need fleet carriers: " + load.jetCost() + "/" + load.fleetCapacity());
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
        return Optional.of("Aircraft may only move between air boxes.");
      }
      if (move.from().equals(move.to())) {
        return Optional.of("An aircraft must move to a different box.");
      }
      if (!canMove(move.from(), move.to())) {
        return Optional.of(
            zoneName(move.from())
                + " to "
                + zoneName(move.to())
                + ": two boxes away, needs two rounds.");
      }
      final Optional<String> entry = entryProblem(player, move.unitType(), move.to());
      if (entry.isPresent()) {
        return Optional.of(
            typeName(move.unitType()) + " to " + zoneName(move.to()) + ": " + entry.get());
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
                + typeName(move.unitType())
                + " available in "
                + zoneName(move.from())
                + ".");
      }
    }
    return checkCarrierCapacity(player, stage, moves);
  }

  /** Carrier capacity applies only in carrier-only (ocean) fronts. */
  private static Optional<String> checkCarrierCapacity(
      final GamePlayer player, final Stage stage, final List<AirMove> moves) {
    final Set<Territory> destinations = new HashSet<>();
    for (final AirMove move : moves) {
      if (AirZoneAttachment.get(move.to()).orElseThrow().getZoneType() == ZoneType.OCEAN) {
        destinations.add(move.to());
      }
    }
    for (final Territory zone : destinations) {
      final Optional<String> problem =
          carrierProblem(
              player,
              zone,
              carrierLoad(player, zone, aircraftAfterMoves(player, stage, moves, zone)));
      if (problem.isPresent()) {
        return Optional.of(zoneName(zone) + ": " + problem.get());
      }
    }
    return Optional.empty();
  }

  /**
   * How a nation's aircraft in a carrier-only front sit on its carriers there.
   *
   * @param cost carrier spaces the aircraft take
   * @param capacity carrier spaces of the nation's carriers in the front's sea zones
   * @param jetCost spaces taken by aircraft that need fleet carriers (jets)
   * @param fleetCapacity spaces on fleet carriers
   * @param unfit an aircraft type that cannot use a carrier, if any
   */
  public record CarrierLoad(
      int cost, int capacity, int jetCost, int fleetCapacity, @Nullable UnitType unfit) {}

  /** Carrier load of these aircraft types in the front (islands give no capacity). */
  public static CarrierLoad carrierLoad(
      final GamePlayer player, final Territory zone, final Collection<UnitType> aircraft) {
    final AirZoneAttachment zoneAttachment = AirZoneAttachment.get(zone).orElseThrow();
    int cost = 0;
    int jetCost = 0;
    UnitType unfit = null;
    for (final UnitType type : aircraft) {
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

  /** A readable unit type name: "diveBomber" becomes "Dive bomber". */
  public static String typeName(final UnitType type) {
    final String name = type.getName();
    final StringBuilder sb = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      final char c = name.charAt(i);
      if (i == 0) {
        sb.append(Character.toUpperCase(c));
      } else if (Character.isUpperCase(c) && Character.isLowerCase(name.charAt(i - 1))) {
        sb.append(' ').append(Character.toLowerCase(c));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /** A nation's carrier load in a carrier-only front once these moves are made. */
  public static CarrierLoad carrierLoadAfter(
      final GamePlayer player, final Stage stage, final List<AirMove> moves, final Territory zone) {
    return carrierLoad(player, zone, aircraftAfterMoves(player, stage, moves, zone));
  }

  /**
   * Types of the player's aircraft in the zone once the moves are made. In the bomber step the
   * fighters there count too: they have not moved yet, so the bombers must leave them carrier room.
   */
  private static List<UnitType> aircraftAfterMoves(
      final GamePlayer player, final Stage stage, final List<AirMove> moves, final Territory zone) {
    final List<UnitType> result = new ArrayList<>();
    for (final Unit unit :
        zone.getUnitCollection()
            .getMatches(u -> u.isOwnedBy(player) && Matches.unitIsAir().test(u))) {
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
