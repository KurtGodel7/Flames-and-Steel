package games.strategy.triplea.delegate.battle.casualty;

import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.data.CasualtyList;
import games.strategy.triplea.formatter.UnitNames;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.experimental.UtilityClass;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel sticky hits (game property "Sticky Hits"). Once a unit has been hit, further
 * hits of the same damage type must go to it until it dies. Damage lasts until units heal at the
 * end of the round, so a unit damaged earlier (in this battle, an earlier battle or the air phase)
 * takes hits first. Land, sea and air damage are separate types.
 *
 * <p>For one set of hits this means, within each damage type: every already-damaged unit must be
 * destroyed before any undamaged unit is hit, and at most one undamaged unit may be left damaged
 * but alive. The owner still chooses which units, within those limits.
 */
@UtilityClass
public class StickyHits {

  private enum DamageType {
    LAND,
    SEA,
    AIR
  }

  /**
   * Hits that sticky hits forces onto already-damaged units, applied without asking the owner.
   *
   * @param killed damaged units destroyed by these hits
   * @param damaged one entry per hit on a damaged unit that survives (only when the hits run out)
   * @param hitsUsed number of hits used
   */
  public record ForcedHits(List<Unit> killed, List<Unit> damaged, int hitsUsed) {}

  /** Already-damaged units take hits first, the most damaged first, each until destroyed. */
  public static ForcedHits getForcedHits(final Collection<Unit> targets, final int hits) {
    final List<Unit> alreadyDamaged =
        targets.stream()
            .filter(u -> u.getHits() > 0)
            .sorted(Comparator.comparingInt(StickyHits::hitPointsLeft))
            .toList();
    final List<Unit> killed = new ArrayList<>();
    final List<Unit> damaged = new ArrayList<>();
    int hitsLeft = hits;
    for (final Unit unit : alreadyDamaged) {
      if (hitsLeft <= 0) {
        break;
      }
      final int hitPointsLeft = hitPointsLeft(unit);
      if (hitsLeft >= hitPointsLeft) {
        killed.add(unit);
        hitsLeft -= hitPointsLeft;
      } else {
        for (int i = 0; i < hitsLeft; i++) {
          damaged.add(unit);
        }
        hitsLeft = 0;
      }
    }
    return new ForcedHits(killed, damaged, hits - hitsLeft);
  }

  public static int hitPointsLeft(final Unit unit) {
    return Unit.maxHitPoints(unit) - unit.getHits();
  }

  /** Why this choice of casualties breaks the sticky hit rule, if it does. */
  public static Optional<String> validate(
      final Collection<Unit> targets, final Collection<Unit> killed, final List<Unit> damaged) {
    final IntegerMap<Unit> newHits = new IntegerMap<>();
    for (final Unit unit : damaged) {
      newHits.add(unit, 1);
    }
    final Set<Unit> killedSet = new HashSet<>(killed);
    for (final DamageType type : DamageType.values()) {
      final List<Unit> group = targets.stream().filter(u -> typeOf(u) == type).toList();
      final List<Unit> alreadyDamaged = group.stream().filter(u -> u.getHits() > 0).toList();
      final List<Unit> freshHit =
          group.stream()
              .filter(u -> u.getHits() == 0)
              .filter(u -> killedSet.contains(u) || newHits.getInt(u) > 0)
              .toList();
      if (!freshHit.isEmpty() && alreadyDamaged.stream().anyMatch(u -> !killedSet.contains(u))) {
        return Optional.of(
            "Sticky hits: damaged units must be destroyed before undamaged units are hit ("
                + describe(alreadyDamaged.stream().filter(u -> !killedSet.contains(u)).toList())
                + ").");
      }
      final List<Unit> leftDamaged = freshHit.stream().filter(u -> !killedSet.contains(u)).toList();
      if (leftDamaged.size() > 1) {
        return Optional.of(
            "Sticky hits: a hit unit keeps taking hits until it is destroyed, so only one unit "
                + "may be left damaged ("
                + describe(leftDamaged)
                + ").");
      }
    }
    return Optional.empty();
  }

  /** Casualties after {@link #applyInPickedOrder}: units destroyed, and hits on survivors. */
  public record Applied(List<Unit> killed, List<Unit> damaged) {}

  /**
   * Applies the hits the sticky way to the units the owner picked: already-damaged units first,
   * then the units the owner chose to destroy, then those the owner chose to damage (most hits
   * first), then any others in order of loss; each unit takes hits until it is destroyed before the
   * next is hit, so at most one unit is left damaged. The owner's choice of units is kept as far as
   * the rule allows, and an unlawful spread (several units each damaged once) never needs to be
   * refused.
   *
   * @param orderOfLoss all units that could be hit, in order of loss
   * @param hits the number of hits to apply
   */
  public static Applied applyInPickedOrder(
      final List<Unit> orderOfLoss,
      final Collection<Unit> pickedKilled,
      final List<Unit> pickedDamaged,
      final int hits) {
    final IntegerMap<Unit> damageCount = new IntegerMap<>();
    pickedDamaged.forEach(u -> damageCount.add(u, 1));
    final List<Unit> order = new ArrayList<>();
    orderOfLoss.stream()
        .filter(u -> u.getHits() > 0)
        .sorted(Comparator.comparingInt(StickyHits::hitPointsLeft))
        .forEach(order::add);
    pickedKilled.stream().filter(u -> !order.contains(u)).forEach(order::add);
    pickedDamaged.stream()
        .distinct()
        .sorted(Comparator.comparingInt(u -> -damageCount.getInt(u)))
        .filter(u -> !order.contains(u))
        .forEach(order::add);
    orderOfLoss.stream().filter(u -> !order.contains(u)).forEach(order::add);
    final List<Unit> killed = new ArrayList<>();
    final List<Unit> damaged = new ArrayList<>();
    int hitsLeft = hits;
    for (final Unit unit : order) {
      if (hitsLeft <= 0) {
        break;
      }
      final int left = hitPointsLeft(unit);
      if (hitsLeft >= left) {
        killed.add(unit);
        hitsLeft -= left;
      } else {
        for (int i = 0; i < hitsLeft; i++) {
          damaged.add(unit);
        }
        hitsLeft = 0;
      }
    }
    return new Applied(killed, damaged);
  }

  /**
   * Flames and Steel (request 12 section 3): the order of loss with the units that alone can hit
   * some enemy unit type still in the battle moved to the end, so the default never takes them
   * before the rest (with enemy submarines present, destroyers go last). Otherwise the order is
   * kept. A unit "can hit" a type when its own targeting allows it (a destroyer's help to the rest
   * of the fleet doesn't count); Undersea War submarines can hit submarines.
   */
  public static List<Unit> keepLastHitters(
      final List<Unit> orderOfLoss, final Collection<Unit> enemies) {
    final List<games.strategy.engine.data.UnitType> enemyTypes =
        enemies.stream()
            .filter(Matches.unitIsNotInfrastructure())
            .map(Unit::getType)
            .distinct()
            .toList();
    final Set<Unit> keep = new HashSet<>();
    for (final games.strategy.engine.data.UnitType enemy : enemyTypes) {
      final List<Unit> hitters = orderOfLoss.stream().filter(u -> canHit(u, enemy)).toList();
      if (!hitters.isEmpty() && hitters.size() < orderOfLoss.size()) {
        keep.addAll(hitters);
      }
    }
    if (keep.isEmpty()) {
      return orderOfLoss;
    }
    final List<Unit> result = new ArrayList<>();
    orderOfLoss.stream().filter(u -> !keep.contains(u)).forEach(result::add);
    orderOfLoss.stream().filter(keep::contains).forEach(result::add);
    return result;
  }

  private static boolean canHit(final Unit unit, final games.strategy.engine.data.UnitType enemy) {
    if (games.strategy.triplea.delegate.battle.UnderseaWar.mayHitSubs(unit)
        && games.strategy.triplea.delegate.battle.UnderseaWar.isSub(enemy)) {
      return true;
    }
    return !unit.getUnitAttachment().getCanNotTarget().contains(enemy)
        && !enemy.getUnitAttachment().getCanNotBeTargetedBy().contains(unit.getType());
  }

  /**
   * Default casualties under sticky hits: already-damaged units first (the most damaged first),
   * then the rest in the given order of loss, each unit taking hits until destroyed before the next
   * is hit.
   */
  public static CasualtyList getDefaultCasualties(final List<Unit> orderOfLoss, final int hits) {
    return toCasualtyList(allocate(damagedFirst(orderOfLoss), hits));
  }

  /** What one unit gets from a set of hits taken in order (request 23). */
  public record Allocation(Unit unit, int hitsTaken, boolean killed) {
    /** Hit points the unit has left after these hits. */
    public int hitPointsLeftAfter() {
      return hitPointsLeft(unit) - hitsTaken;
    }
  }

  /**
   * The hits taken down the order: each unit takes hits until it is destroyed, then the next one
   * starts, so only the last unit hit can be left damaged. Units the hits don't reach are left out.
   */
  public static List<Allocation> allocate(final List<Unit> order, final int hits) {
    final List<Allocation> result = new ArrayList<>();
    int hitsLeft = hits;
    for (final Unit unit : order) {
      if (hitsLeft <= 0) {
        break;
      }
      final int left = hitPointsLeft(unit);
      final int taken = Math.min(hitsLeft, left);
      result.add(new Allocation(unit, taken, taken == left));
      hitsLeft -= taken;
    }
    return result;
  }

  /**
   * The order with the units damaged earlier (they must take hits first) moved to the front, the
   * most damaged first; the other units keep their order.
   */
  public static List<Unit> damagedFirst(final List<Unit> order) {
    final List<Unit> result = new ArrayList<>(lockedFirst(order));
    order.stream().filter(u -> u.getHits() == 0).forEach(result::add);
    return result;
  }

  /** The units damaged earlier, in the order they must take hits: the most damaged first. */
  public static List<Unit> lockedFirst(final Collection<Unit> units) {
    return units.stream()
        .filter(u -> u.getHits() > 0)
        .sorted(Comparator.comparingInt(StickyHits::hitPointsLeft))
        .toList();
  }

  /**
   * Whether the owner's choice is an order of hits (some unit can take a hit without being
   * destroyed) rather than a plain pick of units.
   */
  public static boolean choiceIsAnOrder(final Collection<Unit> units) {
    return units.stream().anyMatch(u -> hitPointsLeft(u) > 1);
  }

  /**
   * Casualties as the engine lists them: a destroyed unit is listed once as killed and once as
   * damaged for each other hit point it had left; a surviving unit once as damaged per hit.
   */
  public static CasualtyList toCasualtyList(final List<Allocation> allocations) {
    final CasualtyList casualties = new CasualtyList();
    for (final Allocation allocation : allocations) {
      final int damagedEntries =
          allocation.killed() ? allocation.hitsTaken() - 1 : allocation.hitsTaken();
      for (int i = 0; i < damagedEntries; i++) {
        casualties.addToDamaged(allocation.unit());
      }
      if (allocation.killed()) {
        casualties.addToKilled(allocation.unit());
      }
    }
    return casualties;
  }

  private static DamageType typeOf(final Unit unit) {
    if (Matches.unitIsAir().test(unit)) {
      return DamageType.AIR;
    }
    return Matches.unitIsSea().test(unit) ? DamageType.SEA : DamageType.LAND;
  }

  private static String describe(final List<Unit> units) {
    final List<String> names = new ArrayList<>();
    for (final Unit unit : units) {
      names.add(UnitNames.of(unit.getType()));
    }
    Collections.sort(names);
    return String.join(", ", names);
  }
}
