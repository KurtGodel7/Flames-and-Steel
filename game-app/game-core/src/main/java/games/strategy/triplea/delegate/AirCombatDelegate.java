package games.strategy.triplea.delegate;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.message.IRemote;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment.ZoneType;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.air.AirCombat.Hit;
import games.strategy.triplea.delegate.air.AirCombat.Roll;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.delegate.remote.IAirCombatDelegate;
import games.strategy.triplea.formatter.MyFormatter;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel air combat, fought once at the start of each round after the air allocation.
 * Three kinds of step use this delegate:
 *
 * <ul>
 *   <li>"airDogfights" (no player): two rounds of dogfight in every zone holding enemies; every
 *       aircraft fires at its dogfight value.
 *   <li>"nationAirTargets": the nation sends its surviving bombers against targets in their zones.
 *   <li>"airStrikes" (no player): AA at land targets, then tactical strikes on land and sea, then
 *       strategic bombing, then ships hit from the air fire back.
 * </ul>
 *
 * <p>All fire uses low luck, and all hits follow the sticky hit rule.
 */
public class AirCombatDelegate extends BaseTripleADelegate implements IAirCombatDelegate {
  private static final int DOGFIGHT_ROUNDS = 2;

  private Map<GamePlayer, List<TargetAssignment>> pending = new LinkedHashMap<>();

  @Override
  public void start() {
    super.start();
    final String step = stepName();
    if (step.startsWith("airDogfight")) {
      fightDogfights();
    } else if (step.startsWith("airStrikes")) {
      resolveStrikes();
    }
  }

  @Override
  public boolean delegateCurrentlyRequiresUserInput() {
    if (player == null || !stepName().endsWith("AirTargets")) {
      return false;
    }
    return AirZoneAttachment.getAirZones(getData().getMap()).stream()
        .anyMatch(
            zone ->
                !AirCombat.getBombers(player, zone).isEmpty()
                    && !AirCombat.getTargets(player, zone).isEmpty());
  }

  @Override
  public @Nullable String assignTargets(final List<TargetAssignment> assignments) {
    if (player == null || !stepName().endsWith("AirTargets")) {
      return "This is not a bomber target step.";
    }
    final String error = validate(player, assignments);
    if (error != null) {
      return error;
    }
    pending.put(player, new ArrayList<>(assignments));
    return null;
  }

  /** Why these targets are not allowed, or null. */
  static @Nullable String validate(
      final GamePlayer player, final List<TargetAssignment> assignments) {
    final IntegerMap<String> used = new IntegerMap<>();
    for (final TargetAssignment a : assignments) {
      if (a.count() <= 0) {
        return "Counts must be positive.";
      }
      if (!AirZoneAttachment.isAirZone(a.zone())) {
        return a.zone().getName() + " is not an air zone.";
      }
      if (!AirCombat.getTargets(player, a.zone()).contains(a.target())) {
        return a.target().describe() + " cannot be attacked from " + a.zone().getName() + ".";
      }
      if (!AirCombat.mayAttack(a.unitType(), a.target().kind())) {
        return a.unitType().getName() + " cannot attack " + a.target().describe() + ".";
      }
      final String key = a.zone().getName() + "|" + a.unitType().getName();
      used.add(key, a.count());
      final long available =
          AirCombat.getBombers(player, a.zone()).stream()
              .filter(u -> u.getType().equals(a.unitType()))
              .count();
      if (used.getInt(key) > available) {
        return "Only "
            + available
            + " "
            + a.unitType().getName()
            + " in "
            + a.zone().getName()
            + ".";
      }
    }
    return null;
  }

  private void fightDogfights() {
    for (final Territory zone : AirZoneAttachment.getAirZones(getData().getMap())) {
      if (AirZoneAttachment.get(zone).orElseThrow().getZoneType() == ZoneType.RESERVE) {
        continue;
      }
      for (int round = 1; round <= DOGFIGHT_ROUNDS; round++) {
        final List<Unit> aircraft = zone.getUnitCollection().getMatches(Matches.unitIsAir());
        if (aircraft.isEmpty()) {
          break;
        }
        final GamePlayer first = aircraft.get(0).getOwner();
        final List<Unit> sideA = new ArrayList<>();
        final List<Unit> sideB = new ArrayList<>();
        for (final Unit unit : aircraft) {
          if (unit.getOwner().equals(first)
              || getData().getRelationshipTracker().isAllied(first, unit.getOwner())) {
            sideA.add(unit);
          } else if (AirCombat.atWar(first, unit.getOwner())) {
            sideB.add(unit);
          }
        }
        if (sideB.isEmpty()) {
          break;
        }
        if (round == 1) {
          bridge.getHistoryWriter().startEvent("Dogfight in " + zone.getName());
        }
        final String text = "Dogfight in " + zone.getName() + ", round " + round;
        final Roll rollA = fire(sideA, AirCombat::dogfightPower, text);
        final Roll rollB = fire(sideB, AirCombat::dogfightPower, text);
        // both sides fired before any losses are taken
        AirCombat.takeHits(bridge, zone, sideB, sideA, rollA, text);
        AirCombat.takeHits(bridge, zone, sideA, sideB, rollB, text);
      }
    }
  }

  private Roll fire(
      final List<Unit> units,
      final java.util.function.ToIntFunction<Unit> power,
      final String text) {
    final int total = units.stream().mapToInt(power).sum();
    final GamePlayer roller = AirCombat.chooser(units);
    final Roll roll = AirCombat.lowLuck(bridge, roller, total, roller.getName() + ": " + text);
    bridge
        .getHistoryWriter()
        .addChildToEvent(
            roller.getName()
                + (units.stream().map(Unit::getOwner).distinct().count() > 1 ? " and allies" : "")
                + " fire with total value "
                + total
                + ": "
                + roll.hits()
                + MyFormatter.pluralize(" hit", roll.hits()));
    return roll;
  }

  /** A strike: aircraft from one zone against one target. */
  private record Strike(Territory zone, Target target, List<Unit> aircraft) {
    List<Unit> survivors() {
      return aircraft.stream().filter(u -> zone.getUnitCollection().contains(u)).toList();
    }
  }

  private void resolveStrikes() {
    final List<Strike> strikes = buildStrikes();
    pending.clear();
    if (strikes.isEmpty()) {
      return;
    }
    bridge.getHistoryWriter().startEvent("Air strikes");
    // 1. AA guns at land targets (tactical and strategic) fire first
    for (final Strike strike : strikes) {
      if (strike.target().kind() == TargetKind.LAND
          || strike.target().kind() == TargetKind.STRATEGIC) {
        fireAa(strike);
      }
    }
    // 2. tactical strikes
    final Map<Strike, List<Unit>> shipsHit = new LinkedHashMap<>();
    for (final Strike strike : strikes) {
      final TargetKind kind = strike.target().kind();
      if (kind == TargetKind.STRATEGIC || strike.survivors().isEmpty()) {
        continue;
      }
      final GamePlayer attacker = AirCombat.chooser(strike.survivors());
      final Territory t = strike.target().territory();
      final List<Unit> targets =
          kind == TargetKind.LAND
              ? AirCombat.getLandTargetUnits(attacker, t)
              : AirCombat.getShipTargetUnits(attacker, t, kind == TargetKind.SUBS);
      final String text = "Air strike on " + strike.target().describe();
      final Roll roll = fire(strike.survivors(), u -> AirCombat.strikePower(u, kind), text);
      final Hit hit = AirCombat.takeHits(bridge, t, targets, strike.survivors(), roll, text);
      if (kind == TargetKind.SURFACE && !hit.all().isEmpty()) {
        shipsHit.put(strike, hit.all());
      }
    }
    // 3. strategic bombing
    final IntegerMap<Territory> bombed = new IntegerMap<>();
    for (final Strike strike : strikes) {
      if (strike.target().kind() == TargetKind.STRATEGIC && !strike.survivors().isEmpty()) {
        strategicBombing(strike, bombed);
      }
    }
    // 4. ships destroyed or damaged by air attack fire back at their attackers
    for (final Map.Entry<Strike, List<Unit>> entry : shipsHit.entrySet()) {
      final Strike strike = entry.getKey();
      if (strike.survivors().isEmpty()) {
        continue;
      }
      final String text = "Ships in " + strike.target().territory().getName() + " fire back";
      final Roll roll = fire(entry.getValue(), AirCombat::shipFireBackPower, text);
      AirCombat.takeHits(bridge, strike.zone(), strike.survivors(), entry.getValue(), roll, text);
    }
  }

  private void fireAa(final Strike strike) {
    final List<Unit> attackers = strike.survivors();
    if (attackers.isEmpty()) {
      return;
    }
    final GamePlayer attacker = AirCombat.chooser(attackers);
    final List<Unit> aaGuns =
        strike
            .target()
            .territory()
            .getUnitCollection()
            .getMatches(
                Matches.unitIsAaForAnything().and(u -> AirCombat.atWar(attacker, u.getOwner())));
    if (aaGuns.isEmpty()) {
      return;
    }
    final String text = "AA fire in " + strike.target().territory().getName();
    final Roll roll = fire(aaGuns, AirCombat::aaPower, text);
    AirCombat.takeHits(bridge, strike.zone(), attackers, aaGuns, roll, text);
  }

  private void strategicBombing(final Strike strike, final IntegerMap<Territory> bombed) {
    final Territory t = strike.target().territory();
    final GamePlayer victim = t.getOwner();
    final int damage =
        strike.survivors().stream()
            .mapToInt(
                u ->
                    AirUnitAttachment.get(u.getType())
                        .map(AirUnitAttachment::getBombingDamage)
                        .orElse(0))
            .sum();
    final Resource pus = getData().getResourceList().getResourceOrThrow(Constants.PUS);
    final int capLeft = Math.max(0, AirCombat.bombingCap(t) - bombed.getInt(t));
    final int loss = Math.min(Math.min(damage, capLeft), victim.getResources().getQuantity(pus));
    bombed.add(t, loss);
    bridge
        .getHistoryWriter()
        .addChildToEvent(
            MyFormatter.unitsToText(strike.survivors())
                + " bomb "
                + t.getName()
                + " for "
                + damage
                + " damage: "
                + victim.getName()
                + " loses "
                + loss
                + MyFormatter.pluralize(" PU", loss)
                + (damage > capLeft ? " (limit " + AirCombat.bombingCap(t) + " per round)" : ""));
    if (loss > 0) {
      bridge.addChange(ChangeFactory.changeResourcesChange(victim, pus, -loss));
    }
  }

  private List<Strike> buildStrikes() {
    final Map<String, Strike> strikes = new LinkedHashMap<>();
    for (final Map.Entry<GamePlayer, List<TargetAssignment>> entry : pending.entrySet()) {
      final Set<Unit> used = new HashSet<>();
      for (final TargetAssignment a : entry.getValue()) {
        final List<Unit> units =
            AirCombat.getBombers(entry.getKey(), a.zone()).stream()
                .filter(u -> u.getType().equals(a.unitType()) && !used.contains(u))
                .limit(a.count())
                .toList();
        used.addAll(units);
        final String key = a.zone().getName() + "|" + a.target();
        strikes
            .computeIfAbsent(key, k -> new Strike(a.zone(), a.target(), new ArrayList<>()))
            .aircraft()
            .addAll(units);
      }
    }
    return new ArrayList<>(strikes.values());
  }

  private String stepName() {
    return getData().getSequence().getStep().getName();
  }

  @Override
  public Serializable saveState() {
    final AirCombatState state = new AirCombatState();
    state.superState = super.saveState();
    state.pending = new LinkedHashMap<>(pending);
    return state;
  }

  @Override
  public void loadState(final Serializable state) {
    final AirCombatState s = (AirCombatState) state;
    super.loadState(s.superState);
    pending = new LinkedHashMap<>(s.pending);
  }

  @Override
  public Class<? extends IRemote> getRemoteType() {
    return IAirCombatDelegate.class;
  }

  private static final class AirCombatState implements Serializable {
    @Serial private static final long serialVersionUID = -4093180047782251877L;

    private Serializable superState;
    private Map<GamePlayer, List<TargetAssignment>> pending;
  }
}
