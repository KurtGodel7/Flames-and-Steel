package games.strategy.triplea.ui;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.engine.stats.StatsSides;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.table.TableModel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Request 60: the Players and Resources tabs on James's round 4 game. */
class StatsTabsRequest60Test {
  private static final Path SAVE =
      Path.of(
          System.getProperty("user.home"),
          "Documents/TripleA-fork/test-saves/request60_round4.tsvg");
  private GameData data;
  private UiContext ui;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(SAVE), "James's round 4 save is not here");
    data = GameDataManager.loadGame(SAVE).orElseThrow();
    ui = mock(UiContext.class, RETURNS_DEEP_STUBS);
    when(ui.getMapData().shouldDrawUnit(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
  }

  private static List<String> rows(final TableModel m) {
    final List<String> out = new ArrayList<>();
    final StringBuilder head = new StringBuilder();
    for (int c = 0; c < m.getColumnCount(); c++) {
      head.append(m.getColumnName(c)).append(" | ");
    }
    out.add(head.toString());
    for (int r = 0; r < m.getRowCount(); r++) {
      final StringBuilder b = new StringBuilder();
      for (int c = 0; c < m.getColumnCount(); c++) {
        b.append(m.getValueAt(r, c)).append(" | ");
      }
      out.add(b.toString().replaceAll("<[^>]*>", "").replace("&nbsp;", " "));
    }
    return out;
  }

  private void setProperties() {
    data.getProperties()
        .set(
            StatsSides.SIDES_PROPERTY,
            "Allies:British:Chinese:AmericansPacific:Americans:Dutch:Russians;"
                + "Axis:Germans:Japanese:Italians");
    data.getProperties().set(StatsSides.MP_VALUE_PROPERTY, 2);
  }

  @Test
  void withThePropertiesBothTabsShowSidesAndManpower() {
    setProperties();
    final List<String> players = rows(new StatPanel(data, ui).dataModel());
    final List<String> resources = rows(new EconomyPanel(data, ui).model());
    players.forEach(System.out::println);
    resources.forEach(System.out::println);
    assertThat(players.get(0), containsString("TUV | Income+MP"));
    final String allies =
        resources.stream().filter(r -> r.startsWith("Allies")).findFirst().orElseThrow();
    assertThat(allies, containsString("456 (+443)"));
    assertThat(allies, containsString("48 (+48)"));
    assertThat(players.stream().anyMatch(r -> r.startsWith("Soviets")), is(false));
  }

  @Test
  void withoutThemBothTabsAreStock() {
    final List<String> players = rows(new StatPanel(data, ui).dataModel());
    final List<String> resources = rows(new EconomyPanel(data, ui).model());
    assertThat(players.get(0).contains("Income+MP"), is(false));
    assertThat(new EconomyPanel(data, ui).model().getColumnCount(), is(3));
    assertThat(
        players.stream().filter(r -> r.startsWith("Allies") || r.startsWith("Axis")).count(),
        is(2L));
  }
}
