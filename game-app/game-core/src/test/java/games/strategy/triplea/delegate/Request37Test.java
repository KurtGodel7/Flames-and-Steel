package games.strategy.triplea.delegate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.formatter.TechNames;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Request 37 on the installed map: nation-level attack bonus, tech names from the XML. */
class Request37Test {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer russians;
  private GamePlayer germans;
  private UnitType diveBomber;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = games.strategy.triplea.delegate.air.BasedMap.load();
    russians = data.getPlayerList().getPlayerId("Russians");
    germans = data.getPlayerList().getPlayerId("Germans");
    diveBomber = data.getUnitTypeList().getUnitTypeOrThrow("diveBomber");
  }

  // ---- part 1

  /** What the cloud session will add to the Soviet techAttachment. */
  private void sovietDiveBomberPenalty() throws Exception {
    if (!russians.getTechAttachment().getAttackBonus().isEmpty()) {
      return; // the XML has it now
    }
    russians.getTechAttachment().getPropertyOrThrow("attackBonus").setValue("-1:diveBomber");
    data.getTechTracker().clearCache();
  }

  private Unit unit(final GamePlayer owner) {
    return diveBomber.create(1, owner).get(0);
  }

  @Test
  void sovietDiveBombersAttackAtTwoAndThreeAgainstLand() throws Exception {
    sovietDiveBomberPenalty();
    assertThat(diveBomber.getUnitAttachment().getAttack(russians), is(2));
    // request 39: 2 + the land bonus die 1
    assertThat(AirCombat.strikePower(unit(russians), TargetKind.LAND), is(3));
    // other nations keep 3 (3 + 1 against land)
    assertThat(diveBomber.getUnitAttachment().getAttack(germans), is(3));
    assertThat(AirCombat.strikePower(unit(germans), TargetKind.LAND), is(4));
    // the unit tooltip and the air values line show it
    assertThat(
        diveBomber.getUnitAttachment().toStringShortAndOnlyImportantDifferences(russians),
        containsString("2 |"));
    assertThat(AirCombat.airValuesText(diveBomber, russians), containsString("attack 2"));
    assertThat(AirCombat.airValuesText(diveBomber, germans), containsString("attack 3"));
  }

  @Test
  void techBonusesStillAddOnTopAndNothingGoesBelowZero() throws Exception {
    sovietDiveBomberPenalty();
    russians.getTechAttachment().getPropertyOrThrow("attackBonus").setValue("-9:diveBomber");
    russians.getTechAttachment().getPropertyOrThrow("defenseBonus").setValue("-9:diveBomber");
    data.getTechTracker().clearCache();
    assertThat(diveBomber.getUnitAttachment().getAttack(russians), is(0));
    assertThat(diveBomber.getUnitAttachment().getDefense(russians), is(0));
    // request 39: the bonus die stays
    assertThat(AirCombat.strikePower(unit(russians), TargetKind.LAND), is(1));
  }

  @Test
  void defenseBonusWorksTheSameWay() throws Exception {
    final int before = diveBomber.getUnitAttachment().getDefense(russians);
    russians.getTechAttachment().getPropertyOrThrow("defenseBonus").setValue("1:diveBomber");
    data.getTechTracker().clearCache();
    assertThat(diveBomber.getUnitAttachment().getDefense(russians), is(before + 1));
    assertThat(diveBomber.getUnitAttachment().getDefense(germans), is(before));
  }

  // ---- part 2

  private TechAdvance anyTech() {
    return data.getTechnologyFrontier().getTechs().get(0);
  }

  @Test
  void withoutAPropertyTheNameIsBuiltFromTheXmlName() {
    assertThat(TechNames.readable("AirborneInterceptRadar"), is("Airborne Intercept Radar"));
    final TechAdvance tech = anyTech();
    Assumptions.assumeTrue(
        data.getProperties().get(TechNames.PREFIX + tech.getName(), "").isBlank(),
        "the XML names this tech");
    assertThat(TechNames.display(tech, germans), is(TechNames.readable(tech.getName())));
  }

  @Test
  void aNationsOwnNameComesFirstAndOthersSeeThePlainName() {
    final TechAdvance tech = anyTech();
    data.getProperties().set(TechNames.PREFIX + tech.getName(), "FD-2 Radar");
    data.getProperties().set(TechNames.PREFIX + tech.getName() + " British", "AI Mk. IV Radar");
    final GamePlayer british = data.getPlayerList().getPlayerId("British");
    assertThat(TechNames.display(tech, british), is("AI Mk. IV Radar"));
    assertThat(TechNames.display(tech, germans), is("FD-2 Radar"));
    assertThat(TechNames.display(tech, null), is("FD-2 Radar"));
    assertThat(
        TechNames.nationNames(tech, data.getPlayerList().getPlayers()).get(0),
        containsString("AI Mk. IV Radar"));
  }

  @Test
  void historyUsesTheNationsName() {
    final TechAdvance tech = anyTech();
    data.getProperties().set(TechNames.PREFIX + tech.getName() + " Germans", "Würzburg Radar");
    assertThat(
        TechNames.list(List.of(tech, data.getTechnologyFrontier().getTechs().get(1)), germans),
        containsString("Würzburg Radar and "));
  }
}
