package games.strategy.triplea.delegate.air;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechDescriptions;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Request 43 on the installed map: one nation's bombers per factory, the nation's starting bonus
 * die ("navalBonusBase"), FIDO's bonus die against submarines and the texts that show them.
 * Combat Air Patrol and the VT fuze in battle are in AirCombatDelegateTest.
 */
class Request43Test {
  private GameData data;
  private GamePlayer americans;
  private GamePlayer germans;
  private UnitType torpedo;

  @BeforeEach
  void setUp() {
    data = BasedMap.load();
    americans = data.getPlayerList().getPlayerId("Americans");
    germans = data.getPlayerList().getPlayerId("Germans");
    torpedo = data.getUnitTypeList().getUnitTypeOrThrow("torpedoBomber");
  }

  private Unit unit(final UnitType type, final GamePlayer owner) {
    return type.create(1, owner).get(0);
  }

  /** Techs the nation doesn't have yet, with a tech ability to change for the test. */
  private List<TechAdvance> spareTechs(final GamePlayer player, final int n) {
    final java.util.Collection<TechAdvance> owned =
        TechTracker.getCurrentTechAdvances(player, data.getTechnologyFrontier());
    final List<TechAdvance> result = new ArrayList<>();
    for (final TechAdvance t : data.getTechnologyFrontier().getTechs()) {
      if (result.size() < n && !owned.contains(t) && TechAbilityAttachment.get(t) != null) {
        result.add(t);
      }
    }
    return result;
  }

  private static void set(
      final games.strategy.engine.data.DefaultAttachment attachment,
      final String option,
      final String value) {
    try {
      attachment.getPropertyOrThrow(option).resetValue();
      attachment.getPropertyOrThrow(option).setValue(value);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void grant(final GamePlayer player, final TechAdvance tech) {
    TechTracker.addAdvance(player, newDelegateBridge(player), tech);
    data.getTechTracker().clearCache();
  }

  @Test
  void americanTorpedoBombersStartAtZeroThenMark13ThenTbfAvenger() {
    set(americans.getTechAttachment(), "navalBonusBase", "0:torpedoBomber");
    data.getTechTracker().clearCache();
    assertThat(AirCombat.strikeText(unit(torpedo, americans), TargetKind.SURFACE), is("3"));
    // everyone else keeps the unit's own 1
    assertThat(AirCombat.strikeText(unit(torpedo, germans), TargetKind.SURFACE), is("3 + 1"));
    final List<TechAdvance> techs = spareTechs(americans, 2);
    set(TechAbilityAttachment.get(techs.get(0)), "navalBonusSet", "1:torpedoBomber");
    set(TechAbilityAttachment.get(techs.get(1)), "navalBonusSet", "3:torpedoBomber");
    grant(americans, techs.get(0));
    assertThat(AirCombat.strikeText(unit(torpedo, americans), TargetKind.SURFACE), is("3 + 1"));
    grant(americans, techs.get(1));
    assertThat(AirCombat.strikeText(unit(torpedo, americans), TargetKind.SURFACE), is("3 + 3"));
    // two naval rounds of 6 against ships at the end
    assertThat(AirCombat.strikePower(unit(torpedo, americans), TargetKind.SURFACE), is(6));
  }

  @Test
  void fidoGivesTorpedoBombersABonusDieAgainstSubmarinesOnly() {
    assertThat(AirCombat.strikeText(unit(torpedo, americans), TargetKind.SUBS), is("3"));
    final TechAdvance fido = spareTechs(americans, 1).get(0);
    set(TechAbilityAttachment.get(fido), "subBonus", "1:torpedoBomber");
    grant(americans, fido);
    assertThat(AirCombat.strikeText(unit(torpedo, americans), TargetKind.SUBS), is("3 + 1"));
    assertThat(AirCombat.strikeRounds(TargetKind.SUBS), is(2));
    // against surface ships its naval bonus as before
    assertThat(AirCombat.strikeText(unit(torpedo, americans), TargetKind.SURFACE), is("3 + 1"));
    assertThat(
        AirCombat.airValuesText(torpedo, americans), containsString("against submarines 3 + 1"));
    assertThat(
        TechDescriptions.describe(fido),
        containsString("+1 to torpedo bombers' bonus die against submarines"));
  }

  @Test
  void combatAirPatrolAndTheFuzeAreDescribed() {
    final List<TechAdvance> techs = spareTechs(americans, 2);
    set(TechAbilityAttachment.get(techs.get(0)), "carrierInterception", "true");
    set(TechAbilityAttachment.get(techs.get(1)), "antiAircraftBonus", "1");
    assertThat(
        TechDescriptions.describe(techs.get(0)),
        containsString("carriers fire at enemy bombers over their sea zone"));
    assertThat(
        TechDescriptions.describe(techs.get(1)),
        containsString("+1 to your ships' value whenever they fire at aircraft"));
  }

  @Test
  void theEarlierAllyKeepsAFactoryAndTacticalStrikesStayMixed() {
    // two allies, in turn order
    final List<GamePlayer> order = AirPriority.turnOrder(data);
    GamePlayer earlier = null;
    GamePlayer later = null;
    for (int i = 0; i < order.size() && later == null; i++) {
      for (int j = i + 1; j < order.size() && later == null; j++) {
        if (AirCover.sameSide(order.get(i), order.get(j))) {
          earlier = order.get(i);
          later = order.get(j);
        }
      }
    }
    final Territory factory = data.getMap().getTerritoryOrThrow("Hamburg");
    final Territory base = data.getMap().getTerritories().get(0);
    final UnitType heavy = data.getUnitTypeList().getUnitTypeOrThrow("strategicBomber");
    final Target raid = new Target(factory, TargetKind.STRATEGIC);
    final Map<GamePlayer, List<TargetAssignment>> choices =
        Map.of(earlier, List.of(new TargetAssignment(base, heavy, 2, raid)));

    data.getProperties().set(AirPriority.ONE_NATION_PER_RAID, false);
    assertThat(AirPriority.raidTakenBy(later, raid, choices), is(Optional.empty()));

    data.getProperties().set(AirPriority.ONE_NATION_PER_RAID, true);
    assertThat(AirPriority.raidLocks(earlier, later), is(true));
    assertThat(AirPriority.raidLocks(later, earlier), is(false));
    assertThat(AirPriority.raidTakenBy(later, raid, choices), is(Optional.of(earlier)));
    // the earlier nation is never kept off by the later one
    assertThat(
        AirPriority.raidTakenBy(
            earlier, raid, Map.of(later, List.of(new TargetAssignment(base, heavy, 1, raid)))),
        is(Optional.empty()));
    // tactical strikes on the same territory may still be mixed
    assertThat(
        AirPriority.raidTakenBy(later, new Target(factory, TargetKind.LAND), choices),
        is(Optional.empty()));
    // an enemy's raid on the same factory keeps nobody off
    assertThat(AirPriority.raidLocks(germans, americans), is(false));
    assertThat(
        AirPriority.raidText(factory, earlier),
        is("Hamburg's factory is a " + AirCombat.adjective(earlier) + " raid this round."));
  }
}
