package games.strategy.triplea.delegate;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.message.IRemote;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.ApcAttachment;
import games.strategy.triplea.delegate.air.AirBases;
import games.strategy.triplea.delegate.air.AirBattleReport;
import games.strategy.triplea.delegate.air.AirBattleView;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.air.AirCombat.Hit;
import games.strategy.triplea.delegate.air.AirCombat.Roll;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.delegate.air.AirCover;
import games.strategy.triplea.formatter.MyFormatter;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel air battles (requests 27, 30 and 38), fought in the "airStrikes" step after the
 * simultaneous air stages ({@link AirStageDelegate}) have put every flying aircraft in the air over
 * its target or the place it escorts or defends. Each territory or sea zone with aircraft over it
 * fights one air battle: the dogfight between the fighter-types there (game property "FS Dogfight
 * Rounds" rounds), then interception (surviving fighters fire at the enemy's bombers there, which
 * fire back), then every damaged bomber aborts. Then the territory's AA guns fire once, each gun
 * its own shot, at every bomber over it whatever their missions (damaged ones first; bombers it
 * damages abort too); then the tactical strikes and the strategic bombing by the bombers neither
 * lost nor aborted; ships hit fire back. Casualties are picked at random (sticky hits apply), no
 * battle window opens, and one summary lists every battle. All aircraft in the air heal at the end
 * of the air phase; they land at the end of the round.
 *
 * <p>All fire uses low luck.
 */
@Slf4j
public class AirCombatDelegate extends BaseTripleADelegate {
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
  // request 46: one picture row per territory's battle, and the battle being fought
  private final transient List<AirBattleReport.Row> rows = new ArrayList<>();
  private transient @Nullable PlaceLog placeLog;

  @Override
  public void start() {
    super.start();
    if (stepName().startsWith("airStrikes")) {
      resolveAirPhase();
    }
  }

  /** The bomber targets chosen in the bomber stage, over the airborne territory. */
  public void setStageBombers(final Map<GamePlayer, List<TargetAssignment>> targets) {
    pending = copy(targets);
  }

  /** The fighter placements chosen in the fighter stage, over the airborne territory. */
  public void setStageCover(final Map<GamePlayer, List<TargetAssignment>> cover) {
    pendingCover = copy(cover);
  }

  @Override
  public boolean delegateCurrentlyRequiresUserInput() {
    return false;
  }

  private static Map<GamePlayer, List<TargetAssignment>> copy(
      final Map<GamePlayer, List<TargetAssignment>> map) {
    final Map<GamePlayer, List<TargetAssignment>> result = new LinkedHashMap<>();
    map.forEach((p, list) -> result.put(p, List.copyOf(list)));
    return result;
  }

  /** Why these targets are not allowed, or null. */
  /** Where one air battle is fought: a territory or sea zone, its aircraft in the air over it. */
  private record Place(Territory zone, Territory territory) {
    String describe() {
      return territory.getName();
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
    aborted.clear();
    raids.clear();
    summary.clear();
    rows.clear();
    clearBombedThisRound();
    final Set<Place> places =
        new java.util.TreeSet<>(
            java.util.Comparator.comparing((Place p) -> p.territory().getName()));
    strikes.forEach(s -> places.add(new Place(s.zone(), s.target().territory())));
    places.addAll(cover.keySet());
    if (!places.isEmpty()) {
      bridge.getHistoryWriter().startEvent("Air battles");
    }
    final IntegerMap<Territory> bombed = new IntegerMap<>();
    for (final Place place : places) {
      final List<Strike> here =
          strikes.stream()
              .filter(s -> s.target().territory().equals(place.territory()))
              .sorted(
                  java.util.Comparator.comparing(
                      (Strike s) -> s.target().kind() == TargetKind.STRATEGIC))
              .toList();
      final List<Unit> fighters = new ArrayList<>();
      cover.forEach(
          (p, units) -> {
            if (p.territory().equals(place.territory())) {
              fighters.addAll(units);
            }
          });
      safely(
          "Air battle over " + place.describe(), () -> fightPlace(place, here, fighters, bombed));
    }
    reportAirPhase();
    healAircraft();
    // request 38: aircraft on carriers sunk by air strikes move one space
    games.strategy.triplea.delegate.air.AirBaseLoss.freeMoves(
        bridge, true, "Carriers sunk by air strikes");
  }

  /**
   * Request 30: one territory's air battle (dogfight, interception, aborts), then (request 38
   * section 11) one AA volley at every bomber over it, then the strikes there.
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
    final PlaceLog report = new PlaceLog(place, attackerHere(place, strikes, aircraft));
    placeLog = report;
    aircraft.forEach(u -> report.watch(u, zone, true));
    // request 43 (Combat Air Patrol): carriers below fire at enemy bombers in the interception,
    // even with no fighters of their own in the air there
    final List<Unit> patrolsA = AirCombat.patrolCarriers(place.territory(), bombers(zone, sideB))
        .stream().filter(c -> AirCover.sameSide(first, c.getOwner())).toList();
    final List<Unit> patrolsB = AirCombat.patrolCarriers(place.territory(), bombers(zone, sideA))
        .stream().filter(c -> !AirCover.sameSide(first, c.getOwner())).toList();
    patrolsA.forEach(u -> report.watch(u, place.territory(), true));
    patrolsB.forEach(u -> report.watch(u, place.territory(), true));
    try {
      if ((!sideB.isEmpty() && hasAirFight(sideA, sideB))
          || !patrolsA.isEmpty()
          || !patrolsB.isEmpty()) {
        fightAirBattle(place, sideA, sideB, patrolsA, patrolsB, lines);
      }
      fireAa(place, strikes, lines);
      for (final Strike strike : strikes) {
        fightStrike(strike, bombed, lines);
      }
    } finally {
      placeLog = null;
    }
    if (!lines.isEmpty()) {
      summary.add("<b>" + place.describe() + "</b>: " + String.join("; ", lines));
      rows.add(report.row(aborted));
    }
  }

  /**
   * Request 46: who attacks in this battle, for the picture row: the bombers' nation, or else the
   * aircraft from outside the territory's side.
   */
  private static GamePlayer attackerHere(
      final Place place, final List<Strike> strikes, final List<Unit> aircraft) {
    for (final Strike strike : strikes) {
      if (!strike.aircraft().isEmpty()) {
        return AirCombat.chooser(strike.aircraft());
      }
    }
    final GamePlayer owner = place.territory().getOwner();
    if (!place.territory().isWater() && !owner.isNull()) {
      for (final Unit unit : aircraft) {
        if (!AirCover.sameSide(owner, unit.getOwner())) {
          return unit.getOwner();
        }
      }
    }
    return aircraft.get(0).getOwner();
  }

  /**
   * Request 46: what happened to the units in one territory's battle, for its row of pictures in
   * the summary. Units are watched before they fight; those always shown (aircraft, AA guns that
   * fired, patrolling carriers, a factory bombed) appear whatever happens to them, the others (units
   * a strike could hit) only when destroyed or damaged.
   */
  private static final class PlaceLog {
    private final Place place;
    private final GamePlayer attacker;
    private final Map<Unit, Territory> where = new LinkedHashMap<>();
    private final Map<Unit, Integer> hitsBefore = new java.util.HashMap<>();
    private final Set<Unit> always = new HashSet<>();
    private final Map<Unit, Integer> bombed = new java.util.HashMap<>();
    private final Map<GamePlayer, Integer> pusLost = new LinkedHashMap<>();
    private final List<String> notes = new ArrayList<>();
    private int dogfightRounds;

    PlaceLog(final Place place, final GamePlayer attacker) {
      this.place = place;
      this.attacker = attacker;
    }

    void watch(final Unit unit, final Territory location, final boolean show) {
      where.putIfAbsent(unit, location);
      hitsBefore.putIfAbsent(unit, unit.getHits());
      if (show) {
        always.add(unit);
      }
    }

    void watch(final Collection<Unit> units, final Territory location, final boolean show) {
      units.forEach(u -> watch(u, location, show));
    }

    void bombed(final GamePlayer victim, final List<Unit> targets, final int loss) {
      if (targets.isEmpty()) {
        // no factory or SPC picture to carry the loss
        notes.add(MINUS + loss + " PU");
      } else {
        bombed.merge(targets.get(0), loss, Integer::sum);
      }
      pusLost.merge(victim, loss, Integer::sum);
    }

    private boolean destroyed(final Unit unit) {
      return !where.get(unit).getUnitCollection().contains(unit);
    }

    private boolean damaged(final Unit unit) {
      return !destroyed(unit) && unit.getHits() > hitsBefore.get(unit);
    }

    AirBattleReport.Row row(final Set<Unit> abortedBombers) {
      final List<Unit> shown =
          where.keySet().stream()
              .filter(u -> always.contains(u) || destroyed(u) || damaged(u))
              .toList();
      final Map<GamePlayer, IntegerMap<games.strategy.engine.data.UnitType>> costs =
          new java.util.HashMap<>();
      final Map<String, Integer> lossValue = new LinkedHashMap<>();
      final Set<String> hurt = new LinkedHashSet<>();
      for (final Unit unit : shown) {
        final GamePlayer owner = unit.getOwner();
        if (destroyed(unit)) {
          final int cost =
              costs
                  .computeIfAbsent(
                      owner,
                      p -> new games.strategy.triplea.util.TuvCostsCalculator().getCostsForTuv(p))
                  .getInt(unit.getType());
          lossValue.merge(owner.getName(), cost, Integer::sum);
          hurt.add(owner.getName());
        } else if (damaged(unit)) {
          hurt.add(owner.getName());
        }
      }
      pusLost.forEach(
          (victim, loss) -> {
            if (loss > 0) {
              lossValue.merge(victim.getName(), loss, Integer::sum);
              hurt.add(victim.getName());
            }
          });
      final List<AirBattleReport.Stack> attackers = new ArrayList<>();
      final List<AirBattleReport.Stack> defenders = new ArrayList<>();
      final Map<List<Object>, int[]> counts = new LinkedHashMap<>();
      for (final Unit unit : shown) {
        final boolean dead = destroyed(unit);
        final List<Object> key =
            List.of(
                unit.getOwner(),
                unit.getType(),
                dead ? 0 : unit.getHits(),
                dead,
                abortedBombers.contains(unit),
                bombed.getOrDefault(unit, 0));
        counts.computeIfAbsent(key, k -> new int[1])[0]++;
      }
      counts.forEach(
          (key, count) -> {
            final GamePlayer owner = (GamePlayer) key.get(0);
            final AirBattleReport.Stack stack =
                new AirBattleReport.Stack(
                    owner.getName(),
                    ((games.strategy.engine.data.UnitType) key.get(1)).getName(),
                    count[0],
                    (Integer) key.get(2),
                    (Boolean) key.get(3),
                    (Boolean) key.get(4),
                    (Integer) key.get(5));
            (AirCover.sameSide(attacker, owner) ? attackers : defenders).add(stack);
          });
      // request 47: text only for what the pictures can't show (they show losses and damage)
      final List<String> text = new ArrayList<>();
      if (dogfightRounds > 0) {
        text.add(dogfightRounds + MyFormatter.pluralize(" dogfight round", dogfightRounds));
      }
      text.addAll(notes);
      return new AirBattleReport.Row(
          place.territory().getName(),
          attackers,
          defenders,
          text,
          lossValue,
          List.copyOf(hurt));
    }

  }

  /**
   * Request 38 section 11: the territory's AA guns fire once in total, each gun its own shot,
   * whatever the number of bombers and their missions; hits go to damaged bombers first, and
   * bombers it damages abort.
   */
  private void fireAa(final Place place, final List<Strike> strikes, final List<String> lines) {
    final Territory t = place.territory();
    if (t.isWater()) {
      return;
    }
    final List<Unit> bombers = new ArrayList<>();
    for (final Strike s : strikes) {
      if (s.target().kind() == TargetKind.LAND || s.target().kind() == TargetKind.STRATEGIC) {
        s.survivors().stream().filter(u -> !bombers.contains(u)).forEach(bombers::add);
      }
    }
    if (bombers.isEmpty()) {
      return;
    }
    final GamePlayer attacker = AirCombat.chooser(bombers);
    final List<Unit> aaGuns =
        t.getUnitCollection()
            .getMatches(
                Matches.unitIsAaForAnything().and(u -> AirCombat.atWar(attacker, u.getOwner())));
    if (aaGuns.isEmpty()) {
      return;
    }
    if (placeLog != null) {
      placeLog.watch(aaGuns, t, true);
    }
    final String aaCasualties = attacker.getName() + " casualties from AA";
    final AirBattleView view = AirBattleView.quiet(bridge, attacker, AirCombat.chooser(aaGuns));
    currentView = view;
    final String text = "AA fire in " + t.getName();
    final Roll roll = fire(aaGuns, AirCombat::aaPower, text);
    final DiceRoll dice = view.showDice(roll, AirCombat.chooser(aaGuns), "AA fire", "AA guns");
    final Hit hit =
        AirCombat.takeHits(
            bridge, view, aaCasualties, place.zone(), bombers, aaGuns, roll, dice, text);
    final List<Unit> abortedByAa = new ArrayList<>();
    for (final Unit bomber : alive(place.zone(), bombers)) {
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
    if (placeLog != null) {
      // request 47: "AA: 1 hit" (the guns are in the pictures)
      placeLog.notes.add(
          "AA: "
              + (hit.all().isEmpty()
                  ? "no hits"
                  : hit.all().size() + MyFormatter.pluralize(" hit", hit.all().size())));
    }
    lines.add(
        "AA ("
            + aaGuns.size()
            + MyFormatter.pluralize(" gun", aaGuns.size())
            + "): "
            + (hit.all().isEmpty()
                ? "no hits"
                : hit.killed().size() + " lost, " + hit.damaged().size() + " damaged"));
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
      final List<Unit> patrolsA,
      final List<Unit> patrolsB,
      final List<String> lines) {
    final Territory zone = place.zone();
    final GamePlayer playerA = AirCombat.chooser(sideA);
    // request 43: with no aircraft of its own there, the side is its patrolling carriers
    final GamePlayer playerB =
        AirCombat.chooser(sideB.isEmpty() ? patrolsB : sideB);
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
      if (placeLog != null) {
        placeLog.dogfightRounds = round;
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
    intercept(zone, place.territory(), view, playerA, playerB, sideA, sideB, patrolsA, patrolsB);
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
      final List<Unit> sideB,
      final List<Unit> patrolsA,
      final List<Unit> patrolsB) {
    final List<Unit> fightersA = fighters(zone, sideA);
    final List<Unit> fightersB = fighters(zone, sideB);
    // request 43 (Combat Air Patrol): the carriers fire with the interceptors, at their defense
    // (plus VT Proximity Fuze); the bombers' air defense fires only at the fighters
    final List<Unit> interceptorsA = new ArrayList<>(fightersA);
    interceptorsA.addAll(patrolsA);
    final List<Unit> interceptorsB = new ArrayList<>(fightersB);
    interceptorsB.addAll(patrolsB);
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
            interceptorsA,
            bombersB,
            u ->
                AirCombat.patrols(u)
                    ? AirCombat.shipFireBackPower(u)
                    : AirCombat.interceptionPower(u, penaltyB),
            interceptStep(playerA),
            text,
            patrolsA.isEmpty() ? " fighters intercept" : " fighters and carriers intercept");
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
            interceptorsB,
            bombersA,
            u ->
                AirCombat.patrols(u)
                    ? AirCombat.shipFireBackPower(u)
                    : AirCombat.interceptionPower(u, penaltyA),
            interceptStep(playerB),
            text,
            patrolsB.isEmpty() ? " fighters intercept" : " fighters and carriers intercept");
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

  /** Request 27: at the end of the air phase every damaged aircraft in the air heals. */
  private void healAircraft() {
    final Territory zone = AirBases.airborne(getData()).orElse(null);
    if (zone == null) {
      return;
    }
    final IntegerMap<Unit> healed = new IntegerMap<>();
    zone.getUnitCollection()
        .getMatches(Matches.unitIsAir().and(u -> u.getHits() > 0))
        .forEach(u -> healed.put(u, 0));
    if (healed.isEmpty()) {
      return;
    }
    final List<Unit> units = new ArrayList<>(healed.keySet());
    bridge
        .getHistoryWriter()
        .startEvent(MyFormatter.unitsToText(units) + " heal at the end of the air phase", units);
    bridge.addChange(ChangeFactory.unitsHit(healed, List.of(zone)));
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

  /**
   * Each strike: the attack by the bombers neither lost nor aborted (AA has already fired once at
   * the whole battle, see {@link #fireAa}), then (sea targets) the ships hit fire back at those
   * attackers. Tactical strikes come before strategic bombing.
   */
  private void fightStrike(
      final Strike strike, final IntegerMap<Territory> bombed, final List<String> lines) {
    if (strike.survivors().isEmpty()) {
      return;
    }
    final TargetKind kind = strike.target().kind();
    final Territory t = strike.target().territory();
    final GamePlayer attacker = AirCombat.chooser(strike.survivors());
    // request 38 section 11: AA fired once for the whole battle, before the strikes
    final List<Unit> aaGuns = List.of();
    final String title =
        kind == TargetKind.STRATEGIC
            ? "Strategic bombing of " + t.getName()
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
      if (placeLog != null) {
        placeLog.notes.add(strikeNote(strike) + ": no attack, all bombers aborted");
      }
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
          case COVER, REBASE -> List.of(); // fighter cover and rebases are never strikes
        };
    if (placeLog != null) {
      // request 46: a factory or SPC bombed is always shown; other targets only when hit
      placeLog.watch(targets, t, kind == TargetKind.STRATEGIC);
    }
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
      if (placeLog != null) {
        placeLog.notes.add(strikeNote(strike) + ": no attack, all bombers lost or aborted");
      }
      return;
    }
    if (kind == TargetKind.STRATEGIC) {
      final String result = strategicBombing(strike, attackers, bombed, targets, view, strikeStep);
      strikeResult(title, strike, result);
      lines.add(title + ": " + result + lossAndAborts(strike, abortedNow));
      return;
    }
    // 3. the strike: one round against land units, two at sea (request 39). After each round at
    // sea the ships hit (damaged or destroyed) fire back; bombers damaged by it abort, make no
    // attack in the next round and take the first hits of its return fire
    final int rounds = AirCombat.strikeRounds(kind);
    final List<Unit> killed = new ArrayList<>();
    final List<Unit> damaged = new ArrayList<>();
    final List<Unit> abortedByShips = new ArrayList<>();
    final List<String> roundText = new ArrayList<>();
    for (int round = 1; round <= rounds; round++) {
      final List<Unit> firing = attackers(strike);
      final List<Unit> left =
          kind == TargetKind.LAND
              ? targets
              : AirCombat.getShipTargetUnits(attacker, t, kind == TargetKind.SUBS);
      if (firing.isEmpty() || left.isEmpty()) {
        break;
      }
      if (placeLog != null) {
        placeLog.watch(left, t, false);
      }
      final String text =
          "Air strike on " + strike.target().describe() + (rounds > 1 ? ", round " + round : "");
      final Roll roll = fire(firing, u -> AirCombat.strikePower(u, kind), text);
      final DiceRoll dice = view.showDice(roll, AirCombat.chooser(firing), strikeStep);
      final Hit hit =
          AirCombat.takeHits(bridge, view, strikeCasualties, t, left, firing, roll, dice, text);
      killed.addAll(hit.killed());
      damaged.removeAll(hit.killed());
      hit.damaged().stream().filter(u -> !damaged.contains(u)).forEach(damaged::add);
      if (rounds > 1) {
        roundText.add(
            "round "
                + round
                + ": "
                + (hit.all().isEmpty() ? "no hits" : hitText(hit)));
      }
      if (kind == TargetKind.SURFACE) {
        // transports are never casualties, but are lost once nothing protects them
        final List<Unit> transports = AirCombat.getUndefendedTransports(attacker, t);
        if (!transports.isEmpty()) {
          if (placeLog != null) {
            placeLog.watch(transports, t, false);
          }
          bridge
              .getHistoryWriter()
              .addChildToEvent(
                  MyFormatter.unitsToText(transports)
                      + " undefended in "
                      + t.getName()
                      + ", lost",
                  transports);
          bridge.addChange(ChangeFactory.removeUnits(t, transports));
        }
        // 4. ships destroyed or damaged this round fire back at the bombers that attacked, and at
        // those that aborted after earlier return fire (aborted before the strike: never)
        final List<Unit> fired = new ArrayList<>(firing);
        abortedByShips.stream().filter(u -> !fired.contains(u)).forEach(fired::add);
        final List<Unit> exposed = alive(strike.zone(), fired);
        // request 43 (Combat Air Patrol): carriers that fired in the interception don't fire back
        final List<Unit> firingBack = hit.all().stream().filter(u -> !AirCombat.patrols(u)).toList();
        if (!firingBack.isEmpty() && !exposed.isEmpty()) {
          final String backText =
              "Ships in " + t.getName() + " fire back" + (rounds > 1 ? ", round " + round : "");
          final Roll back = fire(firingBack, AirCombat::shipFireBackPower, backText);
          final DiceRoll backDice =
              view.showDice(back, AirCombat.chooser(firingBack), fireBack, "Ships");
          AirCombat.takeHits(
              bridge,
              view,
              fireBackCasualties,
              strike.zone(),
              exposed,
              firingBack,
              back,
              backDice,
              backText);
          final List<Unit> nowAborted = new ArrayList<>();
          for (final Unit bomber : alive(strike.zone(), exposed)) {
            if (bomber.getHits() > 0 && aborted.add(bomber)) {
              nowAborted.add(bomber);
              abortedByShips.add(bomber);
            }
          }
          if (!nowAborted.isEmpty() && round < rounds) {
            bridge
                .getHistoryWriter()
                .addChildToEvent(
                    "Aborted after the ships' fire (damaged, no attack in round "
                        + (round + 1)
                        + "): "
                        + AirCombat.sideText(nowAborted),
                    nowAborted);
          }
        }
      }
    }
    final Hit hit = new Hit(killed, damaged);
    final int lost = strike.aircraft().size() - strike.survivors().size();
    final String result =
        hit.killed().size()
            + " destroyed, "
            + hit.damaged().size()
            + " damaged"
            + (roundText.isEmpty() ? "" : " (" + String.join("; ", roundText) + ")")
            + (lost > 0 ? "; " + lost + " aircraft lost" : "")
            + (abortedNow.isEmpty() ? "" : "; " + abortedNow.size() + " aborted");
    strikeResult(title, strike, result);
    lines.add(
        title
            + ": "
            + (hit.all().isEmpty() ? "no hits" : hitText(hit))
            + (roundText.isEmpty() ? "" : " (" + String.join("; ", roundText) + ")")
            + lossAndAborts(strike, abortedNow));
  }

  /** Request 47: "Strike" or "Bombing", for a row's notes. */
  private static String strikeNote(final Strike strike) {
    // request 47: the place is the row's title and the nation its flag
    return strike.target().kind() == TargetKind.STRATEGIC ? "Bombing" : "Strike";
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
    if (placeLog != null) {
      placeLog.bombed(victim, targets, loss);
      if (damage > capLeft) {
        placeLog.notes.add(
            "Bombing: " + damage + " PU of damage, capped at " + capLeft + " (twice the territory's value)");
      }
    }
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
    }
    // request 46: the summary window draws the battles as pictures
    final Map<String, Integer> totals = new LinkedHashMap<>();
    raids.forEach(r -> totals.merge(r.victim().getName(), r.loss(), Integer::sum));
    bridge.getDisplayChannelBroadcaster().showAirBattles(new AirBattleReport(rows, totals));
  }

  /**
   * Request 30: the fighters each nation sent, by place, undamaged ones first (an aircraft is in
   * one place only): the nation's fighter-types in the air over the place.
   */
  private Map<Place, List<Unit>> buildCover() {
    final Map<Place, List<Unit>> result = new LinkedHashMap<>();
    for (final Map.Entry<GamePlayer, List<TargetAssignment>> entry : pendingCover.entrySet()) {
      final Set<Unit> used = new HashSet<>();
      for (final TargetAssignment a : entry.getValue()) {
        final Territory over = a.target().territory();
        final List<Unit> units =
            a.zone().getUnitCollection().stream()
                .filter(
                    u ->
                        u.isOwnedBy(entry.getKey())
                            && AirCombat.isFighterType(u)
                            && u.getType().equals(a.unitType())
                            && over.equals(u.getFlightOver())
                            && !u.getFlightRebase()
                            && !used.contains(u))
                .sorted(java.util.Comparator.comparingInt(Unit::getHits))
                .limit(a.count())
                .toList();
        used.addAll(units);
        result.computeIfAbsent(new Place(a.zone(), over), k -> new ArrayList<>()).addAll(units);
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
        final Territory over = a.target().territory();
        final List<Unit> units =
            a.zone().getUnitCollection().stream()
                .filter(
                    u ->
                        u.isOwnedBy(entry.getKey())
                            && AirCombat.isBomber(u)
                            && u.getType().equals(a.unitType())
                            && over.equals(u.getFlightOver())
                            && !u.getFlightRebase()
                            && !used.contains(u))
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
    return null;
  }

  private static final class AirCombatState implements Serializable {
    @Serial private static final long serialVersionUID = -4093180047782251877L;

    private Serializable superState;
    private Map<GamePlayer, List<TargetAssignment>> pending;
    private Map<GamePlayer, List<TargetAssignment>> pendingCover;
  }
}
