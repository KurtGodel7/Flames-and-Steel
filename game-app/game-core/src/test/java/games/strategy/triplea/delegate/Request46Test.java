package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.delegate.air.AirBattleReport;
import games.strategy.triplea.delegate.air.AirBattleReport.Row;
import games.strategy.triplea.delegate.air.AirBattleReport.Stack;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.delegate.air.AirCover;
import games.strategy.triplea.delegate.air.AirPriority;
import games.strategy.triplea.delegate.air.BasedMap;
import games.strategy.triplea.ui.AirBattleSummaryPanel;
import games.strategy.triplea.ui.AirBattleWindowPreview;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Request 46 on the installed map: the air battles summary as pictures. The engine reports each
 * battle's units (destroyed, damaged, aborted, AA that fired, a factory bombed with its PU loss);
 * the window sorts the battles into "Your losses" and "Enemy losses" for the viewing nation and
 * shows no internal unit names. Also draws the window to build/preview/request46_*.png.
 */
class Request46Test {
  private GameData data;
  private GamePlayer germans;
  private GamePlayer russians;
  private GamePlayer british;
  private Territory london;
  private Territory hamburg;

  @BeforeEach
  void setUp() {
    data = BasedMap.load();
    germans = BasedMap.p(data, "Germans");
    russians = BasedMap.p(data, "Russians");
    british = BasedMap.p(data, "British");
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(newDelegateBridge(germans));
    start.start();
    start.end();
    for (final GamePlayer p : List.of(russians, british)) {
      data.performChange(
          ChangeFactory.relationshipChange(
              p,
              germans,
              data.getRelationshipTracker().getRelationshipType(p, germans),
              data.getRelationshipTypeList().getRelationshipType("War")));
    }
    data.getProperties()
        .set(AirPriority.PROPERTY, "Russians|British:AmericansPacific:Americans:Chinese");
    london = t("London");
    hamburg = t("Hamburg");
    removeFrom(london, london.getUnitCollection().getMatches(Matches.unitIsAir()));
    // Hamburg: its factory, two conscripts and one AA gun (casualties picked by a 0 die: the first)
    removeFrom(
        hamburg,
        hamburg.getUnitCollection().getMatches(Matches.unitCanProduceUnits().negate()));
    add(hamburg, germans, "conscript", 2);
    add(hamburg, germans, "aaGun", 1);
  }

  private Territory t(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  private UnitType type(final String name) {
    return data.getUnitTypeList().getUnitTypeOrThrow(name);
  }

  private List<Unit> add(final Territory t, final GamePlayer owner, final String type, final int n) {
    final List<Unit> units = type(type).create(n, owner);
    addTo(t, units);
    return units;
  }

  private TargetAssignment a(
      final Territory base, final String type, final int n, final TargetKind kind, final String at) {
    return new TargetAssignment(base, type(type), n, new Target(t(at), kind));
  }

  private AirBattleReport report(final AirStageRunner stages) {
    stages.run();
    assertThat(stages.problems(), is(empty()));
    stages.battles();
    final ArgumentCaptor<AirBattleReport> report = ArgumentCaptor.forClass(AirBattleReport.class);
    verify(stages.lastBridge().getDisplayChannelBroadcaster()).showAirBattles(report.capture());
    return report.getValue();
  }

  private static Row row(final AirBattleReport report, final String place) {
    return report.rows().stream().filter(r -> r.place().equals(place)).findFirst().orElseThrow();
  }

  private static List<Stack> stacks(final List<Stack> side, final String type) {
    return side.stream().filter(s -> s.unitType().equals(type)).toList();
  }

  /** Two British strategic bombers strike Hamburg: AA damages one, the other kills a conscript. */
  private AirBattleReport hamburgStrike() {
    add(london, british, "strategicBomber", 2);
    return report(
        new AirStageRunner(data)
            .die(0)
            .bombers(british, a(london, "strategicBomber", 2, TargetKind.LAND, "Hamburg")));
  }

  @Test
  void aStrikeShowsTheDestroyedConscriptTheAbortedBomberAndTheAa() {
    final Row hamburgRow = row(hamburgStrike(), "Hamburg");

    final List<Stack> bombers = stacks(hamburgRow.attackers(), "strategicBomber");
    assertThat(
        bombers.stream().anyMatch(s -> s.hits() == 1 && s.aborted() && !s.destroyed()), is(true));
    assertThat(hamburgRow.attackers().stream().allMatch(s -> s.owner().equals("British")), is(true));
    final List<Stack> conscripts = stacks(hamburgRow.defenders(), "conscript");
    assertThat(conscripts.size(), is(1));
    assertThat(conscripts.get(0).destroyed(), is(true));
    assertThat(conscripts.get(0).count(), is(1));
    final List<Stack> aa = stacks(hamburgRow.defenders(), "aaGun");
    assertThat(aa.size(), is(1));
    assertThat(aa.get(0).destroyed(), is(false));
    assertThat(hamburgRow.notes(), hasItem("German AA (1 gun): 1 hit"));
    assertThat(hamburgRow.hurt(), contains("British", "Germans"));
    assertThat(hamburgRow.lossValue().get("Germans") > 0, is(true));
  }

  @Test
  void aRaidShowsTheFactoryWithItsPuLoss() {
    add(london, british, "strategicBomber", 2);
    final int before = germans.getResources().getQuantity("PUs");
    final AirBattleReport report =
        report(
            new AirStageRunner(data)
                .die(5) // AA misses
                .bombers(
                    british, a(london, "strategicBomber", 2, TargetKind.STRATEGIC, "Hamburg")));

    final int loss = before - germans.getResources().getQuantity("PUs");
    final Row hamburgRow = row(report, "Hamburg");
    final List<Stack> factory = stacks(hamburgRow.defenders(), "factory");
    assertThat(factory.size(), is(1));
    assertThat(factory.get(0).bombed(), is(loss));
    assertThat(loss > 0, is(true));
    assertThat(report.bombingTotals(), is(Map.of("Germans", loss)));
    assertThat(hamburgRow.lossValue().get("Germans"), is(loss));
  }

  @Test
  void theWindowPutsTheViewersLossesFirstAndNeverShowsInternalNames() throws Exception {
    final AirBattleReport report = picturedRound();
    final List<String> texts = new ArrayList<>();
    final List<Map<String, List<Row>>> sections = new ArrayList<>();
    for (final GamePlayer viewer : List.of(germans, british)) {
      final AirBattleWindowPreview.Seen seen = AirBattleWindowPreview.draw(data, report, viewer);
      sections.add(seen.sections());
      texts.addAll(seen.texts());
    }

    // Germans: Hamburg (conscript lost) is theirs; Kiev (only Soviet losses) is the enemy's
    final Map<String, List<Row>> german = sections.get(0);
    assertThat(
        german.get(AirBattleSummaryPanel.YOUR_LOSSES).stream().map(Row::place).toList(),
        hasItem("Hamburg"));
    assertThat(
        german.get(AirBattleSummaryPanel.ENEMY_LOSSES).stream().map(Row::place).toList(),
        hasItem("Kiev"));
    assertThat(german.keySet().iterator().next(), is(AirBattleSummaryPanel.YOUR_LOSSES));
    // British: their aborted bomber puts Hamburg under their own losses too
    assertThat(
        sections.get(1).get(AirBattleSummaryPanel.YOUR_LOSSES).stream().map(Row::place).toList(),
        hasItem("Hamburg"));
    // no XML unit names such as "strategicBomber" or "aaGun" anywhere in the window
    final Pattern internal = Pattern.compile("\\b[a-z]+[A-Z][A-Za-z]*\\b");
    for (final String text : texts) {
      assertThat(text, internal.matcher(text).find(), is(false));
    }
    assertThat(texts, not(empty()));
  }

  /**
   * A round with several battles, seen in the window: two British strategic bombers strike
   * Hamburg and one bombs its factory while German fighters defend; German dive
   * bombers strike Kiev.
   */
  private AirBattleReport picturedRound() {
    add(london, british, "strategicBomber", 3);
    add(hamburg, germans, "fighter", 2);
    final Territory cracow = t("Cracow");
    data.getProperties()
        .getEditablePropertiesByName()
        .get(AirCombat.DOGFIGHT_ROUNDS)
        .setValueIfValid(2);
    return report(
        new AirStageRunner(data)
            .die(2)
            .bombers(
                british,
                a(london, "strategicBomber", 2, TargetKind.LAND, "Hamburg"),
                a(london, "strategicBomber", 1, TargetKind.STRATEGIC, "Hamburg"))
            .bombers(germans, a(cracow, "diveBomber", 2, TargetKind.LAND, "Kiev"))
            .cover(
                germans, new TargetAssignment(hamburg, type("fighter"), 2, AirCover.over(hamburg))));
  }

}
