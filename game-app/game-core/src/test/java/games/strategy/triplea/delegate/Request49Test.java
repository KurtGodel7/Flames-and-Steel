package games.strategy.triplea.delegate;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.triplea.delegate.air.AirBattleReport;
import games.strategy.triplea.delegate.air.AirBattleReport.Row;
import games.strategy.triplea.delegate.air.AirBattleReport.Stack;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.delegate.air.AirCover;
import games.strategy.triplea.ui.AirBattleWindowPreview;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Request 49: Pearl Harbor from James's round 3 (his own start-of-round save, his own orders: 8
 * Japanese torpedo bombers and 4 fighters from Sea Zone 210 to Sea Zone 225). The air battles
 * window shows each side's survivors first, then "Lost · N PU" and the units lost, each still
 * recognisable, with a tooltip naming it and what happened to it; a damaged battleship says how
 * many hit points it has left.
 */
class Request49Test {
  private static final Path SAVE =
      Path.of(System.getProperty("user.home"), "triplea/savedGames/autoSave/FS_R03_start.tsvg");
  private static final String PEARL = "Sea Zone 225 Hawaiian Islands - South";

  @Test
  void pearlHarborShowsWhatAmericaPacificLost() throws Exception {
    Assumptions.assumeTrue(Files.exists(SAVE), "James's round 3 save is not here");
    final GameData data = GameDataManager.loadGame(SAVE).orElseThrow();
    final GamePlayer japanese = data.getPlayerList().getPlayerId("Japanese");
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    final Territory from =
        data.getMap().getTerritoryOrThrow("Sea Zone 210 Central Pacific - South");
    final Territory pearl = data.getMap().getTerritoryOrThrow(PEARL);
    final AirStageRunner stages =
        new AirStageRunner(data)
            .die(2)
            .bombers(
                japanese,
                new TargetAssignment(
                    from,
                    data.getUnitTypeList().getUnitTypeOrThrow("torpedoBomber"),
                    8,
                    new Target(pearl, TargetKind.SURFACE)))
            .cover(
                japanese,
                new TargetAssignment(
                    from,
                    data.getUnitTypeList().getUnitTypeOrThrow("fighter"),
                    4,
                    AirCover.over(pearl)))
            .run();
    assertThat(stages.problems(), is(empty()));
    stages.battles();
    final ArgumentCaptor<AirBattleReport> captor = ArgumentCaptor.forClass(AirBattleReport.class);
    verify(stages.lastBridge().getDisplayChannelBroadcaster()).showAirBattles(captor.capture());
    final AirBattleReport report = captor.getValue();
    final Row row =
        report.rows().stream().filter(r -> r.place().equals(PEARL)).findFirst().orElseThrow();
    System.out.println("PEARL " + row);

    final List<Stack> lost =
        row.defenders().stream()
            .filter(s -> s.owner().equals("AmericansPacific"))
            .filter(Stack::destroyed)
            .toList();
    assertThat(lost.isEmpty(), is(false));
    final int value = lost.stream().mapToInt(Stack::value).sum();
    assertThat(value > 0, is(true));
    // one state per unit: nothing both destroyed and shown aborted (the story says what came first)
    for (final Stack s : row.attackers()) {
      if (s.destroyed() && s.aborted()) {
        assertThat(s.story(), org.hamcrest.Matchers.containsString("aborted, then destroyed"));
      }
    }

    final AirBattleWindowPreview.Seen seen = AirBattleWindowPreview.draw(data, report, germans);
    final List<String> texts = seen.texts();
    // the losses after "Lost · N PU"
    assertThat(texts, hasItem("Lost · " + value + " PU"));
    // every lost ship is named in its tooltip
    for (final Stack s : lost) {
      final String name =
          games.strategy.triplea.delegate.air.AirCombat.unitName(
              data.getUnitTypeList().getUnitTypeOrThrow(s.unitType()));
      assertThat(
          texts.stream()
              .anyMatch(
                  t ->
                      t.startsWith("America Pacific " + name)
                          || t.matches("\\d+ America Pacific " + name + "s?:.*")),
          is(true));
    }
    // a damaged ship with more than 2 hit points says how many are left
    final Pattern hp = Pattern.compile("\\d+/\\d+");
    final boolean damagedBig =
        row.defenders().stream()
            .anyMatch(
                s ->
                    !s.destroyed()
                        && s.hits() > 0
                        && data.getUnitTypeList()
                                .getUnitTypeOrThrow(s.unitType())
                                .getUnitAttachment()
                                .getHitPoints()
                            > 2);
    if (damagedBig) {
      assertThat(texts.stream().anyMatch(t -> hp.matcher(t).matches()), is(true));
    }
  }
}
