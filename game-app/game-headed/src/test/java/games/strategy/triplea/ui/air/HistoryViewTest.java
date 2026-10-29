package games.strategy.triplea.ui.air;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.engine.framework.GameDataUtils;
import games.strategy.engine.history.HistoryNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.tree.TreeNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel request 18: the history view works on a copy of the game data without delegates.
 * Walking it forwards and backwards through air phases, purchases and combat must never ask for a
 * delegate. Uses James's newest "before battle" autosave; skipped when there is none.
 */
class HistoryViewTest {
  private static final Path SAVE =
      Path.of(
          System.getProperty("user.home"), "triplea/savedGames/autoSave/autosaveBeforeBattle.tsvg");

  @Test
  void walkingTheHistoryNeverNeedsADelegate() {
    Assumptions.assumeTrue(Files.exists(SAVE), "no autosave to walk");
    // request 38: saves made with air boxes no longer load
    final java.util.Optional<GameData> loaded = GameDataManager.loadGame(SAVE);
    Assumptions.assumeTrue(loaded.isPresent(), "the autosave is from before request 38");
    final GameData live = loaded.get();
    // what TripleAFrame.showHistory does
    final GameData history =
        GameDataUtils.cloneGameData(
                live, GameDataManager.Options.builder().withHistory(true).build())
            .orElseThrow();
    assertThat(history.getDelegates().isEmpty(), is(true));
    // the history panel does this when the view opens
    history.getHistory().enableSeeking(null);

    final List<HistoryNode> nodes = new ArrayList<>();
    collect((TreeNode) history.getHistory().getRoot(), nodes);
    assertThat(nodes.size(), greaterThan(10));
    // forwards, then backwards, as a player stepping through the tree does (the step stays the
    // live one; the air map mode asks about it at every change)
    final List<HistoryNode> back = new ArrayList<>(nodes);
    Collections.reverse(back);
    for (final List<HistoryNode> walk : List.of(nodes, back)) {
      for (final HistoryNode node : walk) {
        history.getHistory().gotoNode(node);
        try (GameData.Unlocker ignored = history.acquireReadLock()) {
          AirMapMode.isAirStep(history.getSequence().getStep());
        }
      }
    }
    // every step of the copy is told apart by name, as on the live game: the air phase steps are
    // air steps, nothing else is
    for (final var step : history.getSequence()) {
      final String name = step.getName();
      final boolean expected =
          name.endsWith("AirBombers")
              || name.endsWith("AirFighters")
              || name.endsWith("AirTargets")
              || name.endsWith("AirCover")
              || name.startsWith("airReveal")
              || name.equals("airBomberStage")
              || name.equals("airFighterStage")
              || name.equals("airPlanesHome")
              || name.equals("airDogfights")
              || name.equals("airStrikes");
      assertThat(name, AirMapMode.isAirStep(step), is(expected));
    }
    // the live game (with delegates) gives the same answers
    for (int i = 0; i < live.getSequence().size(); i++) {
      final var step = live.getSequence().getStep(i);
      assertThat(
          step.getName(),
          AirMapMode.isAirStep(step),
          is(AirMapMode.isAirStep(history.getSequence().getStep(i))));
    }
  }

  private static void collect(final TreeNode node, final List<HistoryNode> into) {
    if (node instanceof HistoryNode h) {
      into.add(h);
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      collect(node.getChildAt(i), into);
    }
  }
}
