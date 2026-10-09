package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.battleship;
import static games.strategy.triplea.delegate.GameDataTestUtil.british;
import static games.strategy.triplea.delegate.GameDataTestUtil.destroyer;
import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.move;
import static games.strategy.triplea.delegate.GameDataTestUtil.moveDelegate;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.GameDataTestUtil.russians;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.battle.BattleTracker;
import games.strategy.triplea.delegate.battle.MustFightBattle;
import games.strategy.triplea.delegate.battle.steps.fire.NavalNightCombat;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Flames and Steel: Yalta, Naval Wasserfall and Night Combat techs. */
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

  @Test
  void nightCombatSinksShipsBeforeTheBattleWithoutReturnFire() {
    final GameData data = TestMapGameData.WW2V3_1941.getGameData();
    final GamePlayer germans = germans(data);
    final GamePlayer british = british(data);
    final Territory attacked = territory("31 Sea Zone", data);
    final Territory from = territory("32 Sea Zone", data);
    removeFrom(attacked, attacked.getUnits());
    removeFrom(from, from.getUnits());
    addTo(from, destroyer(data).create(1, germans));
    addTo(from, data.getUnitTypeList().getUnitTypeOrThrow("cruiser").create(1, germans));
    addTo(attacked, destroyer(data).create(1, british));
    giveTech(data, germans, "superSub", "nightCombat", "1:destroyer");
    final TechAbilityAttachment ability =
        TechAbilityAttachment.get(data.getTechnologyFrontier().getAdvanceByProperty("superSub"));
    set(ability, "nightCombat", "2:cruiser");
    final IDelegateBridge bridge = newDelegateBridge(germans);
    advanceToStep(bridge, "CombatMove");
    moveDelegate(data).setDelegateBridgeAndPlayer(bridge);
    moveDelegate(data).start();
    move(from.getUnits(), new Route(from, attacked));
    moveDelegate(data).end();
    final MustFightBattle battle =
        (MustFightBattle)
            AbstractMoveDelegate.getBattleTracker(data).getPendingNonBombingBattle(attacked);
    // night value 1 + 2 = 3: the low luck die (0) hits
    when(bridge.getRandom(anyInt(), any(), any(), anyString())).thenReturn(0);
    when(bridge
            .getRemotePlayer()
            .selectCasualties(
                any(),
                any(),
                anyInt(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean()))
        .thenAnswer(
            invocation -> {
              final Collection<Unit> from2 = invocation.getArgument(0);
              return new CasualtyDetails(List.of(from2.iterator().next()), List.of(), false);
            });

    new NavalNightCombat(battle, battle).execute(mock(ExecutionStack.class), bridge);

    // the British destroyer is gone before the battle proper; nothing fired back
    assertThat(attacked.getUnitCollection().countMatches(Matches.unitIsOwnedBy(british)), is(0));
    assertThat(attacked.getUnitCollection().countMatches(Matches.unitIsOwnedBy(germans)), is(2));
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
