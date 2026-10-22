package games.strategy.triplea.delegate.battle.casualty;

import static games.strategy.triplea.delegate.GameDataTestUtil.armour;
import static games.strategy.triplea.delegate.GameDataTestUtil.british;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.delegate.battle.casualty.StickyCasualtyChoice.Group;
import games.strategy.triplea.delegate.data.CasualtyList;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Request 28 part A: the stock-style sticky casualty choice. Tanks have 2 hit points here. */
class StickyCasualtyChoiceTest {
  private final GameData gameData = TestMapGameData.REVISED.getGameData();
  private final GamePlayer british = british(gameData);

  @BeforeEach
  void setUp() {
    gameData.performChange(
        ChangeFactory.attachmentPropertyChange(armour(gameData).getUnitAttachment(), 2, "hitPoints"));
  }

  private List<Unit> units(final int infantry, final int tanks) {
    final List<Unit> units = new ArrayList<>(infantry(gameData).create(infantry, british));
    units.addAll(armour(gameData).create(tanks, british));
    return units;
  }

  private static Group group(final StickyCasualtyChoice choice, final String type) {
    return choice.getGroups().stream()
        .filter(g -> g.getType().getName().equals(type))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void oneEntryPerUnitType() {
    final StickyCasualtyChoice choice = new StickyCasualtyChoice(units(3, 2), 2);

    assertThat(choice.getGroups().size(), is(2));
    assertThat(group(choice, "armour").getUnits().size(), is(2));
  }

  @Test
  void threeHitsOnTwoHitPointUnitsDestroyOneAndDamageOne() {
    final StickyCasualtyChoice choice = new StickyCasualtyChoice(units(0, 3), 3);
    final Group tanks = group(choice, "armour");

    choice.add(tanks);
    choice.add(tanks);
    choice.add(tanks);

    assertThat(tanks.killed(), is(1));
    assertThat(tanks.leftDamaged().isPresent(), is(true));
    assertThat(choice.casualties().getKilled().size(), is(1));
    // not three damaged tanks: one destroyed (listed once damaged, once killed) and one damaged
    assertThat(choice.casualties().getDamaged().size(), is(2));
    assertThat(choice.resultText(), is("Result: 1 Armour destroyed; 1 Armour damaged."));
    assertThat(choice.problem().isEmpty(), is(true));
  }

  @Test
  void aUnitDamagedEarlierIsPreAssignedAndLocked() {
    final List<Unit> units = units(2, 2);
    units.get(3).setHits(1); // a damaged tank
    final StickyCasualtyChoice choice = new StickyCasualtyChoice(units, 2);
    final Group tanks = group(choice, "armour");

    assertThat(tanks.getLocked(), is(1));
    assertThat(tanks.getHits(), is(1));
    assertThat(choice.remove(tanks), is(false));
    assertThat(tanks.getHits(), is(1));
    // the locked hit goes on the damaged tank
    assertThat(tanks.allocations().get(0).unit(), is(units.get(3)));
    assertThat(tanks.allocations().get(0).killed(), is(true));
  }

  @Test
  void okOnlyWhenCompleteAndLegal() {
    final StickyCasualtyChoice choice = new StickyCasualtyChoice(units(2, 1), 2);
    final Group infantry = group(choice, "infantry");
    final Group tanks = group(choice, "armour");

    assertThat(choice.problem().isPresent(), is(true)); // nothing given yet
    choice.add(infantry);
    assertThat(choice.problem().isPresent(), is(true)); // one hit left to give
    choice.add(tanks);
    assertThat(choice.problem().isEmpty(), is(true));
    // no more hits than there are
    assertThat(choice.add(infantry), is(false));
  }

  @Test
  void twoUnitsLeftDamagedIsNotLegal() {
    final List<Unit> units = new ArrayList<>(armour(gameData).create(1, british));
    units.addAll(
        gameData.getUnitTypeList().getUnitTypeOrThrow("battleship").create(1, british));
    gameData.performChange(
        ChangeFactory.attachmentPropertyChange(
            gameData.getUnitTypeList().getUnitTypeOrThrow("battleship").getUnitAttachment(),
            2,
            "hitPoints"));
    final StickyCasualtyChoice choice = new StickyCasualtyChoice(units, 2);
    choice.add(group(choice, "armour"));
    choice.add(group(choice, "battleship"));

    // a tank and a battleship are different kinds (land, sea): each may keep one damaged unit
    assertThat(choice.problem().isEmpty(), is(true));

    final List<Unit> land = new ArrayList<>(armour(gameData).create(1, british));
    land.addAll(gameData.getUnitTypeList().getUnitTypeOrThrow("artillery").create(1, british));
    gameData.performChange(
        ChangeFactory.attachmentPropertyChange(
            gameData.getUnitTypeList().getUnitTypeOrThrow("artillery").getUnitAttachment(),
            2,
            "hitPoints"));
    final StickyCasualtyChoice same = new StickyCasualtyChoice(land, 2);
    same.add(group(same, "armour"));
    same.add(group(same, "artillery"));
    assertThat(same.problem().isPresent(), is(true));
  }

  @Test
  void suggestedCasualtiesFillTheEnginesChoice() {
    final List<Unit> units = units(2, 1);
    final CasualtyList suggested = StickyHits.getDefaultCasualties(units, 3);
    final StickyCasualtyChoice choice = new StickyCasualtyChoice(units, 3);

    choice.suggest(suggested);

    assertThat(group(choice, "infantry").getHits(), is(2));
    assertThat(group(choice, "armour").getHits(), is(1));
    assertThat(choice.problem().isEmpty(), is(true));
  }
}
