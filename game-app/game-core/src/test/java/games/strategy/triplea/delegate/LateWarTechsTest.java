package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.GameDataTestUtil.battleship;
import static games.strategy.triplea.delegate.GameDataTestUtil.british;
import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.GameDataTestUtil.russians;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.battle.BattleTracker;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import org.junit.jupiter.api.Test;

/** Flames and Steel: Yalta and Naval Wasserfall techs (Night Combat was removed in draft 76). */
class LateWarTechsTest extends AbstractClientSettingTestCase {

  @Test
  void yaltaGivesAnAllysConquestToTheUssr() {
    final GameData data = TestMapGameData.REVISED.getGameData();
    final GamePlayer british = british(data);
    final Territory easternEurope = territory("Eastern Europe", data);
    final Territory westernEurope = territory("Western Europe", data);
    removeFrom(easternEurope, easternEurope.getUnits());
    removeFrom(westernEurope, westernEurope.getUnits());
    giveTech(
        data, russians(data), "superSub", "alliedConquestTerritories", easternEurope.getName());

    new BattleTracker().takeOver(easternEurope, british, newDelegateBridge(british), null, null);
    new BattleTracker().takeOver(westernEurope, british, newDelegateBridge(british), null, null);

    assertThat(easternEurope.getOwner(), is(russians(data)));
    assertThat(westernEurope.getOwner(), is(british));
  }

  @Test
  void navalWasserfallDoublesShipsFiringBackAtAircraft() {
    final GameData data = TestMapGameData.REVISED.getGameData();
    final Unit germanShip = battleship(data).create(1, germans(data)).get(0);
    final Unit britishShip = battleship(data).create(1, british(data)).get(0);
    giveTech(data, germans(data), "superSub", "airFireBackMultiplier", "2");

    assertThat(
        AirCombat.shipFireBackPower(germanShip), is(2 * AirCombat.shipFireBackPower(britishShip)));
  }

  private static void giveTech(
      final GameData data,
      final GamePlayer player,
      final String techProperty,
      final String option,
      final String value) {
    final TechAdvance tech = data.getTechnologyFrontier().getAdvanceByProperty(techProperty);
    TechAbilityAttachment ability = TechAbilityAttachment.get(tech);
    if (ability == null) {
      ability = new TechAbilityAttachment(Constants.TECH_ABILITY_ATTACHMENT_NAME, tech, data);
      tech.addAttachment(Constants.TECH_ABILITY_ATTACHMENT_NAME, ability);
    }
    set(ability, option, value);
    TechTracker.addAdvance(player, newDelegateBridge(player), tech);
  }

  private static void set(
      final TechAbilityAttachment attachment, final String property, final String value) {
    try {
      attachment.getPropertyOrThrow(property).setValue(value);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
