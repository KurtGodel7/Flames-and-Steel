package games.strategy.triplea.delegate.research;

import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static games.strategy.triplea.delegate.MockDelegateBridge.whenGetRandom;
import static games.strategy.triplea.delegate.MockDelegateBridge.withValues;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.TechnologyFrontier;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.attachments.TerritoryAttachment;
import games.strategy.triplea.delegate.ResearchCenterDelegate;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.delegate.data.ResearchResults;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel research centres. Uses Global 1940's twelve German techs, regrouped into six
 * categories A to F of two techs each.
 */
class ResearchCenterDelegateTest {
  private final GameData gameData = TestMapGameData.GLOBAL1940.getGameData();
  private final GamePlayer germans = germans(gameData);
  private final List<TechAdvance> allTechs = new ArrayList<>();
  private IDelegateBridge bridge;

  @BeforeEach
  void setUp() {
    // empty the map's own categories and build A..F
    for (final TechnologyFrontier category : germans.getTechnologyFrontierList().getFrontiers()) {
      for (final TechAdvance tech : new ArrayList<>(category.getTechs())) {
        allTechs.add(tech);
        category.removeAdvance(tech);
      }
    }
    for (int i = 0; i < 6; i++) {
      final TechnologyFrontier category =
          new TechnologyFrontier(String.valueOf((char) ('A' + i)), gameData);
      category.addAdvance(allTechs.get(2 * i));
      category.addAdvance(allTechs.get(2 * i + 1));
      germans.getTechnologyFrontierList().addTechnologyFrontier(category);
    }
    bridge = newDelegateBridge(germans);
  }

  @Test
  void primaryFocusHit() {
    setCenters(1);
    whenGetRandom(bridge).thenAnswer(withValues(0));

    final ResearchResults results = research(List.of("A|B"), -1);

    assertThat(results.getError().isPresent(), is(false));
    assertThat(stars(tech("A", 0)), is(1));
    assertThat(starsGainedIn("B", "C", "D", "E", "F"), is(0));
  }

  @Test
  void secondaryFocusHit() {
    setCenters(1);
    whenGetRandom(bridge).thenAnswer(withValues(5));

    research(List.of("A|B"), -1);

    assertThat(stars(tech("B", 0)), is(1));
  }

  @Test
  void randomBreakthroughPicksAmongUnfinishedCategories() {
    setCenters(1);
    whenGetRandom(bridge).thenAnswer(withValues(9));
    when(bridge.getRandom(anyInt(), any(), any(), anyString())).thenReturn(3);

    research(List.of("A|B"), -1);

    assertThat(stars(tech("D", 0)), is(1));
  }

  @Test
  void aCategoryGainsAtMostOneStarPerTurn() {
    setCenters(2);
    // centre 1 rolls random, centre 2 hits its primary C
    whenGetRandom(bridge).thenAnswer(withValues(9, 0));
    // the random pick may not go to C: candidates are A, B, D, E, F; index 2 is D
    when(bridge.getRandom(anyInt(), any(), any(), anyString())).thenReturn(2);

    research(List.of("A|B", "C|E"), -1);

    assertThat(stars(tech("C", 0)), is(1));
    assertThat(stars(tech("D", 0)), is(1));
  }

  @Test
  void primaryOnlyWhenFewerThanTwoCategoriesPerCentre() {
    setCenters(2);
    finishCategories("D", "E", "F"); // 3 left for 2 centres
    whenGetRandom(bridge).thenAnswer(withValues(4, 4)); // 4 is primary in this mode

    final ResearchResults results = research(List.of("A|", "B|"), -1);

    assertThat(results.getError().isPresent(), is(false));
    assertThat(stars(tech("A", 0)), is(1));
    assertThat(stars(tech("B", 0)), is(1));
  }

  @Test
  void automaticWhenNoMoreCategoriesThanCentres() {
    setCenters(3);
    finishCategories("D", "E", "F"); // 3 left for 3 centres

    research(List.of("|", "|", "|"), -1);

    assertThat(stars(tech("A", 0)), is(1));
    assertThat(stars(tech("B", 0)), is(1));
    assertThat(stars(tech("C", 0)), is(1));
    verify(bridge, never()).getRandom(anyInt(), anyInt(), any(), any(), anyString());
  }

  @Test
  void lastStarUnlocksTechForActivation() {
    setCenters(1);
    setStarsRequired(tech("A", 0), 2);
    setStars(tech("A", 0), 1);
    whenGetRandom(bridge).thenAnswer(withValues(0));

    research(List.of("A|B"), -1);

    assertThat(ResearchCenters.isUnlocked(germans, tech("A", 0)), is(true));
    assertThat(gameData.getTechDelegate().getAdvances(germans), contains(tech("A", 0)));
    // the next star in A goes to the second tech
    assertThat(ResearchCenters.getNextTech(germans, category("A")).orElseThrow(), is(tech("A", 1)));
  }

  @Test
  void techAlreadyFullyStarredIsQueuedOnce() {
    setCenters(1);
    setStars(tech("A", 0), 1); // starting stars already complete it
    whenGetRandom(bridge).thenAnswer(withValues(0));

    research(List.of("C|B"), -1);

    assertThat(
        gameData.getTechDelegate().getAdvances(germans),
        containsInAnyOrder(tech("A", 0), tech("C", 0)));
  }

  @Test
  void nationStarCountOverridesTechStarCount() {
    setCenters(1);
    setStarsRequired(tech("A", 0), 5);
    final var overrides = new IntegerMap<String>();
    overrides.put(tech("A", 0).getName(), 1);
    gameData.performChange(
        ChangeFactory.attachmentPropertyChange(
            germans.getTechAttachment(), overrides, "researchStarsRequired"));
    whenGetRandom(bridge).thenAnswer(withValues(0));

    research(List.of("A|B"), -1);

    assertThat(ResearchCenters.isUnlocked(germans, tech("A", 0)), is(true));
  }

  @Test
  void starsGoToTechsInOrder() {
    setCenters(1);
    setStarsRequired(tech("A", 0), 3);
    whenGetRandom(bridge).thenAnswer(withValues(0));

    research(List.of("A|B"), -1);

    assertThat(stars(tech("A", 0)), is(1));
    assertThat(stars(tech("A", 1)), is(0));
  }

  @Test
  void focusMustBeDifferentCategories() {
    setCenters(2);

    final ResearchResults results = research(List.of("A|B", "B|C"), -1);

    assertThat(results.getError().isPresent(), is(true));
  }

  @Test
  void finishedCategoryCannotBeFocus() {
    setCenters(1);
    finishCategories("A");

    final ResearchResults results = research(List.of("A|B"), -1);

    assertThat(results.getError().isPresent(), is(true));
  }

  @Test
  void lostCapitalIdlesOneCentre() {
    setCenters(2);
    loseCapital();
    whenGetRandom(bridge).thenAnswer(withValues(0));

    assertThat(research(List.of("A|B", "C|D"), -1).getError().isPresent(), is(true));
    final ResearchResults results = research(List.of("A|B", "C|D"), 0);

    assertThat(results.getError().isPresent(), is(false));
    assertThat(stars(tech("A", 0)), is(0));
    assertThat(stars(tech("C", 0)), is(1));
  }

  @Test
  void bonusResearchRollAddsAnExtraStarEvenInAFocusCategory() {
    setCenters(1);
    final TechAdvance goettingen = allTechs.get(11);
    category("F").removeAdvance(goettingen);
    giveBonusRoll(goettingen, "50:A");
    setStarsRequired(tech("A", 0), 5);
    whenGetRandom(bridge).thenAnswer(withValues(0));
    when(bridge.getRandom(anyInt(), any(), any(), anyString())).thenReturn(49);

    research(List.of("A|B"), -1);

    assertThat(stars(tech("A", 0)), is(2));
  }

  @Test
  void failedBonusResearchRollGivesNothing() {
    setCenters(1);
    final TechAdvance goettingen = allTechs.get(11);
    category("F").removeAdvance(goettingen);
    giveBonusRoll(goettingen, "50:A");
    whenGetRandom(bridge).thenAnswer(withValues(5));
    when(bridge.getRandom(anyInt(), any(), any(), anyString())).thenReturn(50);

    research(List.of("C|B"), -1);

    assertThat(stars(tech("A", 0)), is(0));
    assertThat(stars(tech("B", 0)), is(1));
  }

  @Test
  void defaultFocusReplacesFinishedAndRepeatedCategories() {
    setCenters(2);
    finishCategories("A");
    gameData.performChange(
        ChangeFactory.attachmentPropertyChange(
            germans.getTechAttachment(), new ArrayList<>(List.of("A|B", "B|C")), "researchFocus"));

    final List<String> focus = ResearchCenters.getDefaultFocus(germans, -1, gameData.getMap());

    assertThat(focus, contains("D|E", "B|C"));
    assertThat(
        ResearchCenters.validateFocus(germans, focus, -1, gameData.getMap()).isPresent(),
        is(false));
  }

  @Test
  void focusIsRemembered() {
    setCenters(2);
    whenGetRandom(bridge).thenAnswer(withValues(0, 0));

    research(List.of("A|B", "C|D"), -1);

    assertThat(germans.getTechAttachment().getResearchFocus(), containsInAnyOrder("A|B", "C|D"));
  }

  private ResearchResults research(final List<String> focus, final int idleCenter) {
    final ResearchCenterDelegate delegate = new ResearchCenterDelegate();
    delegate.initialize("research", "Research");
    delegate.setDelegateBridgeAndPlayer(bridge);
    delegate.start();
    return delegate.research(focus, idleCenter);
  }

  private void setCenters(final int centers) {
    gameData.performChange(
        ChangeFactory.attachmentPropertyChange(
            germans.getTechAttachment(), centers, "researchCenters"));
  }

  private void setStars(final TechAdvance tech, final int stars) {
    final var map = new IntegerMap<>(germans.getTechAttachment().getResearchStars());
    map.put(tech.getName(), stars);
    gameData.performChange(
        ChangeFactory.attachmentPropertyChange(germans.getTechAttachment(), map, "researchStars"));
  }

  private void setStarsRequired(final TechAdvance tech, final int stars) {
    gameData.performChange(
        ChangeFactory.attachmentPropertyChange(
            techAbilityAttachment(tech), stars, "researchStarsRequired"));
  }

  private void giveBonusRoll(final TechAdvance tech, final String value) {
    try {
      techAbilityAttachment(tech).getPropertyOrThrow("bonusResearchRoll").setValue(value);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
    TechTracker.addAdvance(germans, bridge, tech);
  }

  private TechAbilityAttachment techAbilityAttachment(final TechAdvance tech) {
    TechAbilityAttachment attachment = TechAbilityAttachment.get(tech);
    if (attachment == null) {
      attachment =
          new TechAbilityAttachment(Constants.TECH_ABILITY_ATTACHMENT_NAME, tech, gameData);
      tech.addAttachment(Constants.TECH_ABILITY_ATTACHMENT_NAME, attachment);
    }
    return attachment;
  }

  private void finishCategories(final String... names) {
    for (final String name : names) {
      for (final TechAdvance tech : category(name).getTechs()) {
        setStars(tech, ResearchCenters.getStarsRequired(germans, tech));
      }
    }
  }

  private void loseCapital() {
    for (final Territory capital : TerritoryAttachment.getAllCapitals(germans, gameData.getMap())) {
      gameData.performChange(
          ChangeFactory.changeOwner(
              capital, gameData.getPlayerList().getPlayerId(Constants.PLAYER_NAME_RUSSIANS)));
    }
  }

  private TechnologyFrontier category(final String name) {
    return ResearchCenters.getCategory(germans, name).orElseThrow();
  }

  private TechAdvance tech(final String category, final int index) {
    return category(category).getTechs().get(index);
  }

  private int stars(final TechAdvance tech) {
    return ResearchCenters.getStars(germans, tech);
  }

  private int starsGainedIn(final String... categories) {
    int total = 0;
    for (final String name : categories) {
      for (final TechAdvance tech : category(name).getTechs()) {
        total += stars(tech);
      }
    }
    return total;
  }
}
