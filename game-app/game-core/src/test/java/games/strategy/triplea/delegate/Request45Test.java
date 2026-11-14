package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.delegate.air.AirBases;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.delegate.air.AirHome;
import games.strategy.triplea.delegate.air.AirRange;
import games.strategy.triplea.delegate.air.AirStage;
import games.strategy.triplea.delegate.air.AirStage.Choice;
import games.strategy.triplea.delegate.air.AirStage.Stage;
import games.strategy.triplea.delegate.air.BasedMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Request 45 on the installed map: rebase orders in the air stages. Aircraft sent with no attack
 * are airborne for the round, join no battle, and land at their destination at Planes Go Home if it
 * is still legal.
 */
class Request45Test {
  private GameData data;
  private GamePlayer italians;
  private GamePlayer british;
  private GamePlayer germans;
  private Territory sicily;
  private Territory serbia;

  @BeforeEach
  void setUp() {
    data = BasedMap.load();
    italians = BasedMap.p(data, "Italians");
    british = BasedMap.p(data, "British");
    germans = BasedMap.p(data, "Germans");
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(newDelegateBridge(germans));
    start.start();
    start.end();
    sicily = t("Sicily");
    serbia = t("Serbia");
  }

  private Territory t(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  private UnitType type(final String name) {
    return data.getUnitTypeList().getUnitTypeOrThrow(name);
  }

  private List<Unit> add(
      final Territory t, final GamePlayer owner, final String type, final int n) {
    final List<Unit> units = type(type).create(n, owner);
    addTo(t, units);
    return units;
  }

  private void war(final GamePlayer a, final GamePlayer b) {
    data.performChange(
        ChangeFactory.relationshipChange(
            a,
            b,
            data.getRelationshipTracker().getRelationshipType(a, b),
            data.getRelationshipTypeList().getRelationshipType("War")));
  }

  private static TargetAssignment rebase(
      final Territory base, final UnitType type, final int n, final Territory to) {
    return new TargetAssignment(base, type, n, AirStage.rebaseTo(to));
  }

  /** Serbia an Italian landing spot, 3 steps (a dive bomber's full range) from Sicily. */
  private Unit diveBomberInSicily() {
    removeFrom(sicily, sicily.getUnitCollection().getMatches(Matches.unitIsAir()));
    data.performChange(ChangeFactory.changeOwner(serbia, italians));
    final Unit dive = add(sicily, italians, "diveBomber", 1).get(0);
    assertThat(AirRange.range(type("diveBomber"), italians), is(3));
    assertThat(AirBases.distance(italians, sicily, serbia, 3).orElse(-1), is(3));
    return dive;
  }

  @Test
  void aDiveBomberRebasesToSerbiaAndLandsThereWithNoInput() {
    final Unit dive = diveBomberInSicily();
    final AirStageRunner runner =
        new AirStageRunner(data)
            .bombers(italians, rebase(sicily, type("diveBomber"), 1, serbia))
            .run();
    assertThat(runner.problems(), is(empty()));
    // airborne over Serbia with no range left, and in no battle
    assertThat(AirBases.isAirborne(dive), is(true));
    assertThat(dive.getFlightOver(), is(serbia));
    assertThat(dive.getFlightRangeLeft(), is(0));
    assertThat(dive.getFlightRebase(), is(true));
    runner.battles();
    assertThat(AirBases.isAirborne(dive), is(true));
    // planes go home: nobody chose anything for it
    runner.planesHome();
    assertThat(serbia.getUnitCollection().contains(dive), is(true));
    assertThat(AirBases.isAirborne(dive), is(false));
    assertThat(dive.getFlightRebase(), is(false));
  }

  @Test
  void planesGoHomeStartsWithTheRebaseLanded() {
    diveBomberInSicily();
    new AirStageRunner(data).bombers(italians, rebase(sicily, type("diveBomber"), 1, serbia)).run();
    final List<AirStage.Landing> start = AirHome.withRebases(italians, List.of());
    assertThat(start.size(), is(1));
    assertThat(start.get(0).to(), is(serbia));
    assertThat(AirHome.isRebaseLanding(italians, start.get(0)), is(true));
  }

  @Test
  void ifSerbiaIsCapturedTheRebasedDiveBomberIsLost() {
    final Unit dive = diveBomberInSicily();
    final AirStageRunner runner =
        new AirStageRunner(data)
            .bombers(italians, rebase(sicily, type("diveBomber"), 1, serbia))
            .run();
    final GamePlayer russians = BasedMap.p(data, "Russians");
    war(russians, italians);
    data.performChange(ChangeFactory.changeOwner(serbia, russians));
    runner.planesHome();
    assertThat(
        dive.getData().getMap().getTerritories().stream()
            .anyMatch(t -> t.getUnitCollection().contains(dive)),
        is(false));
  }

  @Test
  void aRebaseBeyondFullRangeIsRefusedWithTheReason() {
    diveBomberInSicily();
    final Territory far = t("Berlin");
    final Optional<String> why =
        AirStage.problem(
            italians,
            Stage.BOMBERS,
            Choice.of(List.of(rebase(sicily, type("diveBomber"), 1, far))),
            Map.of(),
            Map.of());
    assertThat(why.isPresent(), is(true));
    assertThat(why.get(), containsString("steps from Sicily"));
    assertThat(why.get(), containsString("fly 3"));
  }

  @Test
  void aPlaceWithEnemiesIsAnAttackNotARebase() {
    diveBomberInSicily();
    final Territory malta = t("Malta");
    data.performChange(ChangeFactory.changeOwner(malta, british));
    war(british, italians);
    add(malta, british, "infantry", 1);
    final Optional<String> why =
        AirStage.rebaseProblem(
            italians, sicily, type("diveBomber"), malta, Stage.BOMBERS, Map.of());
    assertThat(why.orElse(""), containsString("attack"));
  }

  @Test
  void aFighterRebasesToACarrierThatMovesAndLandsOnIt() {
    final Territory london = t("London");
    removeFrom(london, london.getUnitCollection().getMatches(Matches.unitIsAir()));
    // a German ship next to London for the British bombers, so there is a fighter stage
    final List<Territory> seas =
        data.getMap().getNeighbors(london).stream().filter(Territory::isWater).toList();
    final Territory attacked = seas.get(0);
    final Territory carrierZone = seas.get(1);
    final Territory moveTo =
        data.getMap().getNeighbors(carrierZone).stream()
            .filter(z -> z.isWater() && !z.equals(attacked) && !z.equals(london))
            .findFirst()
            .orElseThrow();
    for (final Territory z : List.of(attacked, carrierZone, moveTo)) {
      removeFrom(z, z.getUnitCollection().getUnits());
    }
    war(british, germans);
    add(attacked, germans, "destroyer", 1);
    add(london, british, "mediumBomber", 1);
    final Unit fighter = add(london, british, "fighter", 1).get(0);
    final Unit carrier = add(carrierZone, british, "carrier", 1).get(0);
    final AirStageRunner runner =
        new AirStageRunner(data)
            .bombers(
                british,
                new TargetAssignment(
                    london, type("mediumBomber"), 1, new Target(attacked, TargetKind.SURFACE)))
            .cover(british, rebase(london, type("fighter"), 1, carrierZone))
            .run();
    assertThat(runner.problems(), is(empty()));
    assertThat(fighter.getFlightRebase(), is(true));
    assertThat(fighter.getFlightCarrier(), is(carrier));
    // the carrier sails in its owner's turn
    data.performChange(ChangeFactory.moveUnits(carrierZone, moveTo, List.of(carrier)));
    runner.planesHome();
    assertThat(moveTo.getUnitCollection().contains(fighter), is(true));
  }

  @Test
  void rebasesCountAgainstNoCapAndMakeNoFighterPlace() {
    diveBomberInSicily();
    final Map<GamePlayer, List<TargetAssignment>> bombers =
        Map.of(italians, List.of(rebase(sicily, type("diveBomber"), 1, serbia)));
    assertThat(AirStage.bomberPlaces(bombers), is(empty()));
  }
}
