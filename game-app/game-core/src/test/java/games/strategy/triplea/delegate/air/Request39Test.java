package games.strategy.triplea.delegate.air;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechDescriptions;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Request 39 on the installed map: bonus dice instead of doubling, "set" techs that never lower a
 * bonus, and the texts that show them. The two naval rounds are in AirCombatDelegateTest.
 */
class Request39Test {
  private GameData data;
  private GamePlayer germans;
  private UnitType dive;
  private UnitType torpedo;
  private UnitType medium;

  @BeforeEach
  void setUp() {
    data = BasedMap.load();
    germans = data.getPlayerList().getPlayerId("Germans");
    dive = data.getUnitTypeList().getUnitTypeOrThrow("diveBomber");
    torpedo = data.getUnitTypeList().getUnitTypeOrThrow("torpedoBomber");
    medium = data.getUnitTypeList().getUnitTypeOrThrow("mediumBomber");
  }

  private Unit unit(final UnitType type) {
    return type.create(1, germans).get(0);
  }

  /** Techs Germany doesn't have yet, with a tech ability to change for the test. */
  private List<TechAdvance> spareTechs(final int n) {
    final java.util.Collection<TechAdvance> owned =
        TechTracker.getCurrentTechAdvances(germans, data.getTechnologyFrontier());
    final List<TechAdvance> result = new ArrayList<>();
    for (final TechAdvance t : data.getTechnologyFrontier().getTechs()) {
      if (result.size() < n && !owned.contains(t) && TechAbilityAttachment.get(t) != null) {
        result.add(t);
      }
    }
    return result;
  }

  private void set(final TechAdvance tech, final String option, final String value) {
    try {
      final TechAbilityAttachment a = TechAbilityAttachment.get(tech);
      a.getPropertyOrThrow(option).resetValue();
      a.getPropertyOrThrow(option).setValue(value);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void grant(final TechAdvance tech) {
    TechTracker.addAdvance(germans, newDelegateBridge(germans), tech);
    data.getTechTracker().clearCache();
  }

  @Test
  void aDiveBomberRollsThreePlusOneAgainstLandAndThreeAtSea() {
    assertThat(AirCombat.strikeText(unit(dive), TargetKind.LAND), is("3 + 1"));
    assertThat(AirCombat.strikePower(unit(dive), TargetKind.LAND), is(4));
    assertThat(AirCombat.strikeText(unit(dive), TargetKind.SURFACE), is("3"));
    assertThat(AirCombat.strikeRounds(TargetKind.SURFACE), is(2));
    assertThat(AirCombat.strikeRounds(TargetKind.SUBS), is(2));
    assertThat(AirCombat.strikeRounds(TargetKind.LAND), is(1));
    // torpedo bombers: a naval bonus against surface ships, none against submarines
    assertThat(AirCombat.strikeText(unit(torpedo), TargetKind.SURFACE), is("3 + 1"));
    assertThat(AirCombat.strikeText(unit(torpedo), TargetKind.SUBS), is("3"));
    assertThat(AirCombat.strikeText(unit(medium), TargetKind.SURFACE), is("3"));
    assertThat(AirCombat.airValuesText(dive, germans), containsString("against land 3 + 1"));
    assertThat(AirCombat.airValuesText(dive, germans), not(containsString("doubled")));
  }

  @Test
  void setTechsRaiseTheBonusAndNeverLowerItAndAddsComeOnTop() {
    final List<TechAdvance> techs = spareTechs(4);
    final TechAdvance stuka = techs.get(0);
    final TechAdvance fw190g = techs.get(1);
    final TechAdvance lower = techs.get(2);
    final TechAdvance asm = techs.get(3);
    set(stuka, "landBonusSet", "2:diveBomber");
    set(fw190g, "landBonusSet", "3:diveBomber");
    set(lower, "landBonusSet", "2:diveBomber");
    set(asm, "navalBonus", "2:mediumBomber");
    grant(stuka);
    assertThat(AirCombat.strikeText(unit(dive), TargetKind.LAND), is("3 + 2"));
    grant(fw190g);
    // Stuka then Fw-190G: 3, not 5
    assertThat(AirCombat.strikeText(unit(dive), TargetKind.LAND), is("3 + 3"));
    grant(lower);
    assertThat(AirCombat.strikeText(unit(dive), TargetKind.LAND), is("3 + 3"));
    // an addition: medium bomber naval bonus +2 (Air-to-Surface Missile)
    grant(asm);
    assertThat(AirCombat.strikeText(unit(medium), TargetKind.SURFACE), is("3 + 2"));
    assertThat(AirCombat.strikePower(unit(medium), TargetKind.SURFACE), is(5));
    // other nations are untouched
    final GamePlayer japanese = data.getPlayerList().getPlayerId("Japanese");
    assertThat(AirCombat.strikeText(dive.create(1, japanese).get(0), TargetKind.LAND), is("3 + 1"));
  }

  @Test
  void techDescriptionsSayWhatTheBonusTechsDo() {
    final List<TechAdvance> techs = spareTechs(2);
    set(techs.get(0), "landBonusSet", "3:diveBomber");
    set(techs.get(1), "navalBonus", "1:diveBomber");
    assertThat(
        TechDescriptions.describe(techs.get(0)),
        containsString("bonus die against land units is now 3"));
    assertThat(
        TechDescriptions.describe(techs.get(1)),
        containsString("+1 to dive bombers' bonus die against ships"));
  }
}
