package games.strategy.triplea.delegate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.delegate.air.AirHome;
import games.strategy.triplea.delegate.air.AirStage.Landing;
import games.strategy.triplea.delegate.air.AirValues;
import games.strategy.triplea.delegate.air.BasedMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Request 53 on the installed map: Edit mode's units, Planes Go Home, aircraft values. */
class Request53Test {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer japanese;
  private GamePlayer russians;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = BasedMap.load();
    japanese = BasedMap.p(data, "Japanese");
    russians = BasedMap.p(data, "Russians");
  }

  // ---- item 1: Edit mode offers only units that make sense

  private List<String> offered(final GamePlayer player, final Territory t) {
    return NationUnits.of(player).keySet().stream()
        .filter(type -> NationUnits.fits(type, t))
        .map(UnitType::getName)
        .toList();
  }

  @Test
  void japaneseLandOffersNoShipsAndNoOtherNationsSpecialUnits() {
    final List<String> units = offered(japanese, BasedMap.t(data, "Tokyo"));
    for (final String never :
        List.of(
            "battleship",
            "carrier",
            "typeXXI",
            "sturmtruppen",
            "panther",
            "churchillTank",
            "flyingWing",
            "pershingTank")) {
      assertThat(never, units, not(hasItem(never)));
    }
    assertThat(units, hasItem("infantry"));
    assertThat(units, hasItem("fighter"));
  }

  @Test
  void seaZonesOfferShipsAndCarrierAircraftOnly() {
    final Territory sea =
        data.getMap().getNeighbors(BasedMap.t(data, "Tokyo")).stream()
            .filter(Territory::isWater)
            .findFirst()
            .orElseThrow();
    final List<String> units = offered(japanese, sea);
    assertThat(units, hasItem("destroyer"));
    assertThat(units, not(hasItem("infantry")));
    for (final String name : units) {
      final var ua = data.getUnitTypeList().getUnitTypeOrThrow(name).getUnitAttachment();
      assertThat(name, ua.isSea() || (ua.isAir() && ua.getCarrierCost() > 0), is(true));
    }
  }

  @Test
  void boughtUnitsCarryTheNationsRealPrice() {
    final Map<UnitType, Optional<ProductionRule>> units = NationUnits.of(japanese);
    final UnitType infantry = data.getUnitTypeList().getUnitTypeOrThrow("infantry");
    final ProductionRule rule = units.get(infantry).orElseThrow();
    assertThat(japanese.getProductionFrontier().getRules().contains(rule), is(true));
  }

  // ---- item 4: Planes Go Home lands aircraft where they are without asking

  private Territory seaNextToTokyo() {
    return data.getMap().getNeighbors(BasedMap.t(data, "Tokyo")).stream()
        .filter(Territory::isWater)
        .findFirst()
        .orElseThrow();
  }

  private List<Unit> flyFighters(final Territory over, final int n, final int rangeLeft) {
    final List<Unit> fighters = BasedMap.add(data, over, japanese, "fighter", n);
    BasedMap.fly(data, over, fighters);
    for (final Unit u : fighters) {
      data.performChange(
          ChangeFactory.unitPropertyChange(u, rangeLeft, Unit.PropertyName.FLIGHT_RANGE_LEFT));
    }
    return fighters;
  }

  @Test
  void aircraftOverTheirOwnCarriersLandInPlace() {
    final Territory sea = seaNextToTokyo();
    BasedMap.clear(data, japanese, "carrier", "fleetCarrier");
    BasedMap.add(data, sea, japanese, "carrier", 2);
    flyFighters(sea, 2, 3);
    final List<Landing> defaults = AirHome.defaultLandings(japanese, List.of());
    final List<Landing> here =
        defaults.stream().filter(l -> l.from().equals(sea)).toList();
    assertThat(here.isEmpty(), is(false));
    for (final Landing l : here) {
      assertThat(l.to().getName(), AirHome.landsInPlace(l), is(true));
    }
  }

  @Test
  void aircraftWithoutCarrierRoomRelocate() {
    final Territory sea = seaNextToTokyo();
    BasedMap.clear(data, japanese, "carrier", "fleetCarrier");
    flyFighters(sea, 1, 3);
    final List<Landing> fromSea =
        AirHome.defaultLandings(japanese, List.of()).stream()
            .filter(l -> l.from().equals(sea))
            .toList();
    assertThat(fromSea.size(), is(1));
    assertThat(AirHome.landsInPlace(fromSea.get(0)), is(false));
    assertThat(fromSea.get(0).to().isWater(), is(false));
  }

  @Test
  void chosenLandingsAreNotRepeatedAsDefaults() {
    final Territory sea = seaNextToTokyo();
    BasedMap.clear(data, japanese, "carrier", "fleetCarrier");
    flyFighters(sea, 2, 3);
    final UnitType fighter = data.getUnitTypeList().getUnitTypeOrThrow("fighter");
    final Territory tokyo = BasedMap.t(data, "Tokyo");
    final List<Landing> chosen = List.of(new Landing(sea, true, fighter, 2, tokyo));
    assertThat(
        AirHome.defaultLandings(japanese, chosen).stream()
            .filter(l -> l.from().equals(sea))
            .toList(),
        is(empty()));
  }

  // ---- item 5: the purchase screen shows air-stage values

  @Test
  void sovietAircraftShowTheirAirStageValues() {
    final UnitType fighter = data.getUnitTypeList().getUnitTypeOrThrow("fighter");
    final UnitType dive = data.getUnitTypeList().getUnitTypeOrThrow("diveBomber");
    assertThat(
        AirValues.statLine(fighter, russians), containsString("Dogfight 1 | Interception 1"));
    assertThat(AirValues.statLine(dive, russians), containsString("Attack 2 | Air defense 1"));
    // the tooltip says where the 2 comes from
    assertThat(AirValues.explanationHtml(dive, russians), containsString("starting penalty"));
    // land units keep the stock display
    assertThat(
        AirValues.statLine(data.getUnitTypeList().getUnitTypeOrThrow("infantry"), russians),
        is(""));
  }
}
