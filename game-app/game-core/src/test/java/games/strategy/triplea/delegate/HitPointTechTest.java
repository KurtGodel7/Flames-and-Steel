package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.GameDataTestUtil.battleship;
import static games.strategy.triplea.delegate.GameDataTestUtil.british;
import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.delegate.battle.casualty.StickyHits;
import games.strategy.triplea.delegate.data.CasualtyList;
import games.strategy.triplea.util.UnitCategory;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Flames and Steel: hit point techs belong to one nation (Revised battleships have 2). */
class HitPointTechTest {
  private final GameData gameData = TestMapGameData.REVISED.getGameData();
  private Unit britishBattleship;
  private Unit germanBattleship;

  @BeforeEach
  void setUp() throws Exception {
    final TechAdvance tech = gameData.getTechnologyFrontier().getAdvanceByProperty("superSub");
    TechAbilityAttachment ability = TechAbilityAttachment.get(tech);
    if (ability == null) {
      ability = new TechAbilityAttachment(Constants.TECH_ABILITY_ATTACHMENT_NAME, tech, gameData);
      tech.addAttachment(Constants.TECH_ABILITY_ATTACHMENT_NAME, ability);
    }
    ability.getPropertyOrThrow("hitPointBonus").setValue("1:" + battleship(gameData).getName());
    TechTracker.addAdvance(british(gameData), newDelegateBridge(british(gameData)), tech);
    gameData.getTechTracker().clearCache();
    britishBattleship = battleship(gameData).create(1, british(gameData)).get(0);
    germanBattleship = battleship(gameData).create(1, germans(gameData)).get(0);
  }

  @Test
  void onlyTheNationWithTheTechGetsTheExtraHitPoint() {
    assertThat(britishBattleship.getMaxHitPoints(), is(3));
    assertThat(germanBattleship.getMaxHitPoints(), is(2));
    assertThat(
        new UnitCategory(britishBattleship.getType(), britishBattleship.getOwner()).getHitPoints(),
        is(3));
  }

  @Test
  void theExtraHitPointAbsorbsAHit() {
    final CasualtyList twoHits = StickyHits.getDefaultCasualties(List.of(britishBattleship), 2);

    assertThat(twoHits.getKilled(), is(empty()));
    assertThat(twoHits.getDamaged(), contains(britishBattleship, britishBattleship));

    final CasualtyList germanTwoHits =
        StickyHits.getDefaultCasualties(List.of(germanBattleship), 2);
    assertThat(germanTwoHits.getKilled(), contains(germanBattleship));
  }
}
