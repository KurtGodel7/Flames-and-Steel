package games.strategy.triplea.delegate.air;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.history.IDelegateHistoryWriter;
import games.strategy.triplea.delegate.AirCombatDelegate;
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
  private Territory bombGermany;
  private Territory hamburg;
  private AirCombatDelegate delegate;
  private final List<String> history = new ArrayList<>();
  private final List<String> summaries = new ArrayList<>();

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = GameParser.parse(XML, false).orElseThrow();
    british = data.getPlayerList().getPlayerId("British");
    germans = data.getPlayerList().getPlayerId("Germans");
    bombGermany = data.getMap().getTerritoryOrThrow("Air - Bomb Germany");
    hamburg = data.getMap().getTerritoryOrThrow("Hamburg");
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(newDelegateBridge(germans));
    start.start();
    start.end();
    removeFrom(bombGermany, bombGermany.getUnits());
    // no AA in the way: the raid itself is what is tested
    removeFrom(hamburg, hamburg.getUnitCollection().getMatches(Matches.unitIsAaForAnything()));
    delegate = (AirCombatDelegate) data.getDelegate("airCombat");
  }

  private int pus(final GamePlayer p) {
    return p.getResources().getQuantity("PUs");
  }

  private void raid(final String type, final int count) {
    addTo(bombGermany, data.getUnitTypeList().getUnitTypeOrThrow(type).create(count, british));
    final IDelegateBridge targets = bridge(british);
    advanceToStep(targets, "britishAirTargets");
    delegate.setDelegateBridgeAndPlayer(targets);
    delegate.start();
    assertThat(
        delegate.assignTargets(
            List.of(
                new TargetAssignment(
                    bombGermany,
                    data.getUnitTypeList().getUnitTypeOrThrow(type),
                    count,
                    new Target(hamburg, TargetKind.STRATEGIC)))),
        is(nullValue()));
    delegate.end();
    final IDelegateBridge strikes = bridge(british);
    advanceToStep(strikes, "airStrikes");
    delegate.setDelegateBridgeAndPlayer(strikes);
    delegate.start();
    delegate.end();
    // request 30: no battle window; one summary of the air phase for everyone
    final ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
    verify(strikes.getDisplayChannelBroadcaster(), atLeastOnce())
        .reportMessageToAll(
            summary.capture(), eq("Air battles this round"), eq(false), eq(false), eq(false));
    summaries.addAll(summary.getAllValues());
  }

  @Test
  void aStrategicBomberTakesTwoPusFromGermany() {
    final int before = pus(germans);

    raid("strategicBomber", 1);

    assertThat(pus(germans), is(before - 2));
    assertThat(germans.getTechAttachment().getBombedThisRound(), is(2));
    assertThat(
        summaries.get(0),
        containsString("Strategic bombing of Hamburg: Germans lose 2 PUs"));
    assertThat(summaries.get(0), containsString("British bombed Hamburg: Germans −2 PUs"));
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
        summaries.get(0),
        containsString("12 PUs of damage, capped at 10: 2 × the territory's value"));
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
