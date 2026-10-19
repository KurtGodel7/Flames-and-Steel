package games.strategy.triplea.delegate.air;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.delegate.IDelegate;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.delegate.EndTurnDelegate;
import games.strategy.triplea.delegate.InitializationDelegate;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.MoveDelegate;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.data.CasualtyList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel request 17 on the installed map (draft 64): Japan's main fleet sails from Sea
 * Zone 179 to Sea Zone 210 next to Pearl Harbor in rounds 1 and 2, its 12 carrier aircraft sailing
 * with it into the Hawaiian Waters front, and in round 3's air phase its torpedo bombers can strike
 * the US fleet in Sea Zone 225 (1 zone from the carriers). Oahu is 2 steps from the carriers, out
 * of a torpedo bomber's reach of 1 (half of range 3, rounded down) unless Japan has Extended Range.
 * Skipped when the map is not installed or is older than draft 64.
 */
class PearlHarborTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer japanese;
  private GamePlayer americans;
  private Territory hawaiianWaters;
  private Territory philippineSea;
  private Territory sz225;
  private Territory oahu;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = GameParser.parse(XML, false).orElseThrow();
    japanese = player("Japanese");
    americans = player("Americans");
    Assumptions.assumeTrue(
        territory("Sea Zone 179 North Pacific - Southwest 3")
                .getUnitCollection()
                .countMatches(u -> u.getType().getName().equals("carrier"))
            == 6,
        "map is older than draft 64");
    hawaiianWaters = territory("Air - Hawaiian Waters");
    philippineSea = territory("Air - Philippine Sea");
    sz225 = territory("Sea Zone 225 Hawaiian Islands - South");
    oahu = territory("Oahu");
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(newDelegateBridge(japanese));
    start.start();
    start.end();
  }

  @Test
  void pearlHarbor() {
    // 1. round 1: 179 -> 194 -> 209
    final String move1 =
        moveFleet(
            1,
            "Sea Zone 179 North Pacific - Southwest 3",
            "Sea Zone 194 Central Pacific - West 1",
            "Sea Zone 209 Central Pacific - West 2");
    assertThat(move1, move1, is(""));
    // 3. the 12 carrier aircraft sailed with the fleet into Hawaiian Waters (194 is in that front)
    assertThat(japaneseAircraft(hawaiianWaters, "fighter"), is(4));
    assertThat(japaneseAircraft(hawaiianWaters, "torpedoBomber"), is(8));
    assertThat(japaneseAircraft(philippineSea, "fighter"), is(0));
    assertThat(japaneseAircraft(philippineSea, "torpedoBomber"), is(0));

    // 2. round 2: on to 210, next to Pearl Harbor
    final String move2 =
        moveFleet(
            2, "Sea Zone 209 Central Pacific - West 2", "Sea Zone 210 Central Pacific - South");
    assertThat(move2, move2, is(""));
    assertThat(
        territory("Sea Zone 210 Central Pacific - South")
            .getUnitCollection()
            .countMatches(u -> u.isOwnedBy(japanese) && u.getType().getName().equals("carrier")),
        is(6));

    // 4. the war trigger fires after the US end turn of round 2
    usEndTurn(2);
    assertThat(data.getRelationshipTracker().isAtWar(japanese, americans), is(true));

    // round 3 air phase, up to Japan's target step
    runRound3AirPhaseTo("japaneseAirTargets");
    assertThat(japaneseAircraft(hawaiianWaters, "torpedoBomber"), is(8));
    final UnitType torpedo = type("torpedoBomber");
    final List<Target> targets = AirCombat.getTargets(japanese, hawaiianWaters, torpedo);
    assertThat(targets, hasItem(new Target(sz225, TargetKind.SURFACE)));
    // Oahu: 2 steps from the carriers in 210 (210 -> 225 -> Oahu); torpedo bombers reach 1
    assertThat(targets, not(hasItem(new Target(oahu, TargetKind.LAND))));
    assertThat(
        AirRange.landingDistance(japanese, hawaiianWaters, torpedo, oahu, 5).getAsInt(), is(2));
    assertThat(
        AirRange.landingDistance(japanese, hawaiianWaters, torpedo, sz225, 5).getAsInt(), is(1));
    // what the target panel and grid say
    assertThat(
        AirRange.reachText(japanese, hawaiianWaters, torpedo),
        is("Torpedo bombers: 1 zone from your carriers"));
    assertThat(
        AirRange.outOfRangeText(japanese, hawaiianWaters, torpedo, oahu),
        is("Oahu: 2 steps from your nearest carrier; torpedo bombers reach 1"));
    // the grid counts what the picker offers
    final Map<TargetKind, Integer> counts =
        AirCombat.countTargetsByKind(japanese, hawaiianWaters, torpedo);
    assertThat(counts.get(TargetKind.SURFACE) >= 1, is(true));
    // fighters never strike targets: they fight in the dogfight only
    assertThat(
        AirCombat.isBomber(
            hawaiianWaters
                .getUnitCollection()
                .getMatches(u -> u.isOwnedBy(japanese) && u.getType().getName().equals("fighter"))
                .get(0)),
        is(false));

    // with Extended Range (range 4, reach 2) Oahu is in reach
    final TechAdvance extended = data.getTechnologyFrontier().getAdvanceByName("ExtendedRange");
    Assumptions.assumeTrue(extended != null, "no Extended Range tech");
    TechTracker.addAdvance(japanese, newDelegateBridge(japanese), extended);
    data.getTechTracker().clearCache();
    assertThat(AirRange.range(torpedo, japanese), is(4));
    assertThat(
        AirCombat.getTargets(japanese, hawaiianWaters, torpedo),
        hasItem(new Target(oahu, TargetKind.LAND)));
  }

  @Test
  void halfRangeIsRoundedDown() {
    assertThat(AirRange.reach(3), is(1));
    assertThat(AirRange.reach(4), is(2));
    assertThat(AirRange.reach(8), is(4));
  }

  // ---- helpers

  private String moveFleet(final int round, final String... zones) {
    goTo("japaneseCombatMove", round);
    final Territory from = territory(zones[0]);
    final List<Unit> fleet =
        from.getUnitCollection()
            .getMatches(u -> u.isOwnedBy(japanese) && Matches.unitIsSea().test(u));
    final Route route =
        new Route(
            from,
            java.util.Arrays.stream(zones).skip(1).map(this::territory).toArray(Territory[]::new));
    final IDelegateBridge bridge = bridge(japanese);
    final MoveDelegate move = (MoveDelegate) data.getDelegate("move");
    move.setDelegateBridgeAndPlayer(bridge);
    move.start();
    final var error = move.performMove(new MoveDescription(fleet, route));
    move.end();
    // the non-combat move step ends the turn's moves; the next turn's combat move resets movement
    goTo("japaneseNonCombatMove", round);
    move.setDelegateBridgeAndPlayer(bridge(japanese));
    move.start();
    move.end();
    return error.orElse("");
  }

  private void usEndTurn(final int round) {
    goTo("americanEndTurn", round);
    final var endTurn = (EndTurnDelegate) data.getDelegate("endTurn");
    endTurn.setDelegateBridgeAndPlayer(bridge(americans));
    endTurn.start();
    endTurn.end();
  }

  /** Runs every step of the next round's start (carrier check, allocation, dogfights) in turn. */
  private void runRound3AirPhaseTo(final String stepName) {
    data.getSequence().next();
    for (int i = 0; i < 200; i++) {
      final var step = data.getSequence().getStep();
      if (step.getName().equals(stepName)) {
        assertThat(data.getSequence().getRound(), is(3));
        return;
      }
      final GamePlayer p = step.getPlayerId() == null ? japanese : step.getPlayerId();
      final IDelegate delegate = step.getDelegate();
      delegate.setDelegateBridgeAndPlayer(bridge(p));
      delegate.start();
      delegate.end();
      data.getSequence().next();
    }
    throw new IllegalStateException("no step " + stepName);
  }

  private void goTo(final String step, final int round) {
    final var sequence = data.getSequence();
    for (int i = 0; i < 10_000; i++) {
      if (sequence.getRound() == round && sequence.getStep().getName().equals(step)) {
        return;
      }
      sequence.next();
    }
    throw new IllegalStateException("no step " + step + " in round " + round);
  }

  private int japaneseAircraft(final Territory front, final String type) {
    return front
        .getUnitCollection()
        .countMatches(u -> u.isOwnedBy(japanese) && u.getType().getName().equals(type));
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  private UnitType type(final String name) {
    return data.getUnitTypeList().getUnitTypeOrThrow(name);
  }

  /** Dice miss, casualties are the defaults, fleet aircraft go as suggested. */
  private static IDelegateBridge bridge(final GamePlayer player) {
    final IDelegateBridge bridge = newDelegateBridge(player);
    when(bridge.getRandom(anyInt(), any(), any(), anyString())).thenReturn(5);
    when(bridge.getRandom(anyInt(), anyInt(), any(), any(), anyString()))
        .thenAnswer(i -> new int[i.<Integer>getArgument(1)]);
    when(bridge.getRemotePlayer().selectFleetAircraft(any(), any(), anyString()))
        .thenAnswer(i -> i.getArgument(1));
    when(bridge
            .getRemotePlayer()
            .selectCasualties(
                any(),
                any(),
                anyInt(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean()))
        .thenAnswer(
            invocation -> new CasualtyDetails(invocation.<CasualtyList>getArgument(10), true));
    return bridge;
  }
}
