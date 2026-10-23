package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.DefaultAttachment;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.attachments.TerritoryAttachment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Request 28 part C on the installed map: Chinese land taken from the Axis by Britain or the US
 * goes to China; the USSR keeps what it takes; British and US units on Chinese land are sold to
 * China at the end of its turn. The map does not tag "chineseHomeland" yet, so the test tags every
 * territory whose original owner is China (Manchuria included), as the XML is asked to.
 */
class ChineseLiberationTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer china;
  private GamePlayer british;
  private GamePlayer americans;
  private GamePlayer russians;
  private GamePlayer japanese;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = GameParser.parse(XML, false).orElseThrow();
    china = player("Chinese");
    british = player("British");
    americans = player("Americans");
    russians = player("Russians");
    japanese = player("Japanese");
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(newDelegateBridge(player("Germans")));
    start.start();
    start.end();
    for (final Territory t : data.getMap().getTerritories()) {
      if (!t.isWater()
          && TerritoryAttachment.get(t)
              .flatMap(TerritoryAttachment::getOriginalOwner)
              .map(o -> o.equals(china))
              .orElse(false)) {
        set(TerritoryAttachment.get(t).orElseThrow(), "chineseHomeland", "true");
      }
    }
    for (final GamePlayer p : List.of(british, americans, russians)) {
      war(p, japanese);
    }
  }

  private GamePlayer player(final String name) {
    return data.getPlayerList().getPlayerId(name);
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  private UnitType type(final String name) {
    return data.getUnitTypeList().getUnitType(name).orElseThrow();
  }

  private void war(final GamePlayer a, final GamePlayer b) {
    data.performChange(
        ChangeFactory.relationshipChange(
            a,
            b,
            data.getRelationshipTracker().getRelationshipType(a, b),
            data.getRelationshipTypeList().getRelationshipType("War")));
  }

  private static void set(final DefaultAttachment a, final String property, final String value) {
    try {
      a.getPropertyOrThrow(property).setValue(value);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private IDelegateBridge bridge(final GamePlayer p) {
    return newDelegateBridge(p);
  }

  private void takeOver(final Territory t, final GamePlayer captor) {
    data.getBattleDelegate().getBattleTracker().takeOver(t, captor, bridge(captor), null, null);
  }

  private void goTo(final String step, final int round) {
    final var sequence = data.getSequence();
    for (int i = 0; i < 10_000; i++) {
      if (sequence.getRound() == round && sequence.getStep().getName().equals(step)) {
        return;
      }
      sequence.next();
    }
    throw new IllegalStateException("no step " + step + " in round " + round);
  }

  @Test
  void britainLiberatesForChinaTheUssrKeepsItsConquest() {
    takeOver(territory("Peking"), british);
    assertThat(territory("Peking").getOwner(), is(china));
    takeOver(territory("Shanghai"), americans);
    assertThat(territory("Shanghai").getOwner(), is(china));
    takeOver(territory("Harbin"), russians);
    assertThat(territory("Harbin").getOwner(), is(russians));
  }

  @Test
  void liberationWorksWhileJapanHoldsTheChineseCapital() {
    data.performChange(ChangeFactory.changeOwner(territory("Chungking"), japanese));
    data.performChange(ChangeFactory.changeOwner(territory("Hunan"), japanese));
    takeOver(territory("Hunan"), british);
    assertThat(territory("Hunan").getOwner(), is(china));
  }

  @Test
  void aChineseHomelandWithJapanAsOriginalOwnerStillGoesToChina() {
    final Territory mukden = territory("Mukden");
    set(TerritoryAttachment.get(mukden).orElseThrow(), "originalOwner", "Japanese");
    takeOver(mukden, americans);
    assertThat(mukden.getOwner(), is(china));
  }

  @Test
  void taiwanIsNotChinese() {
    final Territory taihoku = territory("Taihoku");
    assertThat(ChineseLiberation.isChineseHomeland(taihoku), is(false));
    takeOver(taihoku, british);
    assertThat(taihoku.getOwner(), is(british));
  }

  @Test
  void mukdensFactoryPassesToChinaAndChinaCanPlaceThere() {
    final Territory mukden = territory("Mukden");
    data.performChange(
        ChangeFactory.removeUnits(
            mukden, mukden.getUnitCollection().getMatches(Matches.unitIsNotInfrastructure())));
    takeOver(mukden, british);
    assertThat(mukden.getOwner(), is(china));
    assertThat(
        mukden.getUnitCollection().getMatches(Matches.unitCanProduceUnits()).stream()
            .allMatch(u -> u.isOwnedBy(china)),
        is(true));
    assertThat(mukden.anyUnitsMatch(Matches.unitCanProduceUnits()), is(true));

    goTo("chinesePlace", 1);
    final var place = (PlaceDelegate) data.getDelegate("place");
    place.setDelegateBridgeAndPlayer(bridge(china));
    place.start();
    final List<Unit> infantry = type("infantry").create(1, china);
    china.getUnitCollection().addAll(infantry);
    final Optional<String> problem = place.canUnitsBePlaced(mukden, infantry, china);
    place.end();
    assertThat(problem.orElse(""), problem.isEmpty(), is(true));
  }

  private List<Unit> units(final GamePlayer owner, final Object... typesAndCounts) {
    final List<Unit> units = new ArrayList<>();
    for (int i = 0; i < typesAndCounts.length; i += 2) {
      units.addAll(type((String) typesAndCounts[i]).create((Integer) typesAndCounts[i + 1], owner));
    }
    return units;
  }

  private int count(final ChineseLiberation.Garrison g, final String type) {
    return g.units().getInt(type(type));
  }

  @Test
  void twoInfantryAndEightPusOfOtherUnits() {
    final var g = ChineseLiberation.garrisonFor(units(americans, "infantry", 2, "tank", 1), data);
    assertThat(g.conscripts(), is(2));
    assertThat(g.pool(), is(10));
    assertThat(count(g, "antiTankGun"), is(3));
    assertThat(count(g, "heavyGun"), is(0));
    assertThat(g.full(), is(false));
    assertThat(g.cash(), is(0));
  }

  @Test
  void sixInfantryAndThreeTanks() {
    final var g = ChineseLiberation.garrisonFor(units(americans, "infantry", 6, "tank", 3), data);
    assertThat(g.conscripts(), is(4));
    assertThat(g.pool(), is(34)); // 6 + 24 + 2 extra infantry x 1 MP x 2
    assertThat(count(g, "antiTankGun"), is(3));
    assertThat(count(g, "heavyGun"), is(1));
    assertThat(count(g, "trench"), is(2));
    assertThat(g.surplus(), is(12));
    assertThat(g.cash(), is(6));
  }

  @Test
  void noInfantryAndTwoTanks() {
    final var g = ChineseLiberation.garrisonFor(units(british, "tank", 2), data);
    assertThat(g.conscripts(), is(0));
    assertThat(count(g, "antiTankGun"), is(3));
    assertThat(count(g, "heavyGun"), is(1));
    assertThat(count(g, "trench"), is(0));
    assertThat(g.cash(), is(0));
  }

  @Test
  void aSurplusOfNineteenGivesNinePus() {
    // 5 tanks + 1 infantry = 41 PUs; garrison 22; surplus 19 x 50% = 9.5, rounded down
    final var g = ChineseLiberation.garrisonFor(units(british, "tank", 5, "infantry", 1), data);
    assertThat(g.surplus(), is(19));
    assertThat(g.cash(), is(9));
  }

  @Test
  void westernUnitsBecomeAChineseGarrisonAtTheEndOfChinasTurn() {
    final Territory hunan = territory("Hunan");
    final List<Unit> force = units(americans, "infantry", 6, "tank", 3);
    force.get(7).setHits(1); // damaged: counts at full value
    data.performChange(ChangeFactory.addUnits(hunan, force));
    final var pus = data.getResourceList().getResourceOrThrow("PUs");
    final var mp = data.getResourceList().getResourceOrThrow("Manpower");
    final int pusBefore = china.getResources().getQuantity(pus);
    final int mpBefore = china.getResources().getQuantity(mp);
    final int conscriptsBefore =
        hunan.getUnitCollection().getMatches(Matches.unitIsOfType(type("conscript"))).size();

    final String report = ChineseLiberation.garrisonWesternUnits(bridge(china), china);

    assertThat(report, containsString("Hunan: "));
    assertThat(report, containsString("6 PUs pass to China from 9 America Atlantic units."));
    assertThat(china.getResources().getQuantity(pus), is(pusBefore + 6));
    assertThat(china.getResources().getQuantity(mp), is(mpBefore));
    assertThat(hunan.anyUnitsMatch(Matches.unitIsOwnedBy(americans)), is(false));
    assertThat(
        hunan.getUnitCollection().getMatches(Matches.unitIsOfType(type("conscript"))).size(),
        is(conscriptsBefore + 4));
    assertThat(
        hunan.getUnitCollection()
            .getMatches(Matches.unitIsOfType(type("heavyGun")).and(Matches.unitIsOwnedBy(china)))
            .size()
            >= 1,
        is(true));
  }

  @Test
  void eachTerritoryIsComputedSeparately() {
    final var pus = data.getResourceList().getResourceOrThrow("PUs");
    final int before = china.getResources().getQuantity(pus);
    for (final String name : List.of("Hunan", "Kwangsi", "Yunnan")) {
      data.performChange(
          ChangeFactory.addUnits(territory(name), units(british, "infantry", 6, "tank", 3)));
    }
    final String report = ChineseLiberation.garrisonWesternUnits(bridge(china), china);
    // pooled, 3 x 34 = 102 would buy one garrison and leave 80 (40 PUs); separately: 3 x 6
    assertThat(china.getResources().getQuantity(pus), is(before + 18));
    assertThat(report.split("<br />").length, is(3));
  }

  /** Gives Mukden a map manpower value of 1 through a Japanese trigger (draft 77 has none). */
  private void giveMukdenJapaneseManpower() {
    set(
        (DefaultAttachment) japanese.getAttachment("conditionAttachment_Japanese_mp1"),
        "directOwnershipTerritories",
        "each:Chubu:Chugoku:Kyushu:Tohoku:Mukden");
  }

  @Test
  void chinaCollectsTheMapManpowerOfLiberatedChineseLand() {
    // Nanking: no Chinese trigger pays it (Mukden does since the 23 October XML), so give it a
    // Japanese one
    set(
        (DefaultAttachment) japanese.getAttachment("conditionAttachment_Japanese_mp1"),
        "directOwnershipTerritories",
        "each:Chubu:Chugoku:Kyushu:Tohoku:Nanking");
    final Territory mukden = territory("Nanking");
    assertThat(ChineseLiberation.homelandManpower(mukden, china), is(0)); // Japan holds it
    takeOver(mukden, british);
    assertThat(mukden.getOwner(), is(china));
    assertThat(ChineseLiberation.homelandManpower(mukden, china), is(1));
    final var mp = data.getResourceList().getResourceOrThrow("Manpower");
    final int before = china.getResources().getQuantity(mp);

    ChineseLiberation.collectHomelandManpower(bridge(china), china);

    assertThat(china.getResources().getQuantity(mp), is(before + 1));
    assertThat(
        games.strategy.triplea.ui.TerritoryManpower.of(mukden).orElseThrow().receivers()
            .containsKey(china),
        is(true));
  }

  @Test
  void aSovietHeldManchuriaPaysNobodyAndChinasOwnLandIsNotCountedTwice() {
    giveMukdenJapaneseManpower();
    final Territory mukden = territory("Mukden");
    takeOver(mukden, russians);
    assertThat(mukden.getOwner(), is(russians));
    assertThat(ChineseLiberation.homelandManpower(mukden, china), is(0));
    // Hunan already pays China through China's own trigger
    assertThat(ChineseLiberation.homelandManpower(territory("Hunan"), china), is(0));
    assertThat(ChineseLiberation.collectHomelandManpower(bridge(china), china), is(""));
  }

  @Test
  void sovietUnitsNeverBecomeAGarrison() {
    final Territory hunan = territory("Hunan");
    data.performChange(ChangeFactory.addUnits(hunan, type("infantry").create(2, russians)));
    assertThat(ChineseLiberation.garrisonWesternUnits(bridge(china), china), is(""));
    assertThat(hunan.anyUnitsMatch(Matches.unitIsOwnedBy(russians)), is(true));
  }

  @Test
  void westernAndSovietUnitsCannotEnterChineseLand() {
    final Territory hongKong = territory("Hong Kong");
    final Territory kwangsi = territory("Kwangsi");
    assertThat(kwangsi.getOwner(), is(china));
    final Territory soviet =
        data.getMap().getTerritories().stream()
            .filter(t -> !t.isWater() && t.isOwnedBy(russians))
            .filter(t -> data.getMap().getNeighbors(t).stream().anyMatch(n -> n.isOwnedBy(china)))
            .findFirst()
            .orElseThrow();
    final Territory chinese =
        data.getMap().getNeighbors(soviet).stream()
            .filter(n -> n.isOwnedBy(china))
            .findFirst()
            .orElseThrow();
    assertThat(move(russians, "russianNonCombatMove", soviet, chinese).isPresent(), is(true));
    assertThat(move(british, "britishCombatMove", hongKong, kwangsi).isPresent(), is(true));
    assertThat(move(british, "britishNonCombatMove", hongKong, kwangsi).isPresent(), is(true));
  }

  private Optional<String> move(
      final GamePlayer p, final String step, final Territory from, final Territory to) {
    final List<Unit> infantry = type("infantry").create(1, p);
    data.performChange(ChangeFactory.addUnits(from, infantry));
    goTo(step, 1);
    final var move = (MoveDelegate) data.getDelegate("move");
    move.setDelegateBridgeAndPlayer(bridge(p));
    move.start();
    final Optional<String> result =
        move.performMove(new MoveDescription(infantry, new Route(from, to)));
    move.end();
    return result;
  }
}
