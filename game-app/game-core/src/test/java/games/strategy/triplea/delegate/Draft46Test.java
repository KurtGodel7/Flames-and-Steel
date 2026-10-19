package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.attachments.MunitionsAttachment;
import games.strategy.triplea.delegate.battle.BattleState;
import games.strategy.triplea.delegate.battle.FakeBattleState;
import games.strategy.triplea.delegate.battle.UnderseaWar;
import games.strategy.triplea.delegate.battle.steps.fire.FiringGroup;
import games.strategy.triplea.delegate.battle.steps.fire.general.FiringGroupSplitterGeneral;
import games.strategy.triplea.delegate.research.ResearchCenters;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel request 7 on the installed map (draft 46 or later): munitions and placement,
 * Undersea War, research stars and SPC Output order. Skipped when the map is not installed.
 */
class Draft46Test {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer germans;
  private GamePlayer british;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = GameParser.parse(XML, false).orElseThrow();
    Assumptions.assumeTrue(
        data.getTechnologyFrontier().getAdvanceByName(UnderseaWar.TECH_NAME) != null,
        "map has no Undersea War");
    germans = data.getPlayerList().getPlayerId("Germans");
    british = data.getPlayerList().getPlayerId("British");
  }

  private UnitType type(final String name) {
    return data.getUnitTypeList().getUnitType(name).orElseThrow();
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  // --- 1. munitions use factory placement capacity

  @Test
  void munitionsCountAgainstFactoryPlacementCapacity() {
    final PlaceDelegate delegate = new PlaceDelegate();
    delegate.initialize("place");
    delegate.setDelegateBridgeAndPlayer(newDelegateBridge(british));
    delegate.start();
    final Territory london = territory("London");
    final List<Unit> munitions = type("munitions").create(30, british);
    final List<Unit> infantry = type("infantry").create(30, british);
    british.getUnitCollection().addAll(munitions);
    british.getUnitCollection().addAll(infantry);

    final int capacity = delegate.getPlaceableUnits(infantry, london).getMaxUnits();
    assertThat(capacity, greaterThan(0));
    assertThat(capacity, lessThan(30));
    assertThat(delegate.getPlaceableUnits(munitions, london).getMaxUnits(), is(capacity));

    // filling the factory with infantry leaves no room for munitions
    assertThat(delegate.placeUnits(infantry.subList(0, capacity), london).isEmpty(), is(true));
    assertThat(delegate.getPlaceableUnits(munitions, london).getMaxUnits(), is(0));
  }

  // --- 4. munitions value

  @Test
  void munitionsCostAndConvertAtFourPus() {
    final Unit crate = type("munitions").create(1, germans).get(0);
    assertThat(MunitionsAttachment.valueOf(crate), is(4));
    assertThat(type("munitions").getUnitAttachment().getTransportCost(), is(2));
  }

  // --- 2. Undersea War

  private void grantUnderseaWar(final GamePlayer player) {
    final TechAdvance tech = data.getTechnologyFrontier().getAdvanceByName(UnderseaWar.TECH_NAME);
    TechTracker.addAdvance(player, newDelegateBridge(player), tech);
    data.getTechTracker().clearCache();
  }

  private List<FiringGroup> subPhaseGroups(final List<Unit> attackers, final List<Unit> defenders) {
    final BattleState state =
        FakeBattleState.builder()
            .battleRound(1)
            .maxBattleRounds(-1)
            .battleId(UUID.randomUUID())
            .battleSite(territory("Sea Zone 86 Baltic Sea - South"))
            .territoryEffects(List.of())
            .attacker(germans)
            .defender(british)
            .attackingUnits(attackers)
            .attackingWaitingToDie(List.of())
            .defendingUnits(defenders)
            .defendingWaitingToDie(List.of())
            .killed(List.of())
            .retreatUnits(List.of())
            .gameData(data)
            .attackerRetreatTerritories(List.of())
            .dependentUnits(List.of())
            .bombardingUnits(List.of())
            .build();
    return FiringGroupSplitterGeneral.of(
            BattleState.Side.OFFENSE, FiringGroupSplitterGeneral.Type.FIRST_STRIKE, "subs")
        .apply(state);
  }

  private static List<String> typeNames(final Collection<Unit> units) {
    return units.stream().map(u -> u.getType().getName()).distinct().collect(Collectors.toList());
  }

  @Test
  void withoutUnderseaWarSubsCannotTargetSubs() {
    final List<Unit> attackers = type("submarine").create(2, germans);
    final List<Unit> defenders = new ArrayList<>(type("submarine").create(1, british));
    defenders.addAll(type("cruiser").create(1, british));
    final List<FiringGroup> groups = subPhaseGroups(attackers, defenders);
    assertThat(groups.size(), is(1));
    assertThat(typeNames(groups.get(0).getTargetUnits()), is(List.of("cruiser")));
  }

  @Test
  void underseaWarSubsTargetSurfaceShipsAndSubs() {
    grantUnderseaWar(germans);
    final List<Unit> attackers = new ArrayList<>(type("submarine").create(2, germans));
    attackers.addAll(type("typeXXI").create(1, germans));
    final List<Unit> subs = type("submarine").create(1, british);
    final List<Unit> cruiser = type("cruiser").create(1, british);
    final List<Unit> defenders = new ArrayList<>(subs);
    defenders.addAll(cruiser);

    final List<FiringGroup> groups = subPhaseGroups(attackers, defenders);
    assertThat(groups.size(), is(1));
    final FiringGroup group = groups.get(0);
    assertThat(typeNames(group.getFiringUnits()), containsInAnyOrder("submarine", "typeXXI"));
    assertThat(typeNames(group.getTargetUnits()), containsInAnyOrder("submarine", "cruiser"));

    // both stay targets when the subs fire; the casualty step puts hits on the cruiser first
    // (UnderseaWarCasualtiesTest)
    group.retainAliveTargets(defenders);
    assertThat(typeNames(group.getTargetUnits()), containsInAnyOrder("submarine", "cruiser"));
    assertThat(UnderseaWar.surfaceShipsFirst(group.getFiringUnits(), defenders), is(cruiser));
  }

  @Test
  void onlySubsOfTheNationWithTheTechMayHitSubs() {
    grantUnderseaWar(germans);
    final GamePlayer italians = data.getPlayerList().getPlayerId("Italians");
    final List<Unit> attackers = new ArrayList<>(type("submarine").create(1, germans));
    attackers.addAll(type("submarine").create(1, italians));
    final List<Unit> defenders = type("submarine").create(1, british);
    final List<FiringGroup> groups = subPhaseGroups(attackers, defenders);
    assertThat(groups.size(), is(1));
    assertThat(groups.get(0).getFiringUnits().size(), is(1));
    assertThat(groups.get(0).getFiringUnits().iterator().next().getOwner(), is(germans));
  }

  @Test
  void transportsDoNotShieldSubs() {
    grantUnderseaWar(germans);
    final List<Unit> subs = type("submarine").create(1, british);
    final List<Unit> defenders = new ArrayList<>(subs);
    defenders.addAll(type("transport").create(1, british));
    final List<Unit> attackers = type("submarine").create(1, germans);
    final FiringGroup group = subPhaseGroups(attackers, defenders).get(0);
    assertThat(group.getTargetUnits(), hasItem(subs.get(0)));
    assertThat(UnderseaWar.surfaceShipsFirst(group.getFiringUnits(), defenders), is(empty()));
  }

  // --- 3. research stars

  @Test
  void starRowsUseTheNewCostsAndStartingStarsShow() {
    final Map<String, Integer> expected =
        Map.of(
            "Germans/Jet",
            4,
            "Russians/KV2",
            3,
            "Japanese/Tank",
            3,
            "Japanese/StrategicBomber",
            3,
            "Italians/Tank",
            3,
            "Germans/KingTiger",
            2);
    expected.forEach(
        (key, required) -> {
          final String[] s = key.split("/");
          final GamePlayer player = data.getPlayerList().getPlayerId(s[0]);
          final TechAdvance tech = data.getTechnologyFrontier().getAdvanceByName(s[1]);
          assertThat(key, ResearchCenters.getStarsRequired(player, tech), is(required));
        });
    // starting stars show as the map lists them (the map's values change between drafts, e.g.
    // draft 75 gave the USSR 1 KV2 star instead of 2)
    int listed = 0;
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      final var stars = player.getTechAttachment().getResearchStars();
      for (final String name : stars.keySet()) {
        final TechAdvance tech = data.getTechnologyFrontier().getAdvanceByName(name);
        assertThat(
            player.getName() + "/" + name,
            ResearchCenters.getStars(player, tech),
            is(stars.getInt(name)));
        listed++;
      }
    }
    assertThat(listed > 0, is(true));
  }

  @Test
  void noStartingStarsAlreadyCompleteATech() {
    final List<String> complete = new ArrayList<>();
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      for (final TechAdvance tech : data.getTechnologyFrontier().getTechs()) {
        final int stars = ResearchCenters.getStars(player, tech);
        if (stars > 0 && stars >= ResearchCenters.getStarsRequired(player, tech)) {
          complete.add(player.getName() + " " + tech.getName());
        }
      }
    }
    assertThat(complete, is(empty()));
  }

  // --- SPC Output order

  @Test
  void spcOutputComesAfterPlaceAndBeforeTechActivation() {
    final List<String> names = new ArrayList<>();
    for (final GameStep step : data.getSequence()) {
      names.add(step.getName());
    }
    for (final String nation : List.of("german", "russian", "japanese", "british", "american")) {
      final int place = names.indexOf(nation + "Place");
      final int output = names.indexOf(nation + "ApcOutput");
      final int tech = names.indexOf(nation + "TechActivation");
      assertThat(nation, output, is(place + 1));
      assertThat(nation, tech, is(output + 1));
    }
    assertThat(names, not(hasItem("italianApcOutput")));
  }
}
