package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.history.IDelegateHistoryWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel request 19 on the installed map (draft 75 or later): the US mobilization
 * schedule. Two copies of the game play the US end of turn for rounds 1 to 9, one with the schedule
 * and one without; the first collects the round's share of the second's PUs, rounded down. Skipped
 * when the map is not installed or has no schedule.
 */
class Draft75Test {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private static final int[] PERCENT = {0, 0, 0, 50, 60, 70, 80, 90, 100, 100};
  private GameData mobilizing;
  private GameData full;
  private final List<String> history = new ArrayList<>();

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    mobilizing = load();
    Assumptions.assumeTrue(
        Mobilization.schedule(us(mobilizing)).isPresent(), "map has no US mobilization");
    full = load();
    full.getProperties().set(Mobilization.PROPERTY + "Americans", "");
  }

  private static GameData load() {
    final GameData data = GameParser.parse(XML, false).orElseThrow();
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(
        newDelegateBridge(data.getPlayerList().getPlayerId("Germans")));
    start.start();
    start.end();
    return data;
  }

  private static GamePlayer us(final GameData data) {
    return data.getPlayerList().getPlayerId("Americans");
  }

  @Test
  void usIncomeFollowsTheSchedule() {
    final List<String> table = new ArrayList<>();
    for (int round = 1; round <= 9; round++) {
      if (round == 4) {
        // a PU bonus tech (Wartime Production: +1 PU per factory, by trigger) is scaled too
        grant(mobilizing, "WartimeProduction");
        grant(full, "WartimeProduction");
      }
      // the share as the US collects (the war trigger fires at the end of its round 2 turn)
      final int percentBefore = Mobilization.percent(us(mobilizing), round);
      final int fullPus = usEndTurn(full, round, false);
      final int pus = usEndTurn(mobilizing, round, true);
      table.add("round " + round + ": " + pus + " of " + fullPus);
      if (round <= 2) {
        assertThat("round " + round + " is peacetime", pus, is(36));
        assertThat(fullPus, is(36));
        assertThat(percentBefore, is(100));
      } else {
        assertThat("round " + round, pus, is(fullPus * PERCENT[round] / 100));
        assertThat(percentBefore, is(PERCENT[round]));
      }
      if (round == 4) {
        assertThat("Wartime Production adds PUs", fullPus, greaterThan(fullPusRound3));
      }
      if (round == 3) {
        fullPusRound3 = fullPus;
      }
    }
    System.out.println(String.join("\n", table));
    assertThat(
        history.stream()
            .anyMatch(
                h ->
                    h.startsWith("Americans collect ") && h.contains(" PUs (mobilization 60% of ")),
        is(true));
    assertThat(
        Mobilization.statusText(us(mobilizing), 4).orElseThrow(),
        is("Americans: mobilization 60% (full income from round 8)"));
    assertThat(
        Mobilization.scheduleText(us(mobilizing)),
        is(
            "Round 3: 50%, round 4: 60%, round 5: 70%, round 6: 80%, round 7: 90%,"
                + " round 8 onward: 100%"));
  }

  private int fullPusRound3;

  @Test
  void scheduleEdges() {
    final var schedule = new java.util.TreeMap<Integer, Integer>();
    schedule.put(3, 50);
    schedule.put(5, 70);
    assertThat(Mobilization.percent(schedule, 1), is(50)); // before the first: the first share
    assertThat(Mobilization.percent(schedule, 4), is(50)); // a gap: the last listed before it
    assertThat(Mobilization.percent(schedule, 5), is(70));
    assertThat(Mobilization.percent(schedule, 6), is(100)); // after the list: full
    assertThat(Mobilization.fullIncomeRound(schedule), is(6));
    assertThat(Mobilization.scale(227, 60), is(136));
  }

  private void grant(final GameData data, final String tech) {
    final GamePlayer us = us(data);
    TechTracker.addAdvance(
        us, newDelegateBridge(us), data.getTechnologyFrontier().getAdvanceByName(tech));
    data.getTechTracker().clearCache();
  }

  /** The PUs the US collects at its end of turn in this round. */
  private int usEndTurn(final GameData data, final int round, final boolean keepHistory) {
    final var sequence = data.getSequence();
    for (int i = 0; i < 10_000; i++) {
      if (sequence.getRound() == round && sequence.getStep().getName().equals("americanEndTurn")) {
        break;
      }
      sequence.next();
    }
    final GamePlayer us = us(data);
    final var pus = data.getResourceList().getResourceOrThrow("PUs");
    final int before = us.getResources().getQuantity(pus);
    final IDelegateBridge bridge = newDelegateBridge(us);
    when(bridge.getRandom(anyInt(), anyInt(), any(), any(), anyString()))
        .thenAnswer(i -> new int[i.<Integer>getArgument(1)]);
    if (keepHistory) {
      final IDelegateHistoryWriter writer = org.mockito.Mockito.mock(IDelegateHistoryWriter.class);
      org.mockito.Mockito.doAnswer(
              i -> {
                history.add(i.getArgument(0));
                return null;
              })
          .when(writer)
          .startEvent(anyString());
      when(bridge.getHistoryWriter()).thenReturn(writer);
    }
    final var endTurn = (EndTurnDelegate) data.getDelegate("endTurn");
    endTurn.setDelegateBridgeAndPlayer(bridge);
    endTurn.start();
    final int gained = us.getResources().getQuantity(pus) - before;
    endTurn.end();
    sequence.next();
    return gained;
  }
}
