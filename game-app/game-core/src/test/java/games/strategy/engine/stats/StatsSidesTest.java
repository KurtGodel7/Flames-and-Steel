package games.strategy.engine.stats;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Resource;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ui.mapdata.MapData;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Request 60 on James's round 4 game (copied from his autosave of 6 Nov to
 * TripleA-fork/test-saves/request60_round4.tsvg): totals by side and manpower at 2 PUs.
 */
class StatsSidesTest {
  private static final Path SAVE =
      Path.of(
          System.getProperty("user.home"),
          "Documents/TripleA-fork/test-saves/request60_round4.tsvg");
  private static final String SIDES =
      "Allies:British:Chinese:AmericansPacific:Americans:Dutch:Russians;"
          + "Axis:Germans:Japanese:Italians";
  private GameData data;
  private final MapData mapData = mock(MapData.class);

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(SAVE), "James's round 4 save is not here");
    data = GameDataManager.loadGame(SAVE).orElseThrow();
    when(mapData.shouldDrawUnit(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
  }

  private GamePlayer p(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private int pus(final List<GamePlayer> players) {
    final Resource r = data.getResourceList().getResourceOrThrow(Constants.PUS);
    return players.stream().mapToInt(x -> x.getResources().getQuantity(r)).sum();
  }

  @Test
  void withoutThePropertiesTheTotalsAreTheStockAlliances() {
    assertThat(StatsSides.totalRows(data).keySet(), is(java.util.Set.of("Allies", "Axis")));
    assertThat(StatsSides.totalRows(data).get("Allies").contains(p("Russians")), is(false));
    assertThat(StatsSides.mpValue(data), is(0));
  }

  @Test
  void theAlliesSideCountsTheSovietUnion() {
    data.getProperties().set(StatsSides.SIDES_PROPERTY, SIDES);
    final Map<String, List<GamePlayer>> rows = StatsSides.totalRows(data);
    assertThat(List.copyOf(rows.keySet()), is(List.of("Allies", "Axis")));
    assertThat(rows.get("Allies").size(), is(6));
    final int stockAllies =
        pus(data.getAllianceTracker().getPlayersInAlliance("Allies").stream().toList());
    System.out.println(
        "Allies PUs: stock "
            + stockAllies
            + ", side "
            + pus(rows.get("Allies"))
            + ", Russians "
            + pus(List.of(p("Russians"))));
    assertThat(
        pus(rows.get("Allies")),
        is(stockAllies + pus(List.of(p("Russians"))) + pus(List.of(p("Dutch")))));
  }

  @Test
  void incomePlusManpowerIsProductionPlusTwicetheMpIncome() {
    data.getProperties().set(StatsSides.MP_VALUE_PROPERTY, 2);
    final IStat stat = new StatsSides.IncomeMpStat();
    for (final String n : List.of("Germans", "Russians")) {
      final double production = new ProductionStat().getValue(p(n), data, mapData);
      final int mp = StatsSides.mpIncome(p(n), data);
      System.out.println(
          n
              + ": production "
              + production
              + ", MP income "
              + mp
              + ", Income+MP "
              + stat.getValue(p(n), data, mapData));
      assertThat(stat.getValue(p(n), data, mapData), is(production + 2 * mp));
    }
  }

  @Test
  void tuvAlreadyCountsManpowerAtTwoThroughTheXmlTuvValues() {
    final TuvCostsCalculator costs = new TuvCostsCalculator();
    final GamePlayer russians = p("Russians");
    assertThat(
        costs
            .getCostsForTuv(russians)
            .getInt(data.getUnitTypeList().getUnitTypeOrThrow("conscript")),
        is(2));
    assertThat(
        costs
            .getCostsForTuv(russians)
            .getInt(data.getUnitTypeList().getUnitTypeOrThrow("infantry")),
        is(3));
    System.out.println("Russian TUV " + new TuvStat().getValue(russians, data, mapData));
  }
}
