package games.strategy.triplea.delegate.power.calculator;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.attachments.FireTriggerParams;
import games.strategy.triplea.attachments.TriggerAttachment;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.delegate.battle.BattleState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Draft 111 on the installed map: a light gun is supported on defense from the start, and on attack
 * only once its nation has Unrelenting Attack (the XML trigger swaps the attack supports to the
 * "All" versions that list lightGun). One field gun + one light gun attacking: 1 without, 2 with.
 */
class UnrelentingAttackTest {
  private static final FireTriggerParams FIRE =
      new FireTriggerParams("", "", false, false, false, false);
  private GameData data;

  @BeforeEach
  void setUp() {
    data = games.strategy.triplea.delegate.air.BasedMap.load();
  }

  private int lightGunValue(
      final GamePlayer owner, final String supporter, final BattleState.Side side) {
    final List<Unit> units = new ArrayList<>(type("lightGun").create(1, owner));
    units.addAll(type(supporter).create(1, owner));
    final GamePlayer enemy =
        data.getPlayerList().getPlayerId("Russians").equals(owner)
            ? data.getPlayerList().getPlayerId("Germans")
            : data.getPlayerList().getPlayerId("Russians");
    final CombatValue value =
        CombatValueBuilder.mainCombatValue()
            .enemyUnits(type("infantry").create(1, enemy))
            .friendlyUnits(units)
            .side(side)
            .gameSequence(data.getSequence())
            .supportAttachments(data.getUnitTypeList().getSupportRules())
            .lhtrHeavyBombers(false)
            .gameDiceSides(data.getDiceSides())
            .territoryEffects(List.of())
            .build();
    return PowerStrengthAndRolls.build(units, value)
        .getTotalStrengthAndTotalRollsByUnit()
        .get(units.get(0))
        .getStrength();
  }

  private games.strategy.engine.data.UnitType type(final String name) {
    return data.getUnitTypeList().getUnitType(name).orElseThrow();
  }

  private void grantUnrelentingAttack(final GamePlayer player) {
    final TechAdvance tech =
        TechAdvance.getTechAdvances(data.getTechnologyFrontier()).stream()
            .filter(t -> t.getName().equals("UnrelentingAttack"))
            .findFirst()
            .orElseThrow();
    final var bridge = newDelegateBridge(player);
    TechTracker.addAdvance(player, bridge, tech);
    data.getTechTracker().clearCache();
    // what happens at the nation's end of turn: the XML trigger swaps the supports
    final TriggerAttachment trigger =
        TriggerAttachment.get(
            player, "triggerAttachment_" + player.getName() + "_support_UnrelentingAttack");
    TriggerAttachment.triggerSupportChange(Set.of(trigger), bridge, FIRE);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Germans",
        "Russians",
        "Japanese",
        "British",
        "Italians",
        "Americans",
        "AmericansPacific"
      })
  void fieldGunSupportsALightGunOnAttackOnlyWithUnrelentingAttack(final String nation) {
    final GamePlayer player = data.getPlayerList().getPlayerId(nation);
    assertThat(lightGunValue(player, "fieldGun", BattleState.Side.OFFENSE), is(1));
    assertThat(lightGunValue(player, "heavyGun", BattleState.Side.DEFENSE), is(2));
    grantUnrelentingAttack(player);
    assertThat(lightGunValue(player, "fieldGun", BattleState.Side.OFFENSE), is(2));
    assertThat(lightGunValue(player, "heavyGun", BattleState.Side.DEFENSE), is(2));
  }
}
