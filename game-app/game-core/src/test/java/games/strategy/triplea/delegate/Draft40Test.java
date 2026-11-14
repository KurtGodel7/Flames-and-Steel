package games.strategy.triplea.delegate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Constants;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.delegate.air.AirStage;
import games.strategy.triplea.delegate.air.AirStage.Stage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel request 4 on the installed map (draft 40 or later): US peacetime rules, air
 * strikes on neutrals and land-only bombers. Skipped when the map is not installed.
 */
class Draft40Test {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer americans;
  private GamePlayer japanese;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = games.strategy.triplea.delegate.air.BasedMap.load();
    Assumptions.assumeTrue(
        data.getProperties().get(Peacetime.INCOME + "Americans", -1) >= 0,
        "map has no US peacetime rule");
    americans = data.getPlayerList().getPlayerId("Americans");
    japanese = data.getPlayerList().getPlayerId("Japanese");
  }

  @Test
  void usIsAtPeaceAtTheStartOthersAreNot() {
    assertThat(Peacetime.isAtPeace(americans), is(true));
    assertThat(Peacetime.isAtPeace(japanese), is(false));
    assertThat(Peacetime.isAtPeace(data.getPlayerList().getPlayerId("Germans")), is(false));
  }

  @Test
  void usEstimatedIncomeIsTheFixedAmount() {
    final var income = AbstractEndTurnDelegate.findEstimatedIncome(americans, data);
    assertThat(income.getInt(pus()), is(21));
    assertThat(income.getInt(manpower()), is(3));
  }

  @Test
  void usEndTurnCollectsExactlyTheFixedAmount() {
    goToStep("americanEndTurn");
    final int pusBefore = americans.getResources().getQuantity(pus());
    final int mpBefore = americans.getResources().getQuantity(manpower());
    final IDelegateBridge bridge = MockDelegateBridge.newDelegateBridge(americans);
    final var delegate = data.getDelegate("endTurn");
    delegate.setDelegateBridgeAndPlayer(bridge);
    delegate.start();

    assertThat(americans.getResources().getQuantity(pus()) - pusBefore, is(21));
    assertThat(americans.getResources().getQuantity(manpower()) - mpBefore, is(3));
  }

  @Test
  void usAtWarCollectsNormally() {
    data.getRelationshipTracker()
        .setRelationship(
            americans,
            japanese,
            data.getRelationshipTypeList()
                .getRelationshipType(Constants.RELATIONSHIP_TYPE_DEFAULT_WAR));
    assertThat(Peacetime.isAtPeace(americans), is(false));
    final var income = AbstractEndTurnDelegate.findEstimatedIncome(americans, data);
    assertThat(income.getInt(pus()), greaterThan(21));
  }

  @Test
  void usMoveAndAirStepsAreSkipped() {
    goToStep("americanCombatMove");
    final var delegate = data.getSequence().getStep().getDelegate();
    delegate.setDelegateBridgeAndPlayer(MockDelegateBridge.newDelegateBridge(americans));
    assertThat(delegate.delegateCurrentlyRequiresUserInput(), is(false));
  }

  @Test
  void theAirStagesNeverAskTheUsAtPeace() {
    // request 35: the bomber and fighter stages ask every nation at once, but not one at peace
    assertThat(Peacetime.isAtPeace(americans), is(true));
    final var medium = data.getUnitTypeList().getUnitTypeOrThrow("mediumBomber");
    final Territory newYork = data.getMap().getTerritoryOrThrow("New York");
    newYork.getUnitCollection().addAll(medium.create(1, americans));
    // Japanese bombers next to Chinese land with an army: Japan has something to do
    final var japaneseBase =
        data.getMap().getTerritories().stream()
            .filter(t -> !t.isWater() && t.isOwnedBy(japanese))
            .filter(
                t ->
                    data.getMap().getNeighbors(t).stream()
                        .anyMatch(
                            n ->
                                !n.isWater()
                                    && !AirCombat.getLandTargetUnits(japanese, n).isEmpty()))
            .findFirst()
            .orElseThrow();
    japaneseBase.getUnitCollection().addAll(medium.create(1, japanese));
    assertThat(AirStage.hasWork(japanese, Stage.BOMBERS, Map.of(), data.getMap()), is(true));
    final AirStageRunner stages = new AirStageRunner(data).run();
    assertThat(stages.asked(), not(hasItem(americans)));
    assertThat(stages.asked(), hasItem(japanese));
  }

  @Test
  void strategicBombersAttackOnlyLandTargets() {
    final var strategic = data.getUnitTypeList().getUnitTypeOrThrow("strategicBomber");
    final var dive = data.getUnitTypeList().getUnitTypeOrThrow("diveBomber");
    final var medium = data.getUnitTypeList().getUnitTypeOrThrow("mediumBomber");
    assertThat(AirCombat.mayAttack(strategic, TargetKind.SURFACE), is(false));
    assertThat(AirCombat.mayAttack(strategic, TargetKind.SUBS), is(false));
    assertThat(AirCombat.mayAttack(strategic, TargetKind.LAND), is(true));
    assertThat(AirCombat.mayAttack(strategic, TargetKind.STRATEGIC), is(true));
    assertThat(AirCombat.mayAttack(dive, TargetKind.SURFACE), is(true));
    assertThat(AirCombat.mayAttack(medium, TargetKind.SUBS), is(true));
  }

  @Test
  void neutralGarrisonsAreAirTargets() {
    int neutral = 0;
    for (final Territory t : data.getMap().getTerritories()) {
      for (final var target : AirCombat.targetsIn(japanese, t)) {
        if (target.kind() == TargetKind.LAND && target.territory().getOwner().isNull()) {
          neutral++;
        }
      }
    }
    assertThat(neutral, greaterThan(0));
  }

  private void goToStep(final String name) {
    final var sequence = data.getSequence();
    for (int i = 0; i < 500 && !sequence.getStep().getName().equals(name); i++) {
      sequence.next();
    }
    assertThat(sequence.getStep().getName(), is(name));
  }

  private Resource pus() {
    return data.getResourceList().getResourceOrThrow(Constants.PUS);
  }

  private Resource manpower() {
    return data.getResourceList().getResourceOrThrow(Peacetime.MANPOWER_RESOURCE);
  }
}
