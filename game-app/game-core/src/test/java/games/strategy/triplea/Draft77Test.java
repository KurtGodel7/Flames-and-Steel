package games.strategy.triplea;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.UnitType;
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
    data = games.strategy.triplea.delegate.air.BasedMap.load();
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
            Map.entry("heavyTank", 20),
            Map.entry("panther", 20),
            Map.entry("pershingTank", 20),
            Map.entry("churchillTank", 15),
            Map.entry("kingTiger", 30),
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
    // draft 90 (request 50): every SPC unit comes one every 2 turns, the Superfortress every 4
    for (final String unit :
        java.util.List.of(
            "heavyTank",
            "churchillTank",
            "pershingTank",
            "panther",
            "kingTiger",
            "earlyJet",
            "jet",
            "fleetCarrier",
            "typeXXI",
            "flyingWing")) {
      assertThat(unit, Apcs.describeRate(type(unit)), is("1 every 2 turns"));
    }
    assertThat(Apcs.describeRate(type("superfortress")), is("1 every 4 turns"));
  }

  @Test
  void sovietPilotTrainingNeedsTwoStarsAndRadiosOne() {
    // request 35: Modern Fighters and Radios (2 stars) became Pilot Training (2) then Radios (1)
    final GamePlayer russians = data.getPlayerList().getPlayerId("Russians");
    for (final var expected : java.util.Map.of("PilotTraining", 2, "Radios", 1).entrySet()) {
      final games.strategy.triplea.delegate.TechAdvance tech =
          data.getTechnologyFrontier().getAdvanceByName(expected.getKey());
      assertThat(
          games.strategy.triplea.delegate.research.ResearchCenters.getStarsRequired(russians, tech),
          is(expected.getValue()));
    }
  }

  @Test
  void theRaidPropertiesAreGone() {
    // request 38: flying wings fly to targets in range like any bomber
    assertThat(data.getProperties().get("FS Long Range Raid Units", ""), is(""));
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
