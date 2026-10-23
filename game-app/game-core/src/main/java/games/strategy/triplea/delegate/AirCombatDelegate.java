package games.strategy.triplea.delegate;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
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
import games.strategy.triplea.delegate.air.AirCover;
import games.strategy.triplea.delegate.air.LongRangeRaid;
import games.strategy.triplea.delegate.remote.IAirCombatDelegate;
import games.strategy.triplea.formatter.MyFormatter;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel air combat, fought once at the start of each round after the air allocation.
 * Three kinds of step use this delegate:
 *
 * <ul>
 *   <li>"nationAirTargets": the nation assigns its bombers to targets before any fighting; the
 *       assignments are final for the round. Request 30: at most "FS Air Cap Bombers" (4) bombers
 *       per side over a land territory or factory (see {@link AirCover}).
 *   <li>"nationAirCover" (request 30): once every nation's bomber targets are revealed, the nation
 *       places its fighter-types over territories in their front, to escort or to defend, at most
 *       "FS Air Cap Fighters" (4) per side over a land territory (none over a factory under attack
 *       or a sea zone). Fighters placed nowhere take no part. Game XML without these steps gets the
 *       same choice asked at the start of "airStrikes".
 *   <li>"airStrikes" (no player): each territory, sea zone or bombed factory with aircraft fights its
 *       own air battle: the dogfight between the fighter-types there (game property "FS Dogfight
 *       Rounds" rounds), then interception (surviving fighters fire at the enemy's bombers there,
 *       which fire back), then every damaged bomber aborts. Then AA there fires at every bomber
 *       assigned to its target, damaged ones first (bombers it damages abort too); then tactical
 *       strikes and strategic bombing by bombers neither lost nor aborted; ships hit from the air
 *       fire back. Casualties are picked at random (sticky hits apply), no battle window opens, and
 *       one summary lists every battle. All aircraft heal at the end of the air phase.
 * </ul>
 *
 * <p>An "airDogfights" step (XML written for the old order, where it came before the targets)
 * does nothing: the dogfight is now part of the strike step, after the targets are chosen.
 *
 * <p>All fire uses low luck.
 */
@Slf4j
public class AirCombatDelegate extends BaseTripleADelegate implements IAirCombatDelegate {
  private static final String ABORTS = "Aborts";

  private Map<GamePlayer, List<TargetAssignment>> pending = new LinkedHashMap<>();
  // request 30: fighter cover chosen in the "nationAirCover" steps
  private Map<GamePlayer, List<TargetAssignment>> pendingCover = new LinkedHashMap<>();
  // the window of the air battle being fought, closed if that battle fails
  private transient AirBattleView currentView;
  // this strike step's bombing raids, for the summary at its end
  private final transient List<Raid> raids = new ArrayList<>();
  // request 27: bombers damaged during this air phase; they make no strike or bombing run
  private final transient Set<Unit> aborted = new HashSet<>();
  // request 30: one line per territory's battle, for the summary at the end of the air phase
  private final transient List<String> summary = new ArrayList<>();

  @Override
  public void start() {
    super.start();
    // request 27: "airDogfights" does nothing; the whole fight is in the strike step
    if (stepName().startsWith("airStrikes")) {
      if (!hasCoverSteps()) {
        askForCover();
      }
      resolveAirPhase();
      returnRaiders();
    }
  }

  private boolean isTargetStep() {
    return stepName().endsWith("AirTargets");
  }

  private boolean isCoverStep() {
    return GameStep.isAirCoverStepName(stepName());
  }

  /** Whether the game's sequence has request 30's fighter cover steps. */
  private boolean hasCoverSteps() {
    return getData().getSequence().getSteps().stream()
        .anyMatch(s -> GameStep.isAirCoverStepName(s.getName()));
  }

  @Override
  public boolean delegateCurrentlyRequiresUserInput() {
    if (player == null || Peacetime.isAtPeace(player)) {
      return false;
    }
    if (isCoverStep()) {
      return hasCoverChoice(player);
    }
    if (!isTargetStep()) {
      return false;
    }
    return AirZoneAttachment.getAirZones(getData().getMap()).stream()
        .anyMatch(
            zone ->
                !AirCombat.getBombers(player, zone).isEmpty()
                    && !AirCombat.getTargets(player, zone).isEmpty());
  }

  /** Whether the nation has fighter-types in a front with somewhere to cover. */
  private boolean hasCoverChoice(final GamePlayer p) {
    for (final Territory zone : AirZoneAttachment.getAirZones(getData().getMap())) {
      for (final Unit u : zone.getUnitCollection().getMatches(Matches.unitIsOwnedBy(p))) {
        if (AirCombat.isFighterType(u)
            && !AirCover.places(p, zone, u.getType(), pending).isEmpty()) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public @Nullable String assignTargets(final List<TargetAssignment> assignments) {
    if (player == null || (!isTargetStep() && !isCoverStep())) {
      return "This is not a bomber target or fighter cover step.";
    }
    if (Peacetime.isAtPeace(player)) {
      return Peacetime.noAirMessage(player);
    }
    if (isCoverStep()) {
      final String error = validateCover(player, assignments, others(pendingCover), pending);
      if (error != null) {
        return error;
      }
      pendingCover.put(player, new ArrayList<>(assignments));
      return null;
    }
    final String error = validate(player, assignments);
    if (error != null) {
      return error;
    }
    final Optional<String> cap = AirCover.capProblem(player, assignments, others(pending), pending);
    if (cap.isPresent()) {
      return cap.get();
    }
    pending.put(player, new ArrayList<>(assignments));
    return null;
  }

  /** Everyone's choices but the current nation's. */
  private Map<GamePlayer, List<TargetAssignment>> others(
      final Map<GamePlayer, List<TargetAssignment>> all) {
    final Map<GamePlayer, List<TargetAssignment>> result = new LinkedHashMap<>(all);
    result.remove(player);
    return result;
  }

  @Override
  public List<TargetAssignment> getPendingAssignments() {
    if (player == null) {
      return List.of();
    }
    if (isCoverStep()) {
      return List.copyOf(pendingCover.getOrDefault(player, List.of()));
    }
    if (!isTargetStep()) {
      return List.of();
    }
    return List.copyOf(pending.getOrDefault(player, List.of()));
  }

  @Override
  public Map<GamePlayer, List<TargetAssignment>> getRevealedBombers() {
    if (!isCoverStep()) {
      return Map.of();
    }
    return copy(pending);
  }

  @Override
  public Map<GamePlayer, List<TargetAssignment>> getAlliedAssignments() {
    if (player == null) {
      return Map.of();
    }
    final Map<GamePlayer, List<TargetAssignment>> source =
        isCoverStep() ? pendingCover : isTargetStep() ? pending : Map.of();
    final Map<GamePlayer, List<TargetAssignment>> result = new LinkedHashMap<>();
    source.forEach(
        (p, list) -> {
          if (!p.equals(player) && AirCover.sameSide(player, p)) {
            result.put(p, List.copyOf(list));
          }
        });
    return result;
  }

  private static Map<GamePlayer, List<TargetAssignment>> copy(
      final Map<GamePlayer, List<TargetAssignment>> map) {
    final Map<GamePlayer, List<TargetAssignment>> result = new LinkedHashMap<>();
    map.forEach((p, list) -> result.put(p, List.copyOf(list)));
    return result;
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
      if (AirCover.isCover(a)) {
        return "Fighter cover is chosen in its own step.";
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

  /**
   * Request 30: why this fighter cover is not allowed, or null.
   *
   * @param others the other nations' cover so far (allies share the caps)
   * @param bombers every nation's bomber targets this round
   */
  static @Nullable String validateCover(
      final GamePlayer player,
      final List<TargetAssignment> assignments,
      final Map<GamePlayer, List<TargetAssignment>> others,
      final Map<GamePlayer, List<TargetAssignment>> bombers) {
    final IntegerMap<String> used = new IntegerMap<>();
    for (final TargetAssignment a : assignments) {
      if (a.count() <= 0) {
        return "Counts must be positive.";
      }
      if (!AirZoneAttachment.isAirZone(a.zone())) {
        return a.zone().getName() + " is not an air zone.";
      }
      if (!AirCover.isCover(a)) {
        return "Only fighter cover is chosen in this step.";
      }
      if (!AirCover.places(player, a.zone(), a.unitType(), bombers)
          .contains(a.target().territory())) {
        return AirAllocation.typeName(a.unitType())
            + " in "
            + AirAllocation.zoneName(a.zone())
            + " cannot cover "
            + a.target().territory().getName()
            + " (out of range, or not a place it may go).";
      }
      final String key = a.zone().getName() + "|" + a.unitType().getName();
      used.add(key, a.count());
      final long available =
          a.zone().getUnitCollection().stream()
              .filter(u -> u.isOwnedBy(player) && u.getType().equals(a.unitType()))
              .filter(AirCombat::isFighterType)
              .count();
      if (used.getInt(key) > available) {
        return "Only "
            + available
            + " "
            + AirAllocation.typeName(a.unitType())
            + " in "
            + AirAllocation.zoneName(a.zone())
            + ".";
      }
    }
    return AirCover.capProblem(player, assignments, others, bombers).orElse(null);
  }

  /**
   * Request 30, for XML without "nationAirCover" steps: each nation in turn places its fighters,
   * asked here. A placement refused five times counts as none.
   */
  private void askForCover() {
    final List<GamePlayer> order = new ArrayList<>();
    for (final GameStep step : getData().getSequence().getSteps()) {
      if (step.getName().endsWith("AirTargets")
          && step.getPlayerId() != null
          && !order.contains(step.getPlayerId())) {
        order.add(step.getPlayerId());
      }
    }
    for (final GamePlayer p : order) {
      if (Peacetime.isAtPeace(p) || !hasCoverChoice(p) || !AirBattleView.isControlled(p)) {
        continue;
      }
      List<TargetAssignment> choice = List.of();
      String problem = "";
      for (int attempt = 0; attempt < 5; attempt++) {
        final Map<GamePlayer, List<TargetAssignment>> allied = new LinkedHashMap<>();
        pendingCover.forEach(
            (other, list) -> {
              if (AirCover.sameSide(p, other)) {
                allied.put(other, List.copyOf(list));
              }
            });
        choice =
            bridge.getRemotePlayer(p).selectAirCover(choice, copy(pending), allied, problem);
        final Map<GamePlayer, List<TargetAssignment>> others = new LinkedHashMap<>(pendingCover);
        others.remove(p);
        final String error =
            validateCover(p, choice == null ? List.of() : choice, others, pending);
        if (error == null) {
          if (choice != null && !choice.isEmpty()) {
            pendingCover.put(p, new ArrayList<>(choice));
          }
          break;
        }
        problem = error;
        choice = choice == null ? List.of() : choice;
      }
    }
  }

  /** Where one air battle is fought: a territory (or sea zone, or factory), seen from one box. */
  private record Place(Territory zone, Territory territory) {
    String describe() {
      return territory.getName() + " (" + AirAllocation.zoneName(zone) + ")";
    }
  }

  /**
   * Request 27: the targets are fixed before any fighting, so the bombers of each strike are taken
   * now. Request 30: then each territory's air battle, AA and strikes in turn; then all aircraft
   * heal, and one summary lists every battle.
   */
  private void resolveAirPhase() {
    final List<Strike> strikes = buildStrikes();
    final Map<Place, List<Unit>> cover = buildCover();
    pending.clear();
    pendingCover.clear();
    raids.clear();
    aborted.clear();
    summary.clear();
    clearBombedThisRound();
    final Set<Place> places = new LinkedHashSet<>();
    for (final Territory zone : AirZoneAttachment.getAirZones(getData().getMap())) {
      final List<Place> here = new ArrayList<>();
      strikes.stream()
          .filter(s -> s.zone().equals(zone))
          .map(s -> new Place(zone, s.target().territory()))
          .forEach(here::add);
      cover.keySet().stream().filter(p -> p.zone().equals(zone)).forEach(here::add);
      here.sort(java.util.Comparator.comparing(p -> p.territory().getName()));
      places.addAll(here);
    }
    if (!places.isEmpty()) {
      bridge.getHistoryWriter().startEvent("Air battles");
    }
    final IntegerMap<Territory> bombed = new IntegerMap<>();
    for (final Place place : places) {
      final List<Strike> here =
          strikes.stream()
              .filter(
                  s ->
                      s.zone().equals(place.zone())
                          && s.target().territory().equals(place.territory()))
              .sorted(
                  java.util.Comparator.comparing(
                      (Strike s) -> s.target().kind() == TargetKind.STRATEGIC))
              .toList();
      safely(
          "Air battle over " + place.describe(),
          () -> fightPlace(place, here, cover.getOrDefault(place, List.of()), bombed));
    }
    reportAirPhase();
    healAircraft();
  }

  /**
   * Request 30: one territory's air battle (dogfight, interception, aborts), then AA and the
   * strikes there.
   */
  private void fightPlace(
      final Place place,
      final List<Strike> strikes,
      final List<Unit> fighters,
      final IntegerMap<Territory> bombed) {
    final Territory zone = place.zone();
    final List<Unit> aircraft = new ArrayList<>(alive(zone, fighters));
    for (final Strike strike : strikes) {
      strike.survivors().stream().filter(u -> !aircraft.contains(u)).forEach(aircraft::add);
    }
    if (aircraft.isEmpty()) {
      return;
    }
    final GamePlayer first = aircraft.get(0).getOwner();
    final List<Unit> sideA = new ArrayList<>();
    final List<Unit> sideB = new ArrayList<>();
    for (final Unit unit : aircraft) {
      if (AirCover.sameSide(first, unit.getOwner())) {
        sideA.add(unit);
      } else if (AirCombat.atWar(first, unit.getOwner())) {
        sideB.add(unit);
      }
    }
    final List<String> lines = new ArrayList<>();
    if (!sideB.isEmpty() && hasAirFight(sideA, sideB)) {
      fightAirBattle(place, sideA, sideB, lines);
    }
    for (final Strike strike : strikes) {
      fightStrike(strike, bombed, lines);
    }
    if (!lines.isEmpty()) {
      summary.add("<b>" + place.describe() + "</b>: " + String.join("; ", lines));
    }
  }

  /** Whether the sides fight at all: a dogfight, or interceptors against enemy bombers. */
  static boolean hasAirFight(final List<Unit> sideA, final List<Unit> sideB) {
    final boolean fightersA = sideA.stream().anyMatch(AirCombat::isFighterType);
    final boolean fightersB = sideB.stream().anyMatch(AirCombat::isFighterType);
    final boolean bombersA = sideA.stream().anyMatch(AirCombat::isBomber);
    final boolean bombersB = sideB.stream().anyMatch(AirCombat::isBomber);
    return (fightersA && (fightersB || bombersB)) || (fightersB && bombersA);
  }

  /**
   * Request 27: one front's air battle, which is one battle for sticky hits: the dogfight
   * (fighter-types only), interception, then every damaged bomber aborts.
   */
  private void fightAirBattle(
      final Place place,
      final List<Unit> sideA,
      final List<Unit> sideB,
      final List<String> lines) {
    final Territory zone = place.zone();
    final GamePlayer playerA = AirCombat.chooser(sideA);
    final GamePlayer playerB = AirCombat.chooser(sideB);
    final int rounds = AirCombat.dogfightRounds(getData());
    final boolean dogfight =
        rounds > 0 && !fighters(zone, sideA).isEmpty() && !fighters(zone, sideB).isEmpty();
    final List<String> steps = new ArrayList<>();
    if (dogfight) {
      for (int round = 1; round <= rounds; round++) {
        steps.add(fireStep(playerA, round));
        steps.add(fireStep(playerB, round));
        steps.add(casualtyStep(playerB, round));
        steps.add(casualtyStep(playerA, round));
      }
    }
    steps.add(interceptStep(playerA));
    steps.add(returnFireStep(playerB));
    steps.add(interceptStep(playerB));
    steps.add(returnFireStep(playerA));
    steps.add(bomberLossStep(playerB));
    steps.add(fighterLossStep(playerA));
    steps.add(bomberLossStep(playerA));
    steps.add(fighterLossStep(playerB));
    steps.add(ABORTS);
    // request 30: no battle window; the air phase ends with one summary
    final AirBattleView view = AirBattleView.quiet(bridge, playerA, playerB);
    currentView = view;
    bridge.getHistoryWriter().addChildToEvent("Air battle over " + place.describe());
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
    // 1. the dogfight: fighter-types only; bombers neither fire nor are hit
    for (int round = 1; dogfight && round <= rounds; round++) {
      final List<Unit> aliveA = fighters(zone, sideA);
      final List<Unit> aliveB = fighters(zone, sideB);
      if (aliveA.isEmpty() || aliveB.isEmpty()) {
        break;
      }
      final String text = "Dogfight over " + place.territory().getName() + ", round " + round;
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
    // 2. interception: every surviving fighter-type, damaged or not
    intercept(zone, place.territory(), view, playerA, playerB, sideA, sideB);
    // 3. aborts: every damaged bomber
    final List<Unit> nowAborted = new ArrayList<>();
    for (final Unit bomber : alive(zone, everyone)) {
      if (AirCombat.isBomber(bomber) && bomber.getHits() > 0 && aborted.add(bomber)) {
        nowAborted.add(bomber);
      }
    }
    if (nowAborted.isEmpty()) {
      view.showText(ABORTS, List.of("No bomber is damaged: none abort."), "");
    } else {
      view.showText(
          ABORTS,
          List.of(
              "Damaged bombers abort: " + AirCombat.sideText(nowAborted) + ".",
              "They make no strike or bombing run this round, but still face AA at their target.",
              "They heal at the end of the air phase."),
          nowAborted.size() + " aborted");
      bridge
          .getHistoryWriter()
          .addChildToEvent(
              "Aborted (damaged, no strike this round): " + AirCombat.sideText(nowAborted),
              nowAborted);
    }
    final List<Unit> lost =
        everyone.stream().filter(u -> !zone.getUnitCollection().contains(u)).toList();
    final List<Unit> left =
        everyone.stream().filter(u -> zone.getUnitCollection().contains(u)).toList();
    bridge
        .getHistoryWriter()
        .addChildToEvent(
            "Result: lost " + AirCombat.sideText(lost) + "; left " + AirCombat.sideText(left));
    final List<Unit> lostA = sideA.stream().filter(lost::contains).toList();
    final List<Unit> lostB = sideB.stream().filter(lost::contains).toList();
    lines.add(
        "air battle: "
            + lossText(playerA, lostA)
            + "; "
            + lossText(playerB, lostB)
            + (nowAborted.isEmpty() ? "" : "; aborted " + AirCombat.sideText(nowAborted)));
  }

  /** "Germans lost none" or "lost Germans 1 fighter". */
  private static String lossText(final GamePlayer side, final List<Unit> lost) {
    return lost.isEmpty() ? side.getName() + " lost none" : "lost " + AirCombat.sideText(lost);
  }

  /**
   * Request 27, the interception step on its own (a later rule limiting interceptors only changes
   * this): each side's surviving fighter-types fire at the enemy's bomber-types at interception
   * value, and those bombers fire back at them at air defense value. All four rolls are made before
   * any hit is taken. A side with no bombers draws no fire; a side with no fighter-types does not
   * intercept, and its bombers have nobody to fire back at.
   */
  private void intercept(
      final Territory zone,
      final Territory over,
      final AirBattleView view,
      final GamePlayer playerA,
      final GamePlayer playerB,
      final List<Unit> sideA,
      final List<Unit> sideB) {
    final List<Unit> fightersA = fighters(zone, sideA);
    final List<Unit> fightersB = fighters(zone, sideB);
    final List<Unit> bombersA = bombers(zone, sideA);
    final List<Unit> bombersB = bombers(zone, sideB);
    final String text = "Interception over " + over.getName();
    // request 31: Window takes 1 off every interceptor firing at a raid that includes a British
    // strategic bomber
    final int penaltyB = AirCombat.interceptionPenalty(bombersB);
    final int penaltyA = AirCombat.interceptionPenalty(bombersA);
    final Shot interceptA =
        shoot(
            view,
            fightersA,
            bombersB,
            u -> AirCombat.interceptionPower(u, penaltyB),
            interceptStep(playerA),
            text,
            " fighters intercept");
    final Shot backB =
        shoot(
            view,
            bombersB,
            fightersA,
            AirCombat::airDefensePower,
            returnFireStep(playerB),
            text,
            " bombers fire back");
    final Shot interceptB =
        shoot(
            view,
            fightersB,
            bombersA,
            u -> AirCombat.interceptionPower(u, penaltyA),
            interceptStep(playerB),
            text,
            " fighters intercept");
    final Shot backA =
        shoot(
            view,
            bombersA,
            fightersB,
            AirCombat::airDefensePower,
            returnFireStep(playerA),
            text,
            " bombers fire back");
    take(zone, view, interceptA, bomberLossStep(playerB), text);
    take(zone, view, backB, fighterLossStep(playerA), text);
    take(zone, view, interceptB, bomberLossStep(playerA), text);
    take(zone, view, backA, fighterLossStep(playerB), text);
  }

  /** One roll in the interception step, and the units it may hit. */
  private record Shot(List<Unit> firing, List<Unit> targets, Roll roll, DiceRoll dice) {}

  private @Nullable Shot shoot(
      final AirBattleView view,
      final List<Unit> firing,
      final List<Unit> targets,
      final ToIntFunction<Unit> power,
      final String step,
      final String text,
      final String what) {
    if (firing.isEmpty() || targets.isEmpty()) {
      return null;
    }
    final GamePlayer roller = AirCombat.chooser(firing);
    final Roll roll = fire(firing, power, text);
    final DiceRoll dice = view.showDice(roll, roller, step, roller.getName() + what);
    return new Shot(firing, targets, roll, dice);
  }

  private void take(
      final Territory zone,
      final AirBattleView view,
      final @Nullable Shot shot,
      final String step,
      final String text) {
    if (shot == null) {
      return;
    }
    AirCombat.takeHits(
        bridge,
        view,
        step,
        zone,
        alive(zone, shot.targets()),
        shot.firing(),
        shot.roll(),
        shot.dice(),
        text);
  }

  private static String fireStep(final GamePlayer player, final int round) {
    return player.getName() + " fire, round " + round;
  }

  private static String casualtyStep(final GamePlayer player, final int round) {
    return player.getName() + " casualties, round " + round;
  }

  private static String interceptStep(final GamePlayer player) {
    return player.getName() + " intercept";
  }

  private static String returnFireStep(final GamePlayer player) {
    return player.getName() + " bombers fire back";
  }

  private static String bomberLossStep(final GamePlayer player) {
    return player.getName() + " bombers hit";
  }

  private static String fighterLossStep(final GamePlayer player) {
    return player.getName() + " fighters hit";
  }

  private static List<Unit> alive(final Territory where, final List<Unit> units) {
    return units.stream().filter(u -> where.getUnitCollection().contains(u)).toList();
  }

  private static List<Unit> fighters(final Territory zone, final List<Unit> side) {
    return alive(zone, side).stream().filter(AirCombat::isFighterType).toList();
  }

  private static List<Unit> bombers(final Territory zone, final List<Unit> side) {
    return alive(zone, side).stream().filter(AirCombat::isBomber).toList();
  }

  private Roll fire(final List<Unit> units, final ToIntFunction<Unit> power, final String text) {
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

  /** Request 27: at the end of the air phase every damaged aircraft in a box heals. */
  private void healAircraft() {
    for (final Territory zone : AirZoneAttachment.getAirZones(getData().getMap())) {
      final IntegerMap<Unit> healed = new IntegerMap<>();
      zone.getUnitCollection()
          .getMatches(Matches.unitIsAir().and(u -> u.getHits() > 0))
          .forEach(u -> healed.put(u, 0));
      if (healed.isEmpty()) {
        continue;
      }
      final List<Unit> units = new ArrayList<>(healed.keySet());
      bridge
          .getHistoryWriter()
          .startEvent(
              MyFormatter.unitsToText(units)
                  + " in "
                  + zone.getName()
                  + " heal at the end of the air phase",
              units);
      bridge.addChange(ChangeFactory.unitsHit(healed, List.of(zone)));
    }
  }

  /** A strike: aircraft from one zone against one target. */
  private record Strike(Territory zone, Target target, List<Unit> aircraft) {
    /** The strike's aircraft still alive, aborted ones included (AA fires at them all). */
    List<Unit> survivors() {
      return aircraft.stream().filter(u -> zone.getUnitCollection().contains(u)).toList();
    }
  }

  /** Request 27: the strike's aircraft still alive and not aborted; only these attack. */
  private List<Unit> attackers(final Strike strike) {
    return strike.survivors().stream().filter(u -> !aborted.contains(u)).toList();
  }

  private static String strikeName(final Strike strike) {
    return strike.target().kind() == TargetKind.STRATEGIC
        ? "Strategic bombing of " + strike.target().territory().getName() + raidTag(strike)
        : "Air strike on " + strike.target().describe();
  }

  /** Request 26: " (long-range raid)" for a raid by long-range raiders, else "". */
  private static String raidTag(final Strike strike) {
    return strike.aircraft().stream()
            .anyMatch(u -> LongRangeRaid.isRaidZone(u.getOwner(), u.getType(), strike.zone()))
        ? " (" + LongRangeRaid.LABEL.toLowerCase(java.util.Locale.ROOT) + ")"
        : "";
  }

  /**
   * Request 26: after the strikes, long-range raiders in the raid zone fly back to the box they
   * were launched from.
   */
  private void returnRaiders() {
    final java.util.Optional<Territory> from = LongRangeRaid.from(getData());
    final java.util.Optional<Territory> to = LongRangeRaid.to(getData());
    if (from.isEmpty() || to.isEmpty()) {
      return;
    }
    final List<Unit> raiders =
        to.get()
            .getUnitCollection()
            .getMatches(u -> LongRangeRaid.isRaider(u.getOwner(), u.getType()));
    if (raiders.isEmpty()) {
      return;
    }
    bridge
        .getHistoryWriter()
        .startEvent(
            MyFormatter.unitsToText(raiders)
                + " fly back from "
                + to.get().getName()
                + " to "
                + from.get().getName(),
            raiders);
    bridge.addChange(ChangeFactory.moveUnits(to.get(), from.get(), raiders));
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

  /**
   * Each strike is one battle: AA at land targets first (at every bomber assigned there, aborted
   * ones too; the bombers it damages abort), then the attack by the bombers neither lost nor
   * aborted, then (sea targets) the ships hit fire back at those attackers. Tactical strikes come
   * before strategic bombing. AA guns cannot be hit by strikes and each aircraft is in one strike
   * only, so taking the strikes one at a time gives the same result as AA everywhere first, then
   * all tactical strikes, then all bombing.
   */
  private void fightStrike(
      final Strike strike, final IntegerMap<Territory> bombed, final List<String> lines) {
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
    final String title =
        kind == TargetKind.STRATEGIC
            ? "Strategic bombing of " + t.getName() + raidTag(strike)
            : "Air strike on " + strike.target().describe();
    final List<Unit> abortedBefore =
        strike.survivors().stream().filter(aborted::contains).toList();
    if (aaGuns.isEmpty() && attackers(strike).isEmpty()) {
      // nothing fires at aborted bombers here and they make no attack
      bridge
          .getHistoryWriter()
          .addChildToEvent(
              title + ": no attack, " + AirCombat.sideText(abortedBefore) + " aborted",
              abortedBefore);
      lines.add(title + ": no attack (" + AirCombat.sideText(abortedBefore) + " aborted)");
      return;
    }
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
          case COVER -> List.of(); // fighter cover is never a strike
        };
    final List<Unit> defenders = new ArrayList<>(targets);
    aaGuns.stream().filter(u -> !defenders.contains(u)).forEach(defenders::add);
    final GamePlayer defender = defenders.isEmpty() ? t.getOwner() : AirCombat.chooser(defenders);
    final String aaFire = "AA fire";
    final String aaCasualties = attacker.getName() + " casualties from AA";
    final String abortStep = "Aborted bombers";
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
    steps.add(abortStep);
    steps.add(strikeStep);
    if (kind != TargetKind.STRATEGIC) {
      steps.add(strikeCasualties);
    }
    if (kind == TargetKind.SURFACE) {
      steps.add(fireBack);
      steps.add(fireBackCasualties);
    }
    final AirBattleView view = AirBattleView.quiet(bridge, attacker, defender);
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
                + (abortedBefore.isEmpty()
                    ? ""
                    : " (aborted: " + AirCombat.sideText(abortedBefore) + ")")
                + " against "
                + (defenders.isEmpty() ? "no units" : AirCombat.sideText(defenders)),
            involved);

    // 1. AA guns fire first, at every bomber assigned here (aborted ones too); damaged bombers
    // take the hits first (sticky hits within the bombers this AA can reach)
    if (!aaGuns.isEmpty()) {
      final String text = "AA fire in " + t.getName();
      final Roll roll = fire(aaGuns, AirCombat::aaPower, text);
      final DiceRoll dice = view.showDice(roll, AirCombat.chooser(aaGuns), aaFire, "AA guns");
      AirCombat.takeHits(
          bridge, view, aaCasualties, strike.zone(), strike.survivors(), aaGuns, roll, dice, text);
    }
    // 2. aborts: every bomber damaged so far (air battle or this AA) makes no attack
    final List<Unit> abortedByAa = new ArrayList<>();
    for (final Unit bomber : strike.survivors()) {
      if (bomber.getHits() > 0 && aborted.add(bomber)) {
        abortedByAa.add(bomber);
      }
    }
    if (!abortedByAa.isEmpty()) {
      bridge
          .getHistoryWriter()
          .addChildToEvent(
              "Aborted after AA (damaged, no attack): " + AirCombat.sideText(abortedByAa),
              abortedByAa);
    }
    final List<Unit> abortedNow =
        strike.survivors().stream().filter(aborted::contains).toList();
    view.showText(
        abortStep,
        List.of(
            abortedNow.isEmpty()
                ? "No bomber here is damaged: all attack."
                : "Damaged bombers abort and make no attack: "
                    + AirCombat.sideText(abortedNow)
                    + "."),
        abortedNow.isEmpty() ? "" : abortedNow.size() + " aborted");
    final List<Unit> attackers = attackers(strike);
    if (attackers.isEmpty()) {
      final String result =
          strike.survivors().isEmpty()
              ? "all attacking aircraft lost to AA"
              : "no attack: every bomber lost or aborted";
      strikeResult(title, strike, result);
      lines.add(title + ": " + result);
      return;
    }
    if (kind == TargetKind.STRATEGIC) {
      final String result = strategicBombing(strike, attackers, bombed, targets, view, strikeStep);
      strikeResult(title, strike, result);
      lines.add(title + ": " + result + lossAndAborts(strike, abortedNow));
      return;
    }
    // 3. the strike
    final String text = "Air strike on " + strike.target().describe();
    final Roll roll = fire(attackers, u -> AirCombat.strikePower(u, kind), text);
    final DiceRoll dice = view.showDice(roll, AirCombat.chooser(attackers), strikeStep);
    final Hit hit =
        AirCombat.takeHits(
            bridge, view, strikeCasualties, t, targets, attackers, roll, dice, text);
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
      // 4. ships destroyed or damaged by the strike fire back at the bombers that attacked
      // (aborted bombers made no attack, so no ship fires at them)
      final List<Unit> exposed = alive(strike.zone(), attackers);
      if (!hit.all().isEmpty() && !exposed.isEmpty()) {
        final String backText = "Ships in " + t.getName() + " fire back";
        final Roll back = fire(hit.all(), AirCombat::shipFireBackPower, backText);
        final DiceRoll backDice =
            view.showDice(back, AirCombat.chooser(hit.all()), fireBack, "Ships");
        AirCombat.takeHits(
            bridge,
            view,
            fireBackCasualties,
            strike.zone(),
            exposed,
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
            + (lost > 0 ? "; " + lost + " aircraft lost" : "")
            + (abortedNow.isEmpty() ? "" : "; " + abortedNow.size() + " aborted");
    strikeResult(title, strike, result);
    lines.add(
        title
            + ": "
            + (hit.all().isEmpty() ? "no hits" : hitText(hit))
            + lossAndAborts(strike, abortedNow));
  }

  /** "2 infantry destroyed, 1 tank damaged". */
  private static String hitText(final Hit hit) {
    final List<String> parts = new ArrayList<>();
    if (!hit.killed().isEmpty()) {
      parts.add(MyFormatter.unitsToTextNoOwner(hit.killed()) + " destroyed");
    }
    if (!hit.damaged().isEmpty()) {
      parts.add(MyFormatter.unitsToTextNoOwner(hit.damaged()) + " damaged");
    }
    return String.join(", ", parts);
  }

  /** "; 1 aircraft lost; 2 aborted" for a strike's summary line, or "". */
  private static String lossAndAborts(final Strike strike, final List<Unit> abortedNow) {
    final int lost = strike.aircraft().size() - strike.survivors().size();
    return (lost > 0 ? "; " + lost + " aircraft lost" : "")
        + (abortedNow.isEmpty() ? "" : "; " + abortedNow.size() + " aborted");
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
   *
   * @param bombers the bombers that bomb: neither lost nor aborted
   */
  private String strategicBombing(
      final Strike strike,
      final List<Unit> bombers,
      final IntegerMap<Territory> bombed,
      final List<Unit> targets,
      final AirBattleView view,
      final String step) {
    final Territory t = strike.target().territory();
    final GamePlayer victim = t.getOwner();
    final GamePlayer attacker = AirCombat.chooser(bombers);
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
    for (final Unit bomber : bombers) {
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
    return victim.getName()
        + " lose "
        + loss
        + " PUs"
        + (damage > capLeft
            ? " (" + damage + " PUs of damage, capped at " + capLeft + ": 2 × the territory's value)"
            : "");
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

  /**
   * Request 30: at the end of the air phase one summary for everyone: each territory's battle
   * (losses, aborts, strike hits), then every raid with its damage and the total per nation.
   */
  private void reportAirPhase() {
    if (summary.isEmpty() && raids.isEmpty()) {
      return;
    }
    final List<String> lines = new ArrayList<>(summary);
    summary.forEach(line -> bridge.getHistoryWriter().addChildToEvent(line.replaceAll("<[^>]*>", "")));
    if (!raids.isEmpty()) {
      final Map<GamePlayer, Integer> totals = new LinkedHashMap<>();
      raids.forEach(r -> totals.merge(r.victim(), r.loss(), Integer::sum));
      final List<String> bombing = new ArrayList<>(raids.stream().map(Raid::describe).toList());
      totals.forEach(
          (victim, loss) ->
              bombing.add(
                  "Total: "
                      + victim.getName()
                      + " "
                      + MINUS
                      + loss
                      + " PUs from bombing this round"));
      bridge.getHistoryWriter().startEvent("Strategic bombing this round");
      bombing.forEach(line -> bridge.getHistoryWriter().addChildToEvent(line));
      lines.add("");
      lines.add("<b>Strategic bombing</b>");
      lines.addAll(bombing);
    }
    bridge
        .getDisplayChannelBroadcaster()
        .reportMessageToAll(
            "<html>" + String.join("<br>", lines) + "</html>",
            "Air battles this round",
            false,
            false,
            false);
  }

  /**
   * Request 30: the fighters each nation placed, by place, undamaged ones first (an aircraft is in
   * one place only).
   */
  private Map<Place, List<Unit>> buildCover() {
    final Map<Place, List<Unit>> result = new LinkedHashMap<>();
    for (final Map.Entry<GamePlayer, List<TargetAssignment>> entry : pendingCover.entrySet()) {
      final Set<Unit> used = new HashSet<>();
      for (final TargetAssignment a : entry.getValue()) {
        final List<Unit> units =
            a.zone().getUnitCollection().stream()
                .filter(
                    u ->
                        u.isOwnedBy(entry.getKey())
                            && AirCombat.isFighterType(u)
                            && u.getType().equals(a.unitType())
                            && !used.contains(u))
                .sorted(java.util.Comparator.comparingInt(Unit::getHits))
                .limit(a.count())
                .toList();
        used.addAll(units);
        result
            .computeIfAbsent(new Place(a.zone(), a.target().territory()), k -> new ArrayList<>())
            .addAll(units);
      }
    }
    return result;
  }

  /**
   * The strikes with their aircraft, taken before any fighting (request 27): an aircraft keeps its
   * target whatever happens to it in the air battle. Undamaged aircraft are taken first.
   */
  private List<Strike> buildStrikes() {
    final Map<String, Strike> strikes = new LinkedHashMap<>();
    for (final Map.Entry<GamePlayer, List<TargetAssignment>> entry : pending.entrySet()) {
      final Set<Unit> used = new HashSet<>();
      for (final TargetAssignment a : entry.getValue()) {
        final List<Unit> units =
            AirCombat.getBombers(entry.getKey(), a.zone()).stream()
                .filter(u -> u.getType().equals(a.unitType()) && !used.contains(u))
                .sorted(java.util.Comparator.comparingInt(Unit::getHits))
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
    state.pendingCover = new LinkedHashMap<>(pendingCover);
    return state;
  }

  @Override
  public void loadState(final Serializable state) {
    final AirCombatState s = (AirCombatState) state;
    super.loadState(s.superState);
    pending = new LinkedHashMap<>(s.pending);
    // saves from before request 30 have no fighter cover
    pendingCover =
        s.pendingCover == null ? new LinkedHashMap<>() : new LinkedHashMap<>(s.pendingCover);
  }

  @Override
  public Class<? extends IRemote> getRemoteType() {
    return IAirCombatDelegate.class;
  }

  private static final class AirCombatState implements Serializable {
    @Serial private static final long serialVersionUID = -4093180047782251877L;

    private Serializable superState;
    private Map<GamePlayer, List<TargetAssignment>> pending;
    private Map<GamePlayer, List<TargetAssignment>> pendingCover;
  }
}
