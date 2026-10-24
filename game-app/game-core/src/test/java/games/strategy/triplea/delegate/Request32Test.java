package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.delegate.air.AirAllocation;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.formatter.PlayerNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.triplea.io.IoUtils;

/** Request 32 on the installed map: America Pacific and America Atlantic. */
class Request32Test {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer pacific;
  private GamePlayer atlantic;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = load();
    pacific = data.getPlayerList().getPlayerId("AmericansPacific");
    atlantic = data.getPlayerList().getPlayerId("Americans");
    Assumptions.assumeTrue(pacific != null, "map has no America Pacific");
  }

  private static GameData load() {
    final GameData data = GameParser.parse(XML, false).orElseThrow();
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(newDelegateBridge(data.getPlayerList().getPlayerId("Germans")));
    start.start();
    start.end();
    return data;
  }

  // ---- turn order, save and load

  @Test
  void everyAmericanStepHasAPacificTwinAndPacificPlaysFirst() {
    final List<String> names = new ArrayList<>();
    for (final GameStep step : data.getSequence()) {
      names.add(step.getName());
    }
    for (final String name : names) {
      if (name.startsWith("american") && !name.startsWith("americanPacific")) {
        final String twin = "americanPacific" + name.substring("american".length());
        assertThat(name, names, hasItem(twin));
        assertThat(name, names.indexOf(twin) < names.indexOf(name), is(true));
      }
    }
    assertThat(
        names.indexOf("chinesePlace") < names.indexOf("americanPacificCombatMove"), is(true));
  }

  @Test
  void bothAmericasSurviveSaveAndLoad() throws Exception {
    final byte[] bytes = IoUtils.writeToMemory(os -> GameDataManager.saveGame(os, data));
    final GameData loaded = IoUtils.readFromMemory(bytes, GameDataManager::loadGame).orElseThrow();
    final GamePlayer p = loaded.getPlayerList().getPlayerId("AmericansPacific");
    assertThat(p != null, is(true));
    assertThat(PlayerNames.display(p), is("America Pacific"));
    assertThat(
        loaded.getMap().getTerritoryOrThrow("San Francisco").getOwner().getName(),
        is("AmericansPacific"));
  }

  // ---- peace and mobilization

  /** What the nation collects at its end of turn in this round. */
  private static int[] endTurn(
      final GameData data, final GamePlayer player, final String step, final int round) {
    final var sequence = data.getSequence();
    for (int i = 0; i < 10_000; i++) {
      if (sequence.getRound() == round && sequence.getStep().getName().equals(step)) {
        break;
      }
      sequence.next();
    }
    final var pus = data.getResourceList().getResourceOrThrow("PUs");
    final var mp = data.getResourceList().getResourceOrThrow("Manpower");
    final int pusBefore = player.getResources().getQuantity(pus);
    final int mpBefore = player.getResources().getQuantity(mp);
    final IDelegateBridge bridge = newDelegateBridge(player);
    when(bridge.getRandom(anyInt(), anyInt(), any(), any(), anyString()))
        .thenAnswer(i -> new int[i.<Integer>getArgument(1)]);
    final var delegate = (EndTurnDelegate) data.getDelegate("endTurn");
    delegate.setDelegateBridgeAndPlayer(bridge);
    delegate.start();
    final int[] gained = {
      player.getResources().getQuantity(pus) - pusBefore,
      player.getResources().getQuantity(mp) - mpBefore
    };
    delegate.end();
    sequence.next();
    return gained;
  }

  @Test
  void peaceIncomeAndMobilizationForBothAmericas() {
    final GameData full = load();
    full.getProperties().set(Mobilization.PROPERTY + "Americans", "");
    full.getProperties().set(Mobilization.PROPERTY + "AmericansPacific", "");
    for (int round = 1; round <= 10; round++) {
      for (final String name : List.of("AmericansPacific", "Americans")) {
        final String step = name.equals("Americans") ? "americanEndTurn" : "americanPacificEndTurn";
        final GamePlayer p = data.getPlayerList().getPlayerId(name);
        final int percent = Mobilization.percent(p, round);
        final int fullPus = endTurn(full, full.getPlayerList().getPlayerId(name), step, round)[0];
        final int[] gained = endTurn(data, p, step, round);
        if (round <= 2) {
          assertThat(
              name + " round " + round,
              List.of(gained[0], gained[1]),
              is(name.equals("Americans") ? List.of(21, 3) : List.of(15, 1)));
        } else {
          assertThat(name + " round " + round, gained[0], is(fullPus * percent / 100));
        }
      }
    }
    assertThat(Mobilization.percent(pacific, 3), is(80));
    assertThat(Mobilization.percent(pacific, 5), is(100));
    assertThat(Mobilization.percent(atlantic, 3), is(30));
    assertThat(Mobilization.percent(atlantic, 10), is(100));
  }

  @Test
  void bothAmericasAreAtPeaceUntilTheRoundTwoTrigger() {
    final GamePlayer japanese = data.getPlayerList().getPlayerId("Japanese");
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    for (int round = 1; round <= 2; round++) {
      assertThat("round " + round, Peacetime.isAtPeace(pacific), is(true));
      assertThat("round " + round, Peacetime.isAtPeace(atlantic), is(true));
      endTurn(data, pacific, "americanPacificEndTurn", round);
      endTurn(data, atlantic, "americanEndTurn", round);
    }
    // the trigger after America Atlantic's round 2 turn takes both to war
    for (final GamePlayer us : List.of(pacific, atlantic)) {
      assertThat(PlayerNames.display(us), Peacetime.isAtPeace(us), is(false));
      assertThat(data.getRelationshipTracker().isAtWar(us, japanese), is(true));
      assertThat(data.getRelationshipTracker().isAtWar(us, germans), is(true));
    }
  }

  // ---- munitions between the two Americas

  private Territory pacificLand() {
    return data.getMap().getTerritoryOrThrow("San Francisco");
  }

  private List<Unit> munitions(final GamePlayer owner, final int count, final int firstStamp) {
    final List<Unit> units = data.getUnitTypeList().getUnitTypeOrThrow("munitions").create(count, owner);
    addTo(pacificLand(), units);
    for (int i = 0; i < units.size(); i++) {
      data.performChange(
          ChangeFactory.unitPropertyChange(
              units.get(i), firstStamp + i, Unit.PropertyName.ARRIVED));
    }
    return units;
  }

  private int pus(final GamePlayer p) {
    return p.getResources().getQuantity("PUs");
  }

  @Test
  void theTwoNewestMunitionsAreAbsorbedAndTheThirdWaits() {
    final List<Unit> sent = munitions(atlantic, 3, 3001);
    final int before = pus(pacific);

    final String text = Munitions.absorb(newDelegateBridge(pacific), pacific);

    assertThat(pus(pacific), is(before + 8));
    assertThat(text, containsString("America Pacific absorbs 2 munitions from America Atlantic"));
    assertThat(text, containsString("(8 PUs); 1 waits."));
    // the oldest (stamp 3001) is the one left
    assertThat(pacificLand().getUnitCollection().contains(sent.get(0)), is(true));
    assertThat(pacificLand().getUnitCollection().contains(sent.get(2)), is(false));

    // next turn it converts
    Munitions.absorb(newDelegateBridge(pacific), pacific);
    assertThat(pus(pacific), is(before + 12));
    assertThat(pacificLand().getUnitCollection().contains(sent.get(0)), is(false));
  }

  @Test
  void eachListedTechRaisesTheLimitByOne() {
    assertThat(Munitions.absorbLimit(pacific), is(2));
    TechTracker.addAdvance(
        pacific,
        newDelegateBridge(pacific),
        data.getTechnologyFrontier().getAdvanceByName("WarProductionBoard"));
    data.getTechTracker().clearCache();
    assertThat(Munitions.absorbLimit(pacific), is(3));
    munitions(atlantic, 4, 3001);
    final int before = pus(pacific);
    Munitions.absorb(newDelegateBridge(pacific), pacific);
    assertThat(pus(pacific), is(before + 12));
  }

  @Test
  void ownMunitionsAreNotAbsorbedAndSovietLendLeaseIsUnlimited() {
    munitions(pacific, 2, 3001);
    assertThat(Munitions.absorb(newDelegateBridge(pacific), pacific), is(""));
    final GamePlayer russians = data.getPlayerList().getPlayerId("Russians");
    final Territory moscow = data.getMap().getTerritoryOrThrow("Moscow");
    addTo(moscow, data.getUnitTypeList().getUnitTypeOrThrow("munitions").create(5, atlantic));
    final int before = pus(russians);
    Munitions.convert(newDelegateBridge(russians), russians);
    assertThat(pus(russians), is(before + 20));
  }

  @Test
  void movingMunitionsStampsTheirArrival() {
    final List<Unit> units = munitions(atlantic, 1, 0);
    final var change = Munitions.stampArrival(data, units);
    data.performChange(change);
    assertThat(
        units.get(0).getArrived(),
        is(data.getSequence().getRound() * 1000 + data.getSequence().getStepIndex()));
  }

  // ---- display names

  @Test
  void displayNamesReplaceInternalNamesInText() {
    assertThat(PlayerNames.display(atlantic), is("America Atlantic"));
    assertThat(PlayerNames.display(pacific), is("America Pacific"));
    assertThat(
        PlayerNames.inText("Americans fire; AmericansPacific lose 2 PUs", data),
        is("America Atlantic fire; America Pacific lose 2 PUs"));
    assertThat(
        PlayerNames.inText("<img src='flags/Americans.png'> Germans", data),
        is("<img src='flags/Americans.png'> Germans"));
    assertThat(
        OwnTransports.message(pacific),
        is("Only America Pacific units can use America Pacific transports."));
  }

  // ---- Bomb USA with two defenders

  @Test
  void bombUsaHasTwoDefendersAndDamageGoesToTheFactoryOwner() throws Exception {
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    for (final GamePlayer us : List.of(pacific, atlantic)) {
      data.performChange(
          ChangeFactory.relationshipChange(
              germans,
              us,
              data.getRelationshipTracker().getRelationshipType(germans, us),
              data.getRelationshipTypeList().getRelationshipType("War")));
    }
    final Territory bombUsa = data.getMap().getTerritoryOrThrow("Air - Bomb America Atlantic");
    AirZoneAttachment.get(bombUsa)
        .orElseThrow()
        .getPropertyOrThrow("bombedPlayer")
        .setValue("AmericansPacific:Americans");
    final AirZoneAttachment zone = AirZoneAttachment.get(bombUsa).orElseThrow();
    assertThat(zone.getBombedPlayers(), is(List.of(pacific, atlantic)));
    assertThat(zone.isAttacker(germans), is(true));
    assertThat(zone.isAttacker(pacific) || zone.isAttacker(atlantic), is(false));
    final var fighter = data.getUnitTypeList().getUnitTypeOrThrow("fighter");
    assertThat(AirAllocation.entryProblem(pacific, fighter, bombUsa).isPresent(), is(false));
    assertThat(AirAllocation.entryProblem(atlantic, fighter, bombUsa).isPresent(), is(false));

    // a raid on a factory America Pacific owns takes America Pacific's PUs
    final Territory detroit = data.getMap().getTerritoryOrThrow("Detroit");
    data.performChange(ChangeFactory.changeOwner(detroit, pacific));
    for (final Unit u : detroit.getUnitCollection().getMatches(Matches.unitCanProduceUnits())) {
      data.performChange(ChangeFactory.changeOwner(List.of(u), pacific, detroit));
    }
    removeFrom(detroit, detroit.getUnitCollection().getMatches(Matches.unitIsAaForAnything()));
    removeFrom(bombUsa, bombUsa.getUnits());
    addTo(bombUsa, data.getUnitTypeList().getUnitTypeOrThrow("flyingWing").create(1, germans));
    final var delegate = (AirCombatDelegate) data.getDelegate("airCombat");
    // request 35: the target is chosen in the simultaneous bomber stage
    assertThat(
        new AirStageRunner(data)
            .bombers(
                germans,
                new TargetAssignment(
                    bombUsa,
                    data.getUnitTypeList().getUnitTypeOrThrow("flyingWing"),
                    1,
                    new Target(detroit, TargetKind.STRATEGIC)))
            .run()
            .problems(),
        is(List.of()));
    final int pacificBefore = pus(pacific);
    final int atlanticBefore = pus(atlantic);
    final IDelegateBridge strikes = newDelegateBridge(germans);
    when(strikes.getRandom(anyInt(), any(), any(), anyString())).thenReturn(5);
    advanceToStep(strikes, "airStrikes");
    delegate.setDelegateBridgeAndPlayer(strikes);
    delegate.start();
    delegate.end();
    assertThat(pus(pacific) < pacificBefore, is(true));
    assertThat(pus(atlantic), is(atlanticBefore));
  }
}
