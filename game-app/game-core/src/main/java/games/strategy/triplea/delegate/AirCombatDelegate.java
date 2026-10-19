package games.strategy.triplea.delegate;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.message.IRemote;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment.ZoneType;
import games.strategy.triplea.attachments.ApcAttachment;
import games.strategy.triplea.delegate.air.AirAllocation;
import games.strategy.triplea.delegate.air.AirBattleView;
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
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class AirCombatDelegate extends BaseTripleADelegate implements IAirCombatDelegate {
  private static final int DOGFIGHT_ROUNDS = 2;

  private Map<GamePlayer, List<TargetAssignment>> pending = new LinkedHashMap<>();
  // the window of the air battle being fought, closed if that battle fails
  private transient AirBattleView currentView;
  // this strike step's bombing raids, for the summary at its end
  private final transient List<Raid> raids = new ArrayList<>();

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
    if (player == null || !stepName().endsWith("AirTargets") || Peacetime.isAtPeace(player)) {
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
    if (Peacetime.isAtPeace(player)) {
      return Peacetime.noAirMessage(player);
    }
    final String error = validate(player, assignments);
    if (error != null) {
      return error;
    }
    pending.put(player, new ArrayList<>(assignments));
    return null;
  }

  @Override
  public List<TargetAssignment> getPendingAssignments() {
    if (player == null || !stepName().endsWith("AirTargets")) {
      return List.of();
    }
    return List.copyOf(pending.getOrDefault(player, List.of()));
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
      if (!AirCombat.mayAttack(player, a.zone(), a.unitType(), a.target())) {
        return a.target().describe()
            + " is out of range for "
            + a.unitType().getName()
            + " from "
            + a.zone().getName()
            + ".";
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
      final List<Unit> aircraft = zone.getUnitCollection().getMatches(Matches.unitIsAir());
      if (aircraft.isEmpty()) {
        continue;
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
      if (!sideB.isEmpty()) {
        safely("Dogfight in " + zone.getName(), () -> fightDogfight(zone, sideA, sideB));
      }
    }
  }

  private void fightDogfight(final Territory zone, final List<Unit> sideA, final List<Unit> sideB) {
    final GamePlayer playerA = AirCombat.chooser(sideA);
    final GamePlayer playerB = AirCombat.chooser(sideB);
    final List<String> steps = new ArrayList<>();
    for (int round = 1; round <= DOGFIGHT_ROUNDS; round++) {
      steps.add(fireStep(playerA, round));
      steps.add(fireStep(playerB, round));
      steps.add(casualtyStep(playerB, round));
      steps.add(casualtyStep(playerA, round));
    }
    final AirBattleView view =
        AirBattleView.show(
            bridge,
            zone,
            "Dogfight in " + AirAllocation.zoneName(zone),
            sideA,
            sideB,
            playerA,
            playerB,
            steps);
    currentView = view;
    bridge.getHistoryWriter().startEvent("Dogfight in " + zone.getName());
    // request 18: who fought, readable later in the history
    final List<Unit> everyone = new ArrayList<>(sideA);
    everyone.addAll(sideB);
    bridge
        .getHistoryWriter()
        .addChildToEvent(
            "Aircraft at the start: "
                + AirCombat.sideText(sideA)
                + " against "
                + AirCombat.sideText(sideB),
            everyone);
    for (int round = 1; round <= DOGFIGHT_ROUNDS; round++) {
      final List<Unit> aliveA = alive(zone, sideA);
      final List<Unit> aliveB = alive(zone, sideB);
      if (aliveA.isEmpty() || aliveB.isEmpty()) {
        break;
      }
      final String text = "Dogfight in " + zone.getName() + ", round " + round;
      final Roll rollA = fire(aliveA, AirCombat::dogfightPower, text);
      final DiceRoll diceA =
          view.showDice(rollA, AirCombat.chooser(aliveA), fireStep(playerA, round));
      final Roll rollB = fire(aliveB, AirCombat::dogfightPower, text);
      final DiceRoll diceB =
          view.showDice(rollB, AirCombat.chooser(aliveB), fireStep(playerB, round));
      // both sides fired before any losses are taken
      AirCombat.takeHits(
          bridge, view, casualtyStep(playerB, round), zone, aliveB, aliveA, rollA, diceA, text);
      AirCombat.takeHits(
          bridge, view, casualtyStep(playerA, round), zone, aliveA, aliveB, rollB, diceB, text);
    }
    final List<Unit> lost =
        everyone.stream().filter(u -> !zone.getUnitCollection().contains(u)).toList();
    final List<Unit> left =
        everyone.stream().filter(u -> zone.getUnitCollection().contains(u)).toList();
    bridge
        .getHistoryWriter()
        .addChildToEvent(
            "Result: lost " + AirCombat.sideText(lost) + "; left " + AirCombat.sideText(left));
    view.end(
        playerA.getName()
            + " lost "
            + (sideA.size() - alive(zone, sideA).size())
            + ", "
            + playerB.getName()
            + " lost "
            + (sideB.size() - alive(zone, sideB).size()));
  }

  private static String fireStep(final GamePlayer player, final int round) {
    return player.getName() + " fire, round " + round;
  }

  private static String casualtyStep(final GamePlayer player, final int round) {
    return player.getName() + " casualties, round " + round;
  }

  private static List<Unit> alive(final Territory where, final List<Unit> units) {
    return units.stream().filter(u -> where.getUnitCollection().contains(u)).toList();
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

  /**
   * Each strike is one battle: AA at land targets first, then the attack, then (sea targets) the
   * ships hit fire back. Tactical strikes come before strategic bombing. AA guns cannot be hit by
   * strikes and each aircraft is in one strike only, so taking the strikes one at a time gives the
   * same result as AA everywhere first, then all tactical strikes, then all bombing.
   */
  private void resolveStrikes() {
    final List<Strike> strikes = buildStrikes();
    pending.clear();
    raids.clear();
    clearBombedThisRound();
    if (strikes.isEmpty()) {
      return;
    }
    bridge.getHistoryWriter().startEvent("Air strikes");
    final IntegerMap<Territory> bombed = new IntegerMap<>();
    for (final Strike strike : strikes) {
      if (strike.target().kind() != TargetKind.STRATEGIC) {
        safely(strikeName(strike), () -> fightStrike(strike, bombed));
      }
    }
    for (final Strike strike : strikes) {
      if (strike.target().kind() == TargetKind.STRATEGIC) {
        safely(strikeName(strike), () -> fightStrike(strike, bombed));
      }
    }
    reportRaids();
  }

  private static String strikeName(final Strike strike) {
    return strike.target().kind() == TargetKind.STRATEGIC
        ? "Strategic bombing of " + strike.target().territory().getName()
        : "Air strike on " + strike.target().describe();
  }

  /**
   * Runs one air battle. If it fails, the error is logged, the history says the battle was skipped,
   * its window is closed and the air phase goes on, so one bad battle can't end the game.
   */
  private void safely(final String battle, final Runnable fight) {
    try {
      fight.run();
    } catch (final RuntimeException e) {
      log.error("Air phase: " + battle + " failed and was skipped", e);
      bridge.getHistoryWriter().startEvent(battle + " skipped after an error (details in the log)");
      if (currentView != null) {
        try {
          currentView.abort(battle + " skipped after an error");
        } catch (final RuntimeException closeFailed) {
          log.warn("Could not close the air battle window", closeFailed);
        }
      }
    } finally {
      currentView = null;
    }
  }

  private void fightStrike(final Strike strike, final IntegerMap<Territory> bombed) {
    if (strike.survivors().isEmpty()) {
      return;
    }
    final TargetKind kind = strike.target().kind();
    final Territory t = strike.target().territory();
    final GamePlayer attacker = AirCombat.chooser(strike.survivors());
    final List<Unit> aaGuns =
        kind == TargetKind.LAND || kind == TargetKind.STRATEGIC
            ? t.getUnitCollection()
                .getMatches(
                    Matches.unitIsAaForAnything().and(u -> AirCombat.atWar(attacker, u.getOwner())))
            : List.of();
    final List<Unit> targets =
        switch (kind) {
          case LAND -> AirCombat.getLandTargetUnits(attacker, t);
          case SURFACE -> AirCombat.getShipTargetUnits(attacker, t, false);
          case SUBS -> AirCombat.getShipTargetUnits(attacker, t, true);
          case STRATEGIC ->
              t.getUnitCollection()
                  .getMatches(
                      u ->
                          AirCombat.atWar(attacker, u.getOwner())
                              && (Matches.unitCanProduceUnits().test(u)
                                  || ApcAttachment.isApc(u.getType())));
        };
    final List<Unit> defenders = new ArrayList<>(targets);
    aaGuns.stream().filter(u -> !defenders.contains(u)).forEach(defenders::add);
    final GamePlayer defender = defenders.isEmpty() ? t.getOwner() : AirCombat.chooser(defenders);
    final String aaFire = "AA fire";
    final String aaCasualties = attacker.getName() + " casualties from AA";
    final String strikeStep =
        kind == TargetKind.STRATEGIC ? "Bombing" : attacker.getName() + " strike";
    final String strikeCasualties = defender.getName() + " casualties";
    final String fireBack = "Ships fire back";
    final String fireBackCasualties = attacker.getName() + " casualties from ships";
    final List<String> steps = new ArrayList<>();
    if (!aaGuns.isEmpty()) {
      steps.add(aaFire);
      steps.add(aaCasualties);
    }
    steps.add(strikeStep);
    if (kind != TargetKind.STRATEGIC) {
      steps.add(strikeCasualties);
    }
    if (kind == TargetKind.SURFACE) {
      steps.add(fireBack);
      steps.add(fireBackCasualties);
    }
    final String title =
        kind == TargetKind.STRATEGIC
            ? "Strategic bombing of " + t.getName()
            : "Air strike on " + strike.target().describe();
    final AirBattleView view =
        AirBattleView.show(
            bridge, t, title, strike.survivors(), defenders, attacker, defender, steps);
    currentView = view;
    // request 18: who took part, readable later in the history
    final List<Unit> involved = new ArrayList<>(strike.survivors());
    involved.addAll(defenders);
    bridge
        .getHistoryWriter()
        .addChildToEvent(
            title
                + ": "
                + AirCombat.sideText(strike.survivors())
                + " against "
                + (defenders.isEmpty() ? "no units" : AirCombat.sideText(defenders)),
            involved);

    // 1. AA guns fire first
    if (!aaGuns.isEmpty()) {
      final String text = "AA fire in " + t.getName();
      final Roll roll = fire(aaGuns, AirCombat::aaPower, text);
      final DiceRoll dice = view.showDice(roll, AirCombat.chooser(aaGuns), aaFire, "AA guns");
      AirCombat.takeHits(
          bridge, view, aaCasualties, strike.zone(), strike.survivors(), aaGuns, roll, dice, text);
    }
    if (strike.survivors().isEmpty()) {
      strikeResult(title, strike, "all attacking aircraft lost to AA");
      view.end("All attacking aircraft lost");
      return;
    }
    if (kind == TargetKind.STRATEGIC) {
      final String result = strategicBombing(strike, bombed, targets, view, strikeStep);
      strikeResult(title, strike, result);
      view.end(result);
      return;
    }
    // 2. the strike
    final String text = "Air strike on " + strike.target().describe();
    final Roll roll = fire(strike.survivors(), u -> AirCombat.strikePower(u, kind), text);
    final DiceRoll dice = view.showDice(roll, attacker, strikeStep);
    final Hit hit =
        AirCombat.takeHits(
            bridge, view, strikeCasualties, t, targets, strike.survivors(), roll, dice, text);
    if (kind == TargetKind.SURFACE) {
      // transports are never casualties, but are lost once nothing protects them
      final List<Unit> transports = AirCombat.getUndefendedTransports(attacker, t);
      if (!transports.isEmpty()) {
        bridge
            .getHistoryWriter()
            .addChildToEvent(
                MyFormatter.unitsToText(transports) + " undefended in " + t.getName() + ", lost",
                transports);
        bridge.addChange(ChangeFactory.removeUnits(t, transports));
      }
      // 3. ships destroyed or damaged by the strike fire back
      if (!hit.all().isEmpty() && !strike.survivors().isEmpty()) {
        final String backText = "Ships in " + t.getName() + " fire back";
        final Roll back = fire(hit.all(), AirCombat::shipFireBackPower, backText);
        final DiceRoll backDice =
            view.showDice(back, AirCombat.chooser(hit.all()), fireBack, "Ships");
        AirCombat.takeHits(
            bridge,
            view,
            fireBackCasualties,
            strike.zone(),
            strike.survivors(),
            hit.all(),
            back,
            backDice,
            backText);
      }
    }
    final int lost = strike.aircraft().size() - strike.survivors().size();
    final String result =
        hit.killed().size()
            + " destroyed, "
            + hit.damaged().size()
            + " damaged"
            + (lost > 0 ? "; " + lost + " aircraft lost" : "");
    strikeResult(title, strike, result);
    view.end(result);
  }

  /** The strike's last history line: its result, the aircraft lost and those left. */
  private void strikeResult(final String title, final Strike strike, final String result) {
    final List<Unit> lost =
        strike.aircraft().stream().filter(u -> !strike.survivors().contains(u)).toList();
    bridge
        .getHistoryWriter()
        .addChildToEvent(
            title
                + " result: "
                + result
                + "; aircraft lost "
                + AirCombat.sideText(lost)
                + "; left "
                + AirCombat.sideText(strike.survivors()));
  }

  /** One strategic bombing raid's result, for the air phase summary. */
  record Raid(GamePlayer attacker, Territory territory, GamePlayer victim, int loss) {
    String describe() {
      return AirCombat.adjective(attacker)
          + " bombed "
          + territory.getName()
          + ": "
          + victim.getName()
          + " "
          + MINUS
          + loss
          + " PUs";
    }
  }

  private static final String MINUS = "−";

  /**
   * Bombs the target: PUs off its owner, at most twice the territory's value per round over all
   * raids on it. Shows each bomber's damage and the total in the battle window's bombing step and
   * returns the result for the window's last line.
   */
  private String strategicBombing(
      final Strike strike,
      final IntegerMap<Territory> bombed,
      final List<Unit> targets,
      final AirBattleView view,
      final String step) {
    final Territory t = strike.target().territory();
    final GamePlayer victim = t.getOwner();
    final GamePlayer attacker = AirCombat.chooser(strike.survivors());
    final String targetName =
        t.getName()
            + (targets.isEmpty()
                ? ""
                : " "
                    + String.join(
                        " and ",
                        targets.stream()
                            .map(u -> AirCombat.unitName(u.getType()))
                            .distinct()
                            .toList()));
    final List<String> lines = new ArrayList<>();
    int damage = 0;
    for (final Unit bomber : strike.survivors()) {
      final int d = AirCombat.bombingDamage(bomber);
      damage += d;
      lines.add(
          AirCombat.adjective(bomber.getOwner())
              + " "
              + AirCombat.unitName(bomber.getType())
              + " bombs "
              + targetName
              + ": "
              + d
              + " PUs");
    }
    final Resource pus = getData().getResourceList().getResourceOrThrow(Constants.PUS);
    final int cap = AirCombat.bombingCap(t);
    final int capLeft = Math.max(0, cap - bombed.getInt(t));
    final int before = victim.getResources().getQuantity(pus);
    final int loss = Math.min(Math.min(damage, capLeft), before);
    if (damage > capLeft) {
      lines.add(
          damage
              + " PUs of damage, capped at "
              + capLeft
              + " (2 × the territory's value"
              + (capLeft < cap ? "; " + (cap - capLeft) + " already bombed this round" : "")
              + ")");
    }
    if (loss < Math.min(damage, capLeft)) {
      lines.add(victim.getName() + " had only " + before + " PUs");
    }
    lines.add(
        victim.getName()
            + " lose "
            + loss
            + " PUs (raid cap "
            + cap
            + "): "
            + before
            + " → "
            + (before - loss)
            + " PUs");
    bombed.add(t, loss);
    final Raid raid = new Raid(attacker, t, victim, loss);
    raids.add(raid);
    bridge
        .getHistoryWriter()
        .addChildToEvent(
            raid.describe()
                + " ("
                + damage
                + " damage, raid cap "
                + cap
                + (damage > capLeft ? ", capped at " + capLeft : "")
                + "; "
                + before
                + " → "
                + (before - loss)
                + " PUs)");
    if (loss > 0) {
      bridge.addChange(ChangeFactory.changeResourcesChange(victim, pus, -loss));
      final var tech = victim.getTechAttachment();
      bridge.addChange(
          ChangeFactory.attachmentPropertyChange(
              tech, tech.getBombedThisRound() + loss, "bombedThisRound"));
    }
    view.showText(step, lines, "Bombing damage: " + victim.getName() + " " + MINUS + loss + " PUs");
    return victim.getName() + " lose " + loss + " PUs";
  }

  /**
   * Clears last round's bombing figures, so the PUs display only counts this round's raids. Called
   * as the strike step starts, before any raid.
   */
  private void clearBombedThisRound() {
    for (final GamePlayer p : getData().getPlayerList().getPlayers()) {
      final var tech = p.getTechAttachment();
      if (tech.getBombedThisRound() != 0) {
        bridge.addChange(ChangeFactory.attachmentPropertyChange(tech, 0, "bombedThisRound"));
      }
    }
  }

  /** At the end of the strikes: every raid with its damage, and the total per nation. */
  private void reportRaids() {
    if (raids.isEmpty()) {
      return;
    }
    final Map<GamePlayer, Integer> totals = new LinkedHashMap<>();
    raids.forEach(r -> totals.merge(r.victim(), r.loss(), Integer::sum));
    final List<String> lines = new ArrayList<>(raids.stream().map(Raid::describe).toList());
    totals.forEach(
        (victim, loss) ->
            lines.add(
                "Total: "
                    + victim.getName()
                    + " "
                    + MINUS
                    + loss
                    + " PUs from bombing this round"));
    bridge.getHistoryWriter().startEvent("Strategic bombing this round");
    lines.forEach(line -> bridge.getHistoryWriter().addChildToEvent(line));
    bridge
        .getDisplayChannelBroadcaster()
        .reportMessageToAll(
            "<html>" + String.join("<br>", lines) + "</html>",
            "Strategic bombing this round",
            false,
            false,
            false);
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
