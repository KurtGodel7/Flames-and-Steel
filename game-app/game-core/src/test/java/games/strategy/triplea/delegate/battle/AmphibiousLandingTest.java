package games.strategy.triplea.delegate.battle;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.artillery;
import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.GameDataTestUtil.russians;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static games.strategy.triplea.delegate.MockDelegateBridge.whenGetRandom;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.delegate.AbstractMoveDelegate;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.battle.steps.BattleStep;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel request 55 on the WW2v3 test map: attacking land units unloaded from sea hold
 * fire (and give no support) in round 1 and fire from round 2; exempt types and units that came
 * over land fire in round 1; defenders always fire. Dice are 0-based: 0 hits, 5 misses.
 */
class AmphibiousLandingTest extends AbstractClientSettingTestCase {
  private final GameData gameData = TestMapGameData.WW2V3_1941.getGameData();
  private final GamePlayer germans = germans(gameData);
  private final GamePlayer russians = russians(gameData);
  private final Territory site = territory("Karelia S.S.R.", gameData);

  /** Attacker's dice per roll, in order. */
  private final List<Integer> germanDice = new ArrayList<>();

  /** Defender's dice per roll, in order. */
  private final List<Integer> russianDice = new ArrayList<>();

  @BeforeEach
  void setUp() {
    gameData.getProperties().set(AmphibiousLanding.EXEMPT_PROPERTY, "marine:rikusentai");
    removeFrom(site, site.getUnits());
  }

  @Test
  void tenByLandAndFourBySeaRollTenThenFourteen() {
    attackers(infantry(gameData).create(10, germans), false);
    attackers(infantry(gameData).create(4, germans), true);
    defenders(infantry(gameData).create(6, russians));
    // round 1 and 2: everyone misses; round 3: the Germans hit with everything
    final IDelegateBridge bridge = bridge(2);

    battle(bridge).fight(bridge);

    assertThat(germanDice.subList(0, 2), contains(10, 14));
    assertThat(russianDice.subList(0, 2), contains(6, 6));
  }

  @Test
  void landingUnitsCanBeTakenAsCasualtiesInRoundOne() {
    attackers(infantry(gameData).create(2, germans), true);
    defenders(infantry(gameData).create(2, russians));
    final IDelegateBridge bridge = newDelegateBridge(germans);
    advanceToStep(bridge, "germanBattle");
    givenFirstUnitChosenAsCasualty(bridge);
    // round 1: one Russian die hits; afterwards every die hits
    whenGetRandom(bridge)
        .thenAnswer(
            invocation -> {
              final int count = invocation.getArgument(1);
              final GamePlayer player = invocation.getArgument(2);
              final List<Integer> rolls = player == germans ? germanDice : russianDice;
              rolls.add(count);
              final int[] values = new int[count];
              if (player == russians && rolls.size() == 1) {
                Arrays.fill(values, 5);
                values[0] = 0;
              }
              return values;
            });

    battle(bridge).fight(bridge);

    // round 1: no German dice and the Russians' 2 dice killed 1 landing infantry, so in round 2
    // one German infantry is left to fire
    assertThat(russianDice.get(0), is(2));
    assertThat(germanDice.get(0), is(1));
  }

  @Test
  void exemptTypesFireInRoundOne() {
    gameData.getProperties().set(AmphibiousLanding.EXEMPT_PROPERTY, "artillery");
    attackers(artillery(gameData).create(2, germans), true);
    attackers(infantry(gameData).create(2, germans), true);
    defenders(infantry(gameData).create(6, russians));
    final IDelegateBridge bridge = bridge(1);

    battle(bridge).fight(bridge);

    assertThat(germanDice.subList(0, 2), contains(2, 4));
  }

  @Test
  void landingArtilleryGivesNoSupportInRoundOne() {
    attackers(infantry(gameData).create(1, germans), false);
    attackers(artillery(gameData).create(1, germans), true);
    final List<Unit> defenders = defenders(infantry(gameData).create(1, russians));
    final IDelegateBridge bridge = newDelegateBridge(germans);
    advanceToStep(bridge, "germanBattle");
    givenFirstUnitChosenAsCasualty(bridge);
    whenGetRandom(bridge)
        .thenAnswer(
            invocation -> {
              final int count = invocation.getArgument(1);
              final GamePlayer player = invocation.getArgument(2);
              (player == germans ? germanDice : russianDice).add(count);
              final int[] values = new int[count];
              // Germans roll 1 (would hit at 2 with artillery support); Russians miss
              Arrays.fill(values, player == germans ? 1 : 5);
              return values;
            });

    battle(bridge).fight(bridge);

    // round 1: the infantry (unsupported, attack 1) missed with a 1; round 2: supported, it hits
    assertThat(germanDice.subList(0, 2), contains(1, 2));
    assertThat(site.getUnits().containsAll(defenders), is(false));
    assertThat(russianDice.size(), is(2));
  }

  @Test
  void withoutThePropertyLandingUnitsFireInRoundOne() {
    gameData.getProperties().set(AmphibiousLanding.EXEMPT_PROPERTY, null);
    attackers(infantry(gameData).create(4, germans), true);
    defenders(infantry(gameData).create(6, russians));
    final IDelegateBridge bridge = bridge(0);

    battle(bridge).fight(bridge);

    assertThat(germanDice.get(0), is(4));
  }

  @Test
  void stepIsListedOnlyInRoundOneWithLandingUnits() {
    attackers(infantry(gameData).create(1, germans), true);
    defenders(infantry(gameData).create(1, russians));
    final MustFightBattle battle = battle(newDelegateBridge(germans));

    assertThat(AmphibiousLanding.holdingFire(battle).size(), is(1));
    assertThat(
        new games.strategy.triplea.delegate.battle.steps.fire.AmphibiousHoldFire(battle, battle)
            .getAllStepDetails().stream().map(BattleStep.StepDetails::getName).toList(),
        contains("Germans landing from the sea hold fire"));
    assertThat(
        AmphibiousLanding.logLine(4), is("4 units landing from the sea hold fire this round"));
  }

  private List<Unit> attackers(final List<Unit> units, final boolean bySea) {
    units.forEach(u -> u.setWasAmphibious(bySea));
    addTo(site, units);
    return units;
  }

  private List<Unit> defenders(final List<Unit> units) {
    addTo(site, units);
    return units;
  }

  /**
   * A bridge where every die misses for the first {@code missRounds} rounds of both sides, then
   * every die hits.
   */
  private IDelegateBridge bridge(final int missRounds) {
    final IDelegateBridge bridge = newDelegateBridge(germans);
    advanceToStep(bridge, "germanBattle");
    givenFirstUnitChosenAsCasualty(bridge);
    whenGetRandom(bridge)
        .thenAnswer(
            invocation -> {
              final int count = invocation.getArgument(1);
              final GamePlayer player = invocation.getArgument(2);
              final List<Integer> rolls = player == germans ? germanDice : russianDice;
              rolls.add(count);
              final int[] values = new int[count];
              Arrays.fill(values, rolls.size() <= missRounds ? 5 : (player == germans ? 0 : 5));
              return values;
            });
    return bridge;
  }

  private MustFightBattle battle(final IDelegateBridge bridge) {
    final BattleTracker tracker = AbstractMoveDelegate.getBattleTracker(gameData);
    final MustFightBattle battle = new MustFightBattle(site, germans, gameData, tracker);
    battle.setUnits(
        site.getUnitCollection().getMatches(Matches.unitIsOwnedBy(russians)),
        site.getUnitCollection().getMatches(Matches.unitIsOwnedBy(germans)),
        List.of(),
        russians,
        List.of());
    tracker
        .getBattleRecords()
        .addBattle(germans, battle.getBattleId(), site, battle.getBattleType());
    return battle;
  }

  private static void givenFirstUnitChosenAsCasualty(final IDelegateBridge bridge) {
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
              final Collection<Unit> selectFrom = invocation.getArgument(0);
              final int count = invocation.getArgument(2);
              final List<Unit> picks = new ArrayList<>(selectFrom).subList(0, count);
              return new CasualtyDetails(picks, List.of(), false);
            });
  }
}
