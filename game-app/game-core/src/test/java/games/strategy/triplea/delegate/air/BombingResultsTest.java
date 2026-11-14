package games.strategy.triplea.delegate.air;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.history.IDelegateHistoryWriter;
import games.strategy.triplea.delegate.AirCombatDelegate;
import games.strategy.triplea.delegate.AirStageRunner;
import games.strategy.triplea.delegate.InitializationDelegate;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.air.AirCombat.Roll;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.data.CasualtyList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Flames and Steel request 15 on the installed map: a strategic bombing raid takes the bombers'
 * damage off the owner's PUs (at most twice the territory's value), and says so in the battle
 * window, the history, the round's summary and beside the nation's PUs. Skipped when the map is not
 * installed.
 */
class BombingResultsTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer british;
  private GamePlayer germans;
  private Territory london;
  private Territory hamburg;
  private AirCombatDelegate delegate;
  private final List<String> history = new ArrayList<>();
  private final List<AirBattleReport> summaries = new ArrayList<>();

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = games.strategy.triplea.delegate.air.BasedMap.load();
    british = data.getPlayerList().getPlayerId("British");
    germans = data.getPlayerList().getPlayerId("Germans");
    london = data.getMap().getTerritoryOrThrow("London");
    hamburg = data.getMap().getTerritoryOrThrow("Hamburg");
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(newDelegateBridge(germans));
    start.start();
    start.end();
    // no AA in the way: the raid itself is what is tested
    removeFrom(hamburg, hamburg.getUnitCollection().getMatches(Matches.unitIsAaForAnything()));
    delegate = (AirCombatDelegate) data.getDelegate("airCombat");
  }

  private int pus(final GamePlayer p) {
    return p.getResources().getQuantity("PUs");
  }

  private void raid(final String type, final int count) {
    addTo(london, data.getUnitTypeList().getUnitTypeOrThrow(type).create(count, british));
    // request 35: the target is chosen in the simultaneous bomber stage
    assertThat(
        new AirStageRunner(data)
            .bombers(
                british,
                new TargetAssignment(
                    london,
                    data.getUnitTypeList().getUnitTypeOrThrow(type),
                    count,
                    new Target(hamburg, TargetKind.STRATEGIC)))
            .run()
            .problems(),
        is(empty()));
    final IDelegateBridge strikes = bridge(british);
    advanceToStep(strikes, "airStrikes");
    delegate.setDelegateBridgeAndPlayer(strikes);
    delegate.start();
    delegate.end();
    // request 30: no battle window; one summary of the air phase for everyone (request 46: as
    // pictures)
    final ArgumentCaptor<AirBattleReport> summary = ArgumentCaptor.forClass(AirBattleReport.class);
    verify(strikes.getDisplayChannelBroadcaster(), atLeastOnce()).showAirBattles(summary.capture());
    summaries.addAll(summary.getAllValues());
  }

  private AirBattleReport.Row hamburgRow() {
    return summaries.get(0).rows().stream()
        .filter(r -> r.place().equals("Hamburg"))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void aStrategicBomberTakesTwoPusFromGermany() {
    final int before = pus(germans);

    raid("strategicBomber", 1);

    assertThat(pus(germans), is(before - 2));
    assertThat(germans.getTechAttachment().getBombedThisRound(), is(2));
    assertThat(
        hamburgRow().defenders().stream()
            .filter(s -> s.unitType().equals("factory"))
            .findFirst()
            .orElseThrow()
            .bombed(),
        is(2));
    assertThat(summaries.get(0).bombingTotals().get("Germans"), is(2));
    assertThat(
        history,
        hasItem(
            "British bombed Hamburg: Germans −2 PUs (2 damage, raid cap 10; "
                + before
                + " → "
                + (before - 2)
                + " PUs)"));
    assertThat(history, hasItem("Total: Germans −2 PUs from bombing this round"));
  }

  @Test
  void aMediumBomberTakesOne() {
    final int before = pus(germans);

    raid("mediumBomber", 1);

    assertThat(pus(germans), is(before - 1));
  }

  @Test
  void theCapIsTwiceTheTerritorysValueAndTheWindowSaysSo() {
    final int before = pus(germans);

    // request 30 allows 4 bombers per factory; raise the cap to pass Hamburg's limit
    data.getProperties().set(AirCover.CAP_BOMBERS, 6);
    raid("strategicBomber", 6); // request 31 values: 6 x 2 = 12 damage, Hamburg is worth 5

    assertThat(pus(germans), is(before - 10));
    assertThat(
        hamburgRow().notes(),
        hasItem("Bombing: 12 PU of damage, capped at 10 (twice the territory's value)"));
  }

  @Test
  void theNextRoundsStrikeStepClearsLastRoundsFigure() {
    raid("strategicBomber", 1);
    assertThat(germans.getTechAttachment().getBombedThisRound(), is(2));

    final IDelegateBridge strikes = bridge(british);
    data.getSequence().next();
    advanceToStep(strikes, "airStrikes");
    delegate.setDelegateBridgeAndPlayer(strikes);
    delegate.start();
    delegate.end();

    assertThat(germans.getTechAttachment().getBombedThisRound(), is(0));
  }

  @Test
  void rollsInPlainWords() {
    assertThat(
        AirCombat.describeRoll("AA guns", new Roll(0, new int[] {3}, 2), 6),
        is("AA guns fire at 2: rolled 4, miss"));
    assertThat(
        AirCombat.describeRoll("AA guns", new Roll(1, new int[] {0}, 2), 6),
        is("AA guns fire at 2: rolled 1, hit"));
    assertThat(
        AirCombat.describeRoll("Germans", new Roll(2, new int[0], 12), 6),
        is("Germans fire at 12: 2 sure hits"));
    assertThat(
        AirCombat.describeRoll("Germans", new Roll(2, new int[] {1}, 9), 6),
        is("Germans fire at 9: 1 sure hit, then rolled 2 at 3, hit: 2 hits"));
  }

  /** Remainder dice always miss; history lines are kept; players take offered casualties. */
  private IDelegateBridge bridge(final GamePlayer player) {
    final IDelegateBridge bridge = newDelegateBridge(player);
    final IDelegateHistoryWriter writer = mock(IDelegateHistoryWriter.class);
    org.mockito.Mockito.doAnswer(
            i -> {
              history.add(i.getArgument(0));
              return null;
            })
        .when(writer)
        .addChildToEvent(anyString());
    when(bridge.getHistoryWriter()).thenReturn(writer);
    when(bridge.getRandom(anyInt(), any(), any(), anyString())).thenReturn(5);
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
