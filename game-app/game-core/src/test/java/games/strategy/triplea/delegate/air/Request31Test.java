package games.strategy.triplea.delegate.air;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.TechnologyFrontier;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.delegate.InitializationDelegate;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.delegate.research.ResearchCenters;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Request 31 part A on the installed map: Window, the bombing techs stacking per nation, the heavy
 * fighter from XML alone, and the new research categories.
 */
class Request31Test {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer british;
  private GamePlayer americans;
  private GamePlayer germans;
  private GamePlayer japanese;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = GameParser.parse(XML, false).orElseThrow();
    british = data.getPlayerList().getPlayerId("British");
    americans = data.getPlayerList().getPlayerId("Americans");
    germans = data.getPlayerList().getPlayerId("Germans");
    japanese = data.getPlayerList().getPlayerId("Japanese");
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(newDelegateBridge(germans));
    start.start();
    start.end();
  }

  private UnitType type(final String name) {
    return data.getUnitTypeList().getUnitTypeOrThrow(name);
  }

  private Unit unit(final String type, final GamePlayer owner) {
    return type(type).create(1, owner).get(0);
  }

  private TechAdvance tech(final String name) {
    return data.getTechnologyFrontier().getAdvanceByName(name);
  }

  private void grant(final GamePlayer player, final String... techs) {
    for (final String name : techs) {
      TechTracker.addAdvance(player, newDelegateBridge(player), tech(name));
    }
    data.getTechTracker().clearCache();
  }

  /** Gives Window the effect request 31 asks the XML for. */
  private void windowOption() throws Exception {
    TechAbilityAttachment.get(tech("Window"))
        .getPropertyOrThrow("interceptionPenaltyAgainst")
        .setValue("1:strategicBomber");
    data.getTechTracker().clearCache();
  }

  @Test
  void windowTakesOneOffInterceptorsAgainstARaidWithABritishStrategicBomber() throws Exception {
    windowOption();
    final Unit heavy = unit("heavyFighter", germans);
    final List<Unit> raid =
        List.of(unit("strategicBomber", british), unit("strategicBomber", americans));
    assertThat(AirCombat.interceptionPower(heavy, AirCombat.interceptionPenalty(raid)), is(4));

    grant(british, "Window");
    assertThat(AirCombat.interceptionPenalty(raid), is(1));
    // American bombers in the same raid benefit
    assertThat(AirCombat.interceptionPower(heavy, AirCombat.interceptionPenalty(raid)), is(3));
    // a raid without a British strategic bomber does not
    assertThat(
        AirCombat.interceptionPenalty(List.of(unit("strategicBomber", americans))), is(0));
    // Airborne Intercept Radar (+1) and Window cancel exactly
    grant(germans, "AirborneInterceptRadar");
    assertThat(AirCombat.interceptionPower(heavy, AirCombat.interceptionPenalty(raid)), is(4));
    // never below 1
    assertThat(AirCombat.interceptionPower(unit("fighter", germans), 5), is(1));
    // the dogfight is untouched
    assertThat(AirCombat.dogfightValue(heavy), is(2));
  }

  @Test
  void britishBombingTechsStackForBritainOnly() {
    final Unit lancaster = unit("strategicBomber", british);
    final Unit german = unit("strategicBomber", germans);
    assertThat(AirCombat.bombingDamage(lancaster), is(2));
    grant(british, "Gee", "Lancaster", "H2SAndOboe", "TallboyAndGrandSlam");
    assertThat(AirCombat.bombingDamage(lancaster), is(5));
    assertThat(Unit.maxHitPoints(lancaster), is(3));
    assertThat(AirCombat.airDefenseValue(lancaster), is(1));
    assertThat(AirCombat.bombingDamage(german), is(2));
    assertThat(Unit.maxHitPoints(german), is(2));
  }

  @Test
  void americanBombingTechsStack() {
    final Unit fortress = unit("strategicBomber", americans);
    grant(americans, "NordenBombsight", "B17FlyingFortress", "CombatBox", "H2XRadar");
    assertThat(AirCombat.bombingDamage(fortress), is(4));
    assertThat(Unit.maxHitPoints(fortress), is(3));
    assertThat(AirCombat.airDefenseValue(fortress), is(3));
  }

  @Test
  void axisUpgrades() {
    grant(japanese, "G8NRenzan");
    grant(germans, "He177Greif");
    final Unit renzan = unit("strategicBomber", japanese);
    final Unit greif = unit("strategicBomber", germans);
    assertThat(List.of(AirCombat.bombingDamage(renzan), Unit.maxHitPoints(renzan)), is(List.of(3, 3)));
    assertThat(AirCombat.airDefenseValue(renzan), is(2));
    assertThat(List.of(AirCombat.bombingDamage(greif), Unit.maxHitPoints(greif)), is(List.of(3, 3)));
    assertThat(AirCombat.airDefenseValue(greif), is(1));
  }

  @Test
  void mustangMovementIsAmericanOnly() {
    final int before = AirRange.range(type("advancedFighter"), americans);
    grant(americans, "P51Mustang");
    assertThat(AirRange.range(type("advancedFighter"), americans), is(before + 2));
    assertThat(AirRange.range(type("advancedFighter"), british), is(before));
  }

  @Test
  void theHeavyFighterIsAnOrdinaryFighterTypeFromXmlAlone() {
    final Unit heavy = unit("heavyFighter", germans);
    assertThat(AirCombat.isFighterType(heavy), is(true));
    assertThat(AirCombat.isBomber(heavy), is(false));
    assertThat(AirCombat.interceptionValue(heavy), is(4));
    assertThat(type("heavyFighter").getUnitAttachment().getCarrierCost() > 0, is(false));
    final Territory bombGermany = data.getMap().getTerritoryOrThrow("Air - Bomb Germany");
    // defends against strategic bombing
    assertThat(
        AirAllocation.entryProblem(germans, type("heavyFighter"), bombGermany).isPresent(),
        is(false));
    // may cover places in its front (range 6: three steps)
    final Territory westernEurope = data.getMap().getTerritoryOrThrow("Air - Western Europe");
    assertThat(
        AirCover.places(germans, westernEurope, type("heavyFighter"), Map.of()).isEmpty(),
        is(false));
    // and never uses a carrier: an ocean front's carrier load rejects it
    final Territory ocean =
        AirZoneAttachment.getAirZones(data.getMap()).stream()
            .filter(
                z ->
                    AirZoneAttachment.get(z).orElseThrow().getZoneType()
                        == AirZoneAttachment.ZoneType.OCEAN)
            .findFirst()
            .orElseThrow();
    assertThat(
        AirAllocation.carrierLoad(germans, ocean, List.of(type("heavyFighter"))).unfit(),
        is(type("heavyFighter")));
  }

  @Test
  void theNewBritishCategoryIsResearchedInOrderFromXmlAlone() {
    final TechnologyFrontier category =
        ResearchCenters.getCategories(british).stream()
            .filter(c -> c.getTechs().contains(tech("Gee")))
            .findFirst()
            .orElseThrow();
    final List<String> names = new ArrayList<>();
    final List<Integer> stars = new ArrayList<>();
    for (final TechAdvance t : category.getTechs()) {
      names.add(t.getName());
      stars.add(ResearchCenters.getStarsRequired(british, t));
    }
    assertThat(
        names, is(List.of("Gee", "Lancaster", "H2SAndOboe", "Window", "TallboyAndGrandSlam")));
    assertThat(stars, is(List.of(2, 1, 1, 2, 1)));
    assertThat(ResearchCenters.getNextTech(british, category).orElseThrow(), is(tech("Gee")));
    grant(british, "Gee");
    assertThat(ResearchCenters.getNextTech(british, category).orElseThrow(), is(tech("Lancaster")));
    grant(british, "Lancaster", "H2SAndOboe", "Window", "TallboyAndGrandSlam");
    assertThat(ResearchCenters.getUnfinishedCategories(british).contains(category), is(false));
  }

  @Test
  void everyCategoryIsResearchedInTheOrderTheXmlListsIt() throws Exception {
    final String xml = Files.readString(XML);
    final var players =
        java.util.regex.Pattern.compile(
                "<playerTech player=\"([^\"]+)\">(.*?)</playerTech>", java.util.regex.Pattern.DOTALL)
            .matcher(xml);
    int checked = 0;
    while (players.find()) {
      final GamePlayer player = data.getPlayerList().getPlayerId(players.group(1));
      final var categories =
          java.util.regex.Pattern.compile(
                  "<category name=\"([^\"]+)\">(.*?)</category>", java.util.regex.Pattern.DOTALL)
              .matcher(players.group(2));
      while (categories.find()) {
        final List<String> listed = new ArrayList<>();
        final var techs =
            java.util.regex.Pattern.compile("<tech name=\"([^\"]+)\"").matcher(categories.group(2));
        while (techs.find()) {
          listed.add(techs.group(1));
        }
        final List<String> engine =
            ResearchCenters.getCategory(player, categories.group(1)).orElseThrow().getTechs().stream()
                .map(TechAdvance::getName)
                .toList();
        assertThat(player.getName() + " " + categories.group(1), engine, is(listed));
        checked++;
      }
    }
    assertThat(checked > 20, is(true));
  }

  @Test
  void theAmericanCategoryEndsWithSuperfortress() {
    final TechnologyFrontier category =
        ResearchCenters.getCategories(americans).stream()
            .filter(c -> c.getTechs().contains(tech("NordenBombsight")))
            .findFirst()
            .orElseThrow();
    final List<String> names = category.getTechs().stream().map(TechAdvance::getName).toList();
    assertThat(names.get(names.size() - 1), is("Superfortress"));
    assertThat(ResearchCenters.getStarsRequired(americans, tech("Superfortress")), is(2));
  }
}
