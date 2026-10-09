package games.strategy.triplea.delegate.battle.casualty;

import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.data.CasualtyList;
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

  private static int hitPointsLeft(final Unit unit) {
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

  /**
   * Default casualties under sticky hits: already-damaged units first, then the rest in the given
   * order of loss, each unit taking hits until destroyed before the next is hit.
   */
  public static CasualtyList getDefaultCasualties(final List<Unit> orderOfLoss, final int hits) {
    final List<Unit> order = new ArrayList<>();
    orderOfLoss.stream().filter(u -> u.getHits() > 0).forEach(order::add);
    orderOfLoss.stream().filter(u -> u.getHits() == 0).forEach(order::add);
    final CasualtyList casualties = new CasualtyList();
    int hitsLeft = hits;
    for (final Unit unit : order) {
      if (hitsLeft <= 0) {
        break;
      }
      final int hitPointsLeft = Unit.maxHitPoints(unit) - unit.getHits();
      final int taken = Math.min(hitsLeft, hitPointsLeft);
      // a unit listed as damaged and then killed takes all its remaining hit points
      for (int i = 0; i < Math.min(taken, hitPointsLeft - 1); i++) {
        casualties.addToDamaged(unit);
      }
      if (taken == hitPointsLeft) {
        casualties.addToKilled(unit);
      }
      hitsLeft -= taken;
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
      names.add(unit.getType().getName());
    }
    Collections.sort(names);
    return String.join(", ", names);
  }
}
