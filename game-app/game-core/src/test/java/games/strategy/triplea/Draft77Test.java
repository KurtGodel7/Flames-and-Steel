package games.strategy.triplea;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.delegate.apc.Apcs;
import games.strategy.triplea.util.TuvCostsCalculator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel request 25: the installed map (draft 77 or later) loads, and the fork reads its
 * new values. Skipped when the map is not installed.
 */
class Draft77Test {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = GameParser.parse(XML, false).orElseThrow();
  }

  private UnitType type(final String name) {
    return data.getUnitTypeList().getUnitType(name).orElseThrow();
  }

  @Test
  void battleCalculatorAndBattleTuvUseTheXmlValues() {
    final Map<String, Integer> expected =
        Map.ofEntries(
            Map.entry("conscript", 2),
            Map.entry("infantry", 3),
            Map.entry("sturmtruppen", 5),
            Map.entry("mobileInfantry", 5),
            Map.entry("mechInfantry", 7),
            Map.entry("heavyTank", 15),
            Map.entry("panther", 15),
            Map.entry("pershingTank", 15),
            Map.entry("churchillTank", 13),
            Map.entry("kingTiger", 25),
            Map.entry("earlyJet", 14),
            Map.entry("jet", 20),
            Map.entry("superfortress", 45),
            Map.entry("flyingWing", 25),
            Map.entry("typeXXI", 23),
            Map.entry("fleetCarrier", 28));
    // the calculator, the battle window and the AI all take their values from here
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      final IntegerMap<UnitType> tuv = new TuvCostsCalculator().getCostsForTuv(player);
      expected.forEach(
          (name, value) ->
              assertThat(player.getName() + " " + name, tuv.getInt(type(name)), is(value)));
    }
  }

  @Test
  void infantryStillCostsOnePuAndOneManpower() {
    final ProductionRule rule = data.getProductionRuleList().getProductionRule("buy_infantry");
    assertThat(rule.getCosts().getInt(data.getResourceList().getResourceOrThrow("PUs")), is(1));
    assertThat(
        rule.getCosts().getInt(data.getResourceList().getResourceOrThrow("Manpower")), is(1));
  }

  @Test
  void spcRates() {
    assertThat(Apcs.describeRate(type("typeXXI")), is("1 per turn"));
    assertThat(Apcs.describeRate(type("fleetCarrier")), is("1 every 2 turns"));
    assertThat(Apcs.describeRate(type("superfortress")), is("1 every 2 turns"));
    // later draft 77: the flying wing comes one per turn
    assertThat(Apcs.describeRate(type("flyingWing")), is("1 per turn"));
  }

  @Test
  void sovietModernFightersAndRadiosNeedsTwoStars() {
    final GamePlayer russians = data.getPlayerList().getPlayerId("Russians");
    final games.strategy.triplea.delegate.TechAdvance tech =
        data.getTechnologyFrontier().getTechs().stream()
            .filter(t -> t.getName().equals("ModernFightersAndRadios"))
            .findFirst()
            .orElseThrow();
    assertThat(
        games.strategy.triplea.delegate.research.ResearchCenters.getStarsRequired(russians, tech),
        is(2));
  }

  @Test
  void longRangeRaidPropertiesAreSet() {
    assertThat(data.getProperties().get("FS Long Range Raid Units", ""), is("flyingWing"));
    assertThat(data.getProperties().get("FS Long Range Raid To", ""), is("Air - Bomb America Atlantic"));
  }

  @Test
  void shoreBombardmentAndAaGuns() {
    assertThat(Properties.getShoreBombardPerGroundUnitRestricted(data.getProperties()), is(true));
    assertThat(
        games.strategy.triplea.delegate.Matches.unitTypeCanNotMoveDuringCombatMove()
            .test(type("aaGun")),
        is(true));
  }
}
