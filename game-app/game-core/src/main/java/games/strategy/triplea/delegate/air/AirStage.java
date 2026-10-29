package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.delegate.air.AirBases.Flight;
import games.strategy.triplea.delegate.air.AirBases.Reach;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel (requests 35 and 38): the simultaneous air stages. Aircraft sit on bases on the
 * map. In the bomber stage each nation's bombers either fly to a target or stay; in the fighter
 * stage its fighter-types fly over a territory where bombers are going, to escort or defend, or
 * stay. A choice is a {@link TargetAssignment} whose zone is the base the aircraft fly from.
 *
 * <p>A flight is legal when the target is within the aircraft's range and a landing spot is within
 * the range it has left there ({@link AirBases}); every flying aircraft of the nation must be sure
 * of its own spot. Caps ({@link AirCover}) are per side and territory, and some allies never share
 * a battle ({@link AirPriority}).
 */
@UtilityClass
public class AirStage {

  /** The air stages: bombers, fighters, and planes go home at the end of the round. */
  public enum Stage {
    BOMBERS,
    FIGHTERS,
    HOME
  }

  /** What one nation chose in a stage: targets (air stages) or landings (planes go home). */
  public record Choice(List<TargetAssignment> targets, List<Landing> landings)
      implements Serializable {
    @Serial private static final long serialVersionUID = 4227146303818440914L;

    public static Choice none() {
      return new Choice(List.of(), List.of());
    }

    public static Choice of(final List<TargetAssignment> targets) {
      return new Choice(List.copyOf(targets), List.of());
    }

    public boolean isEmpty() {
      return targets.isEmpty() && landings.isEmpty();
    }
  }

  /**
   * Planes go home: {@code count} aircraft of one type land (or rebase) from one place to another.
   * For airborne aircraft {@code from} is the territory they are over; for aircraft that stayed
   * home, their base.
   */
  public record Landing(
      Territory from, boolean airborne, UnitType unitType, int count, Territory to)
      implements Serializable {
    @Serial private static final long serialVersionUID = -3140711206318823311L;
  }

  /** "Torpedo bomber" for torpedoBomber. */
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

  private static boolean isFighter(final UnitType type) {
    return AirUnitAttachment.get(type).map(AirUnitAttachment::isFighter).orElse(false);
  }

  /** The aircraft of the stage: bombers in the bomber stage, fighter-types in the fighter stage. */
  public static boolean isOfStage(final UnitType type, final Stage stage) {
    return switch (stage) {
      case BOMBERS -> AirBases.isAircraft(type) && !isFighter(type);
      case FIGHTERS -> AirBases.isAircraft(type) && isFighter(type);
      case HOME -> AirBases.isAircraft(type);
    };
  }

  /** The nation's aircraft of the stage on the base, undamaged first. */
  public static List<Unit> aircraftAt(
      final GamePlayer player, final Territory base, final Stage stage) {
    if (AirBases.isAirborneTerritory(base)) {
      return List.of();
    }
    return base.getUnitCollection()
        .getMatches(u -> u.isOwnedBy(player) && isOfStage(u.getType(), stage))
        .stream()
        .sorted(Comparator.comparingInt(Unit::getHits))
        .toList();
  }

  /** The bases with the nation's aircraft of the stage. */
  public static List<Territory> bases(final GamePlayer player, final Stage stage) {
    final List<Territory> result = new ArrayList<>();
    for (final Territory t : player.getData().getMap().getTerritories()) {
      if (!aircraftAt(player, t, stage).isEmpty()) {
        result.add(t);
      }
    }
    return result;
  }

  /** The territories where any nation's bombers are going this round: where fighters may go. */
  public static Set<Territory> bomberPlaces(
      final Map<GamePlayer, ? extends Collection<TargetAssignment>> bombers) {
    final Set<Territory> result = new LinkedHashSet<>();
    bombers.values().forEach(list -> list.forEach(a -> result.add(a.target().territory())));
    return result;
  }

  /**
   * Every target (bomber stage) or place (fighter stage, as cover targets) the nation's aircraft of
   * this type on the base could fly to on their own: in range, with somewhere to land, and of a
   * kind the type may take. Caps, the shared carrier room and priority are checked by {@link
   * #problem}.
   */
  public static List<Target> candidates(
      final GamePlayer player,
      final Territory base,
      final UnitType type,
      final Stage stage,
      final Map<GamePlayer, ? extends Collection<TargetAssignment>> bombers,
      final Reach reach) {
    final List<Target> result = new ArrayList<>();
    if (!isOfStage(type, stage) || stage == Stage.HOME) {
      return result;
    }
    final int range = AirRange.range(type, player);
    final Map<Territory, Integer> out = AirBases.distances(player, base, range);
    final Collection<Territory> places =
        stage == Stage.FIGHTERS ? bomberPlaces(bombers) : out.keySet();
    for (final Territory t : places) {
      final Integer d = out.get(t);
      if (d == null || !reach.canLand(type, t, range - d)) {
        continue;
      }
      if (stage == Stage.FIGHTERS) {
        if (AirCover.coverRule(player, type, t, bombers).isEmpty()) {
          result.add(AirCover.over(t));
        }
        continue;
      }
      for (final Target target : AirCombat.targetsIn(player, t)) {
        if (AirCombat.mayAttack(type, target.kind())) {
          result.add(target);
        }
      }
    }
    return result;
  }

  /** {@link #candidates} with a fresh landing search. */
  public static List<Target> candidates(
      final GamePlayer player,
      final Territory base,
      final UnitType type,
      final Stage stage,
      final Map<GamePlayer, ? extends Collection<TargetAssignment>> bombers) {
    return candidates(player, base, type, stage, bombers, new Reach(player));
  }

  /**
   * Why one choice can't be made, on its own, or empty: "Hamburg is 5 steps from Lwow; fighters fly
   * 3", "Fighters can't escort the bombing of ...".
   */
  public static Optional<String> whyNot(
      final GamePlayer player,
      final Territory base,
      final UnitType type,
      final Target target,
      final Stage stage,
      final Map<GamePlayer, ? extends Collection<TargetAssignment>> bombers) {
    final List<Unit> here =
        aircraftAt(player, base, stage).stream().filter(u -> u.getType().equals(type)).toList();
    if (here.isEmpty()) {
      return Optional.of("No " + typeName(type).toLowerCase(java.util.Locale.ROOT) + " at " + base.getName() + ".");
    }
    if (stage == Stage.FIGHTERS) {
      if (target.kind() != TargetKind.COVER) {
        return Optional.of("Fighters escort or defend; they make no strikes.");
      }
      if (!bomberPlaces(bombers).contains(target.territory())) {
        return Optional.of("No bombers are going to " + target.territory().getName() + " this round.");
      }
      final Optional<String> rule = AirCover.coverRule(player, type, target.territory(), bombers);
      if (rule.isPresent()) {
        return rule;
      }
    } else {
      if (!AirCombat.mayAttack(type, target.kind())) {
        return Optional.of(typeName(type) + " can't attack " + target.describe() + ".");
      }
      if (!AirCombat.targetsIn(player, target.territory()).contains(target)) {
        return Optional.of("Nothing to attack: " + target.describe() + ".");
      }
    }
    return AirBases.flightProblem(here.get(0), base, target.territory());
  }

  /**
   * The flights a nation's choice makes: for each choice, that many aircraft of the type from the
   * base (undamaged first), over the target with the range left there.
   */
  public static List<Flight> flights(
      final GamePlayer player, final Stage stage, final Collection<TargetAssignment> choice) {
    final List<Flight> result = new ArrayList<>();
    final Set<Unit> used = new HashSet<>();
    for (final TargetAssignment a : choice) {
      final List<Unit> units =
          aircraftAt(player, a.zone(), stage).stream()
              .filter(u -> u.getType().equals(a.unitType()) && !used.contains(u))
              .limit(a.count())
              .toList();
      for (final Unit u : units) {
        used.add(u);
        final int left =
            AirBases.rangeLeftAt(u, a.zone(), a.target().territory()).orElse(-1);
        result.add(new Flight(u, a.target().territory(), left));
      }
    }
    return result;
  }

  /** The nation's aircraft already in the air (earlier stage), as flights. */
  public static List<Flight> airborneFlights(final GamePlayer player) {
    return AirBases.airborne(player.getData())
        .map(
            a ->
                a.getUnitCollection().getMatches(u -> u.isOwnedBy(player)).stream()
                    .filter(AirBases::isAirborne)
                    .map(u -> new Flight(u, u.getFlightOver(), u.getFlightRangeLeft()))
                    .toList())
        .orElse(List.of());
  }

  /**
   * Why the nation's choice for the stage is not allowed, or empty.
   *
   * @param others the other nations' choices so far this stage, finished or still being made
   *     (allies share the caps; priority nations lock others out)
   * @param bombers every nation's bomber targets (fighter stage; empty in the bomber stage)
   */
  public static Optional<String> problem(
      final GamePlayer player,
      final Stage stage,
      final Choice choice,
      final Map<GamePlayer, ? extends Collection<TargetAssignment>> others,
      final Map<GamePlayer, ? extends Collection<TargetAssignment>> bombers) {
    final IntegerMap<String> used = new IntegerMap<>();
    final Reach reach = new Reach(player);
    final Map<String, List<Target>> listed = new LinkedHashMap<>();
    for (final TargetAssignment a : choice.targets()) {
      if (a.count() <= 0) {
        return Optional.of("Counts must be positive.");
      }
      if (!isOfStage(a.unitType(), stage)) {
        return Optional.of(typeName(a.unitType()) + " don't fly in this stage.");
      }
      final String key = a.zone().getName() + "|" + a.unitType().getName();
      final List<Target> legal =
          listed.computeIfAbsent(
              key, k -> candidates(player, a.zone(), a.unitType(), stage, bombers, reach));
      if (!legal.contains(a.target())) {
        return Optional.of(
            whyNot(player, a.zone(), a.unitType(), a.target(), stage, bombers)
                .orElse(
                    typeName(a.unitType())
                        + " at "
                        + a.zone().getName()
                        + " can't take "
                        + a.target().describe()
                        + "."));
      }
      final Optional<GamePlayer> locker = AirPriority.lockedBy(player, a.target().territory(), others);
      if (locker.isPresent()) {
        return Optional.of(AirPriority.lockedText(a.target().territory(), locker.get()));
      }
      // request 43 section 1: one nation's bombers per factory
      final Optional<GamePlayer> raider = AirPriority.raidTakenBy(player, a.target(), others);
      if (raider.isPresent()) {
        return Optional.of(AirPriority.raidText(a.target().territory(), raider.get()));
      }
      used.add(key, a.count());
      final long available =
          aircraftAt(player, a.zone(), stage).stream()
              .filter(u -> u.getType().equals(a.unitType()))
              .count();
      if (used.getInt(key) > available) {
        return Optional.of(
            "Only " + available + " " + typeName(a.unitType()) + " at " + a.zone().getName() + ".");
      }
    }
    final Optional<String> cap = AirCover.capProblem(player, choice.targets(), others, bombers);
    if (cap.isPresent()) {
      return cap;
    }
    final List<Flight> all = new ArrayList<>(airborneFlights(player));
    all.addAll(flights(player, stage, choice.targets()));
    return AirBases.landingProblem(player, all);
  }

  /**
   * Whether the nation has anything to do in the stage: aircraft of the stage on a base that could
   * fly somewhere.
   */
  public static boolean hasWork(
      final GamePlayer player,
      final Stage stage,
      final Map<GamePlayer, ? extends Collection<TargetAssignment>> bombers,
      final @Nullable games.strategy.engine.data.GameMap map) {
    if (map == null || stage == Stage.HOME) {
      return false;
    }
    if (stage == Stage.FIGHTERS && bomberPlaces(bombers).isEmpty()) {
      return false;
    }
    final Reach reach = new Reach(player);
    for (final Territory base : bases(player, stage)) {
      final Set<UnitType> types = new LinkedHashSet<>();
      aircraftAt(player, base, stage).forEach(u -> types.add(u.getType()));
      for (final UnitType type : types) {
        if (!candidates(player, base, type, stage, bombers, reach).isEmpty()) {
          return true;
        }
      }
    }
    return false;
  }

  /** Every nation's targets in these choices. */
  public static Map<GamePlayer, List<TargetAssignment>> targetsOf(
      final Map<GamePlayer, Choice> choices) {
    final Map<GamePlayer, List<TargetAssignment>> result = new LinkedHashMap<>();
    choices.forEach((p, c) -> result.put(p, c.targets()));
    return result;
  }

  /** "3 dive bombers from Warsaw → Kiev". */
  public static String describe(final TargetAssignment a) {
    return a.count()
        + " "
        + AirCombat.unitName(a.unitType())
        + (a.count() == 1 ? "" : "s")
        + " from "
        + a.zone().getName()
        + " → "
        + a.target().describe();
  }
}
