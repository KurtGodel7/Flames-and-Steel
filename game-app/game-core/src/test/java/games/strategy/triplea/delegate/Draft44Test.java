package games.strategy.triplea.delegate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.formatter.MyFormatter;
import games.strategy.triplea.formatter.UnitNames;
import games.strategy.triplea.ui.TerritoryManpower;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel request 5 on the installed map (draft 44 or later): SPC display name and
 * manpower in the territory info. Skipped when the map is not installed.
 */
class Draft44Test {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer russians;
  private GamePlayer germans;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = GameParser.parse(XML, false).orElseThrow();
    russians = data.getPlayerList().getPlayerId("Russians");
    germans = data.getPlayerList().getPlayerId("Germans");
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  @Test
  void manpowerComesFromTheOriginalOwnersEachTriggers() {
    final var kiev = TerritoryManpower.of(territory("Kiev")).orElseThrow();
    assertThat(kiev.amount(), is(2));
    assertThat(kiev.receivers(), is(Map.of(russians, 2)));
    assertThat(kiev.shortText(), is("2 MP"));
    assertThat(TerritoryManpower.of(territory("Moscow")).orElseThrow().amount(), is(3));
    assertThat(TerritoryManpower.of(territory("Minsk")).orElseThrow().amount(), is(1));
  }

  @Test
  void manpowerStillShowsWhenSomeoneElseHoldsTheTerritory() {
    territory("Kiev").setOwner(germans);
    final var kiev = TerritoryManpower.of(territory("Kiev")).orElseThrow();
    assertThat(kiev.amount(), is(2));
    assertThat(kiev.longText(), is("2 Manpower (original owner only: Russians)"));
  }

  @Test
  void seaZonesHaveNoManpower() {
    assertThat(
        TerritoryManpower.of(territory("Sea Zone 86 Baltic Sea - South")).isPresent(), is(false));
  }

  @Test
  void apcIsShownAsSpecialProductionCenter() {
    final UnitType apc = data.getUnitTypeList().getUnitType("apc").orElseThrow();
    assertThat(UnitNames.of(apc), is("Special Production Center"));
    assertThat(UnitNames.shortOf(apc), is("SPC"));
    assertThat(
        MyFormatter.unitsToTextNoOwner(apc.createTemp(2, germans)),
        is("2 Special Production Centers"));
    final UnitType tank = data.getUnitTypeList().getUnitType("tank").orElseThrow();
    assertThat(UnitNames.of(tank), is("tank"));
  }
}
