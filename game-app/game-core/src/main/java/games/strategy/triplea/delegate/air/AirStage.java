package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.GameMap;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment.ZoneType;
import games.strategy.triplea.delegate.air.AirAllocation.AirMove;
import games.strategy.triplea.delegate.air.AirAllocation.Stage;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
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
 * Flames and Steel (request 35 part 3): the simultaneous air stages. In one screen per nation the
 * bombers move and choose targets (bomber stage), then the fighter-types move and are placed
 * (fighter stage). A target or place in an adjacent front or bombing zone moves the aircraft
 * there; aircraft may also just move to an adjacent box, or stay.
 *
 * <p>On the nation's screen each choice is a {@link TargetAssignment} whose zone is the box the
 * aircraft start in (its origin): its target is a real target or place, or a {@link
 * TargetKind#MOVE} target naming the box it moves to. {@link #toChoice} turns them into the moves
 * and the targets (by the box the aircraft end in) that the stage stores.
 */
@UtilityClass
public class AirStage {

  /** What one nation chose in a stage: moves between boxes, and targets in the boxes they end in. */
  public record Choice(List<AirMove> moves, List<TargetAssignment> targets)
      implements Serializable {
    @Serial private static final long serialVersionUID = 4227146303818440913L;

    public static Choice none() {
      return new Choice(List.of(), List.of());
    }

    public boolean isEmpty() {
      return moves.isEmpty() && targets.isEmpty();
    }
  }

  /** "Move to Eastern Front": a choice with no target. */
  public static Target moveTo(final Territory box) {
    return new Target(box, TargetKind.MOVE);
  }

  public static boolean isMove(final TargetAssignment a) {
    return a.target().kind() == TargetKind.MOVE;
  }

  private static boolean isFighter(final UnitType type) {
    return games.strategy.triplea.attachments.AirUnitAttachment.get(type)
        .map(games.strategy.triplea.attachments.AirUnitAttachment::isFighter)
        .orElse(false);
  }

  /** The aircraft of the stage: bombers in the bomber stage, fighter-types in the fighter stage. */
  public static boolean isOfStage(final UnitType type, final Stage stage) {
    return isFighter(type) == (stage == Stage.FIGHTERS);
  }

  /** The boxes aircraft of this type may move to from the box this round. */
  public static List<Territory> neighbours(
      final GamePlayer player, final Territory origin, final UnitType type) {
    final List<Territory> result = new ArrayList<>();
    for (final Territory to : AirZoneAttachment.getAirZones(origin.getData().getMap())) {
      if (!to.equals(origin)
          && AirAllocation.canMove(player, type, origin, to)
          && AirAllocation.entryProblem(player, type, to).isEmpty()) {
        result.add(to);
      }
    }
    return result;
  }

  /**
   * Targets (bomber stage) or places (fighter stage, as cover targets) the nation's aircraft of
   * this type could reach in a box, ignoring where they start.
   */
  private static List<Target> targetsIn(
      final GamePlayer player,
      final Territory box,
      final UnitType type,
      final Stage stage,
      final Map<GamePlayer, List<TargetAssignment>> bombers) {
    if (AirZoneAttachment.get(box).map(a -> a.getZoneType() == ZoneType.RESERVE).orElse(true)) {
      return List.of();
    }
    if (stage == Stage.FIGHTERS) {
      return AirCover.places(player, box, type, bombers).stream().map(AirCover::over).toList();
    }
    return AirCombat.getTargets(player, box, type);
  }

  /**
   * Every target or place the nation's aircraft of this type starting in the origin box may choose:
   * those in the box, then those in each box it may move to, as (box it ends in, target).
   */
  public static Map<Target, Territory> candidates(
      final GamePlayer player,
      final Territory origin,
      final UnitType type,
      final Stage stage,
      final Map<GamePlayer, List<TargetAssignment>> bombers) {
    final Map<Target, Territory> result = new LinkedHashMap<>();
    for (final Target t : targetsIn(player, origin, type, stage, bombers)) {
      result.putIfAbsent(t, origin);
    }
    for (final Territory to : neighbours(player, origin, type)) {
      for (final Target t : targetsIn(player, to, type, stage, bombers)) {
        result.putIfAbsent(t, to);
      }
    }
    return result;
  }

  /**
   * The box an aircraft starting in the origin ends in when it takes this target: the origin if
   * the target is there, else the first box it may move to that has it; the box itself for a move.
   */
  public static Optional<Territory> destination(
      final GamePlayer player,
      final Territory origin,
      final UnitType type,
      final Target target,
      final Stage stage,
      final Map<GamePlayer, List<TargetAssignment>> bombers) {
    if (target.kind() == TargetKind.MOVE) {
      return neighbours(player, origin, type).contains(target.territory())
          ? Optional.of(target.territory())
          : Optional.empty();
    }
    return Optional.ofNullable(candidates(player, origin, type, stage, bombers).get(target));
  }

  /**
   * The screen's choices (origin form) as the stage stores them: the moves, merged by box and
   * type, and the targets by the box the aircraft end in. Choices that cannot be placed are left
   * out.
   */
  public static Choice toChoice(
      final GamePlayer player,
      final Stage stage,
      final Collection<TargetAssignment> originForm,
      final Map<GamePlayer, List<TargetAssignment>> bombers) {
    final Map<String, AirMove> moves = new LinkedHashMap<>();
    final Map<String, TargetAssignment> targets = new LinkedHashMap<>();
    for (final TargetAssignment a : originForm) {
      final Optional<Territory> to =
          destination(player, a.zone(), a.unitType(), a.target(), stage, bombers);
      if (to.isEmpty()) {
        continue;
      }
      if (!to.get().equals(a.zone())) {
        moves.merge(
            a.zone().getName() + "|" + to.get().getName() + "|" + a.unitType().getName(),
            new AirMove(a.zone(), to.get(), a.unitType(), a.count()),
            (x, y) -> new AirMove(x.from(), x.to(), x.unitType(), x.count() + y.count()));
      }
      if (!isMove(a)) {
        targets.merge(
            to.get().getName() + "|" + a.unitType().getName() + "|" + a.target(),
            new TargetAssignment(to.get(), a.unitType(), a.count(), a.target()),
            (x, y) -> new TargetAssignment(x.zone(), x.unitType(), x.count() + y.count(), x.target()));
      }
    }
    return new Choice(List.copyOf(moves.values()), List.copyOf(targets.values()));
  }

  /** The nation's aircraft of the stage in the box once the moves are made, by type. */
  public static IntegerMap<UnitType> afterMoves(
      final GamePlayer player,
      final Stage stage,
      final Collection<AirMove> moves,
      final Territory box) {
    final IntegerMap<UnitType> result = new IntegerMap<>();
    if (AirZoneAttachment.get(box).map(a -> a.getZoneType() == ZoneType.RESERVE).orElse(true)) {
      return result;
    }
    for (final Unit u : AirAllocation.getAircraft(player, box, stage)) {
      result.add(u.getType(), 1);
    }
    for (final AirMove m : moves) {
      if (m.from().equals(box)) {
        result.add(m.unitType(), -m.count());
      }
      if (m.to().equals(box)) {
        result.add(m.unitType(), m.count());
      }
    }
    return result;
  }

  /**
   * Why the nation's choice for the stage is not allowed, or empty.
   *
   * @param others the other nations' choices accepted so far this stage (allies share the caps)
   * @param bombers every nation's bomber targets (fighter stage; empty in the bomber stage)
   */
  public static Optional<String> problem(
      final GamePlayer player,
      final Stage stage,
      final Choice choice,
      final Map<GamePlayer, Choice> others,
      final Map<GamePlayer, List<TargetAssignment>> bombers) {
    final GameMap map = player.getData().getMap();
    final Optional<String> moves = AirAllocation.validate(player, stage, choice.moves(), map);
    if (moves.isPresent()) {
      return moves;
    }
    final IntegerMap<String> used = new IntegerMap<>();
    final Set<Territory> boxes = new LinkedHashSet<>();
    for (final TargetAssignment a : choice.targets()) {
      if (a.count() <= 0) {
        return Optional.of("Counts must be positive.");
      }
      if (!AirZoneAttachment.isAirZone(a.zone())) {
        return Optional.of(a.zone().getName() + " is not an air box.");
      }
      if (!isOfStage(a.unitType(), stage) || isMove(a)) {
        return Optional.of(AirAllocation.typeName(a.unitType()) + " cannot take that here.");
      }
      if (!targetsIn(player, a.zone(), a.unitType(), stage, bombers).contains(a.target())) {
        return Optional.of(
            AirAllocation.typeName(a.unitType())
                + " in "
                + AirAllocation.zoneName(a.zone())
                + " cannot reach "
                + a.target().describe()
                + ".");
      }
      used.add(a.zone().getName() + "|" + a.unitType().getName(), a.count());
      boxes.add(a.zone());
    }
    for (final Territory box : boxes) {
      final IntegerMap<UnitType> there = afterMoves(player, stage, choice.moves(), box);
      for (final UnitType type : there.keySet()) {
        final int wanted = used.getInt(box.getName() + "|" + type.getName());
        if (wanted > there.getInt(type)) {
          return Optional.of(
              "Only "
                  + there.getInt(type)
                  + " "
                  + AirAllocation.typeName(type)
                  + " in "
                  + AirAllocation.zoneName(box)
                  + " after the moves.");
        }
      }
      for (final TargetAssignment a : choice.targets()) {
        if (a.zone().equals(box) && there.getInt(a.unitType()) == 0) {
          return Optional.of(
              "No " + AirAllocation.typeName(a.unitType()) + " in " + AirAllocation.zoneName(box) + ".");
        }
      }
    }
    final Map<GamePlayer, List<TargetAssignment>> otherTargets = new LinkedHashMap<>();
    others.forEach((p, c) -> otherTargets.put(p, c.targets()));
    return AirCover.capProblem(player, choice.targets(), otherTargets, bombers);
  }

  /** Every nation's targets in these choices. */
  public static Map<GamePlayer, List<TargetAssignment>> targetsOf(
      final Map<GamePlayer, Choice> choices) {
    final Map<GamePlayer, List<TargetAssignment>> result = new LinkedHashMap<>();
    choices.forEach((p, c) -> result.put(p, c.targets()));
    return result;
  }

  /** Every nation's moves in these choices. */
  public static Map<GamePlayer, List<AirMove>> movesOf(final Map<GamePlayer, Choice> choices) {
    final Map<GamePlayer, List<AirMove>> result = new LinkedHashMap<>();
    choices.forEach((p, c) -> result.put(p, c.moves()));
    return result;
  }

  /**
   * Whether the nation has anything to do in the stage: aircraft of the stage in a box (not the
   * reserve) that can move somewhere or reach a target or place.
   */
  public static boolean hasWork(
      final GamePlayer player,
      final Stage stage,
      final Map<GamePlayer, List<TargetAssignment>> bombers,
      final @Nullable GameMap map) {
    if (map == null) {
      return false;
    }
    for (final Territory box : AirZoneAttachment.getAirZones(map)) {
      for (final Unit u : AirAllocation.getAircraft(player, box, stage)) {
        if (!neighbours(player, box, u.getType()).isEmpty()
            || !candidates(player, box, u.getType(), stage, bombers).isEmpty()) {
          return true;
        }
      }
    }
    return false;
  }
}
