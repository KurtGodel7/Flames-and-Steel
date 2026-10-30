package games.strategy.triplea.delegate.battle;

import static games.strategy.triplea.delegate.GameDataTestUtil.battleship;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static games.strategy.triplea.delegate.GameDataTestUtil.japanese;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.delegate.power.calculator.CombatValueBuilder;
import games.strategy.triplea.delegate.power.calculator.PowerStrengthAndRolls;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel request 51 on the Revised test map: bombardment dice capped by the land units
 * unloaded from sea. Battleships stand in for Yamatos, with 2 dice at 4.
 */
class BombardCapTest {
  private final GameData gameData = TestMapGameData.REVISED.getGameData();
  private final GamePlayer japanese = japanese(gameData);

  @BeforeEach
  void setUp() throws Exception {
    battleship(gameData).getUnitAttachment().getPropertyOrThrow("attackRolls").setValue("2");
    battleship(gameData).getUnitAttachment().getPropertyOrThrow("bombard").setValue("4");
  }

  @Test
  void twoUnitsUnloadedLetOneShipFireBothDiceAtItsValue() {
    assertThat(fire(unloaded(2), ships(4)), contains("2 at 4", "none", "none", "none"));
  }

  @Test
  void oneUnitUnloadedLetsOneShipFireOneDieAtFive() {
    assertThat(fire(unloaded(1), ships(4)), contains("1 at 5", "none", "none", "none"));
  }

  @Test
  void theTechAllowsTwoDicePerUnitUnloaded() throws Exception {
    final TechAdvance tech = gameData.getTechnologyFrontier().getAdvanceByProperty("rocket");
    TechAbilityAttachment ability = TechAbilityAttachment.get(tech);
    if (ability == null) {
      ability = new TechAbilityAttachment(Constants.TECH_ABILITY_ATTACHMENT_NAME, tech, gameData);
      tech.addAttachment(Constants.TECH_ABILITY_ATTACHMENT_NAME, ability);
    }
    ability.getPropertyOrThrow("bombardDicePerUnloadedUnit").setValue("2");
    TechTracker.addAdvance(japanese, newDelegateBridge(japanese), tech);

    assertThat(BombardCap.dicePerUnloadedUnit(japanese), is(2));
    assertThat(fire(unloaded(2), ships(4)), contains("2 at 4", "2 at 4", "none", "none"));
  }

  @Test
  void theCapNeverAddsDiceNorRaisesAnUncutShip() throws Exception {
    battleship(gameData).getUnitAttachment().getPropertyOrThrow("attackRolls").setValue("1");
    assertThat(fire(unloaded(3), ships(1)), contains("1 at 4"));
  }

  @Test
  void noUnitsUnloadedMeansNoBombardment() {
    final List<Unit> landed = infantry(gameData).create(2, japanese);
    assertThat(BombardCap.diceCap(landed, japanese), is(0));
    assertThat(fire(landed, ships(2)), contains("none", "none"));
  }

  @Test
  void captionSaysDiceAndUnitsUnloaded() {
    assertThat(BombardCap.caption(2, 2, 1), is("Bombardment: 2 dice (2 units unloaded)"));
    assertThat(BombardCap.caption(1, 3, 1), is("Bombardment: 1 die of up to 3 (3 units unloaded)"));
    assertThat(
        BombardCap.caption(4, 2, 2), is("Bombardment: 4 dice (2 units unloaded, 2 dice each)"));
  }

  private List<Unit> unloaded(final int count) {
    final List<Unit> units = infantry(gameData).create(count, japanese);
    units.forEach(u -> u.setWasAmphibious(true));
    return units;
  }

  private List<Unit> ships(final int count) {
    return battleship(gameData).create(count, japanese);
  }

  /** Each ship's dice as the bombardment rolls them, e.g. "2 at 4" or "none". */
  private List<String> fire(final List<Unit> landed, final List<Unit> ships) {
    final PowerStrengthAndRolls table =
        PowerStrengthAndRolls.build(
            ships,
            CombatValueBuilder.navalBombardmentCombatValue()
                .enemyUnits(List.of())
                .friendlyUnits(landed)
                .supportAttachments(List.of())
                .lhtrHeavyBombers(false)
                .gameDiceSides(6)
                .territoryEffects(List.of())
                .diceCap(BombardCap.diceCap(landed, japanese))
                .reducedDieValue(5)
                .build());
    final List<String> result = new ArrayList<>();
    for (final Unit ship : ships) {
      result.add(
          table.getRolls(ship) == 0
              ? "none"
              : table.getRolls(ship) + " at " + table.getStrength(ship));
    }
    return result;
  }
}
