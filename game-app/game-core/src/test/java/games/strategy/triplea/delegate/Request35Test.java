package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.TechnologyFrontier;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.research.ResearchCenters;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Request 35 parts 1 and 2 on the installed map: tech descriptions and the Soviet air penalty. */
class Request35Test {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private static final List<String> FIGHTER_TYPES =
      List.of("fighter", "advancedFighter", "earlyJet", "jet");
  private GameData data;
  private GamePlayer russians;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = games.strategy.triplea.delegate.air.BasedMap.load();
    russians = data.getPlayerList().getPlayerId("Russians");
  }

  // ---- part 1

  @Test
  void everyTechHasADescription() {
    final List<String> missing = new ArrayList<>();
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      for (final TechnologyFrontier category : ResearchCenters.getCategories(player)) {
        for (final TechAdvance tech : category.getTechs()) {
          if (TechDescriptions.describe(tech, player).isBlank()) {
            missing.add(player.getName() + " " + tech.getName());
          }
        }
      }
    }
    for (final TechAdvance tech : data.getTechnologyFrontier().getTechs()) {
      if (TechDescriptions.describeAnyNation(tech).isBlank()) {
        missing.add(tech.getName());
      }
    }
    assertThat(missing, is(empty()));
  }

  @Test
  void aNationsOwnDescriptionComesFirst() {
    final TechAdvance tech = data.getTechnologyFrontier().getAdvanceByName("Propaganda1");
    Assumptions.assumeTrue(tech != null, "no Propaganda1");
    final String own =
        data.getProperties().get(TechDescriptions.PREFIX + "Propaganda1 Russians", "");
    Assumptions.assumeTrue(!own.isBlank(), "no Soviet override for Propaganda1");
    assertThat(TechDescriptions.describe(tech, russians), is(own));
    data.getProperties().set(TechDescriptions.PREFIX + "Propaganda1 Russians", "");
    assertThat(
        TechDescriptions.describe(tech, russians),
        is(data.getProperties().get(TechDescriptions.PREFIX + "Propaganda1", "")));
  }

  // ---- part 2

  /** What the cloud session will add to the Soviet techAttachment. */
  private void sovietInterceptionPenalty() throws Exception {
    if (!russians.getTechAttachment().getInterceptionBonus().isEmpty()) {
      return; // the XML has it now
    }
    for (final String type : FIGHTER_TYPES) {
      russians.getTechAttachment().getPropertyOrThrow("interceptionBonus").setValue("-2:" + type);
    }
    data.getTechTracker().clearCache();
  }

  private void grant(final String tech) {
    TechTracker.addAdvance(
        russians, newDelegateBridge(russians), data.getTechnologyFrontier().getAdvanceByName(tech));
    data.getTechTracker().clearCache();
  }

  private List<Integer> values(final String type, final GamePlayer owner) {
    final Unit unit = data.getUnitTypeList().getUnitTypeOrThrow(type).create(1, owner).get(0);
    return List.of(AirCombat.dogfightValue(unit), AirCombat.interceptionValue(unit));
  }

  @Test
  void sovietFightersStartAtMinusTwoAndPilotTrainingThenRadiosRestoreThem() throws Exception {
    sovietInterceptionPenalty();
    assertThat(values("fighter", russians), is(List.of(1, 1)));
    assertThat(values("advancedFighter", russians), is(List.of(2, 2)));
    assertThat(values("earlyJet", russians), is(List.of(2, 2)));
    assertThat(values("jet", russians), is(List.of(3, 3)));
    grant("PilotTraining");
    assertThat(values("fighter", russians), is(List.of(2, 2)));
    grant("Radios");
    assertThat(values("fighter", russians), is(List.of(3, 3)));
    assertThat(values("jet", russians), is(List.of(5, 5)));
    // other nations are unaffected
    assertThat(values("fighter", data.getPlayerList().getPlayerId("Germans")), is(List.of(3, 3)));
    // the tooltip line shows the values in force
    assertThat(
        AirCombat.airValuesText(data.getUnitTypeList().getUnitTypeOrThrow("fighter"), russians),
        containsString("dogfight 3, interception 3"));
  }

  @Test
  void neverBelowOne() throws Exception {
    sovietInterceptionPenalty();
    russians.getTechAttachment().getPropertyOrThrow("interceptionBonus").setValue("-5:fighter");
    data.getTechTracker().clearCache();
    assertThat(values("fighter", russians).get(1), is(1));
  }
}
