package games.strategy.triplea.ui;

import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.delegate.battle.BattleState;
import games.strategy.triplea.delegate.battle.IBattle;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel request 14: the retreat question's summary reads a battle model from the game
 * thread while the Swing thread removes casualties from it. That crashed the game with a
 * ConcurrentModificationException.
 */
class BattleModelSummaryTest {
  private final GameData data = TestMapGameData.REVISED.getGameData();
  private final GamePlayer germans = germans(data);

  @Test
  void summaryCanBeBuiltWhileTheUnitsChange() throws InterruptedException {
    final List<Unit> start = infantry(data).create(50, germans);
    final BattleModel model =
        new BattleModel(
            start,
            BattleState.Side.OFFENSE,
            IBattle.BattleType.NORMAL,
            data,
            List.of(),
            territory("Germany", data),
            null);
    final Collection<Unit> units = model.getUnits();
    final List<Unit> extra = infantry(data).create(20, germans);
    final AtomicBoolean stop = new AtomicBoolean();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    // stands in for the Swing thread taking casualties and adding units
    final Thread changer =
        new Thread(
            () -> {
              while (!stop.get()) {
                units.addAll(extra);
                units.removeAll(extra);
              }
            });
    changer.start();
    try {
      for (int i = 0; i < 20_000; i++) {
        assertThat(model.summary(germans), startsWith("Germans: "));
      }
    } catch (final Throwable t) {
      failure.set(t);
    } finally {
      stop.set(true);
      changer.join();
    }

    assertThat(failure.get(), is(nullValue()));
    assertThat(model.summary(germans), startsWith("Germans: 50 infantry; 50 HP left"));
  }
}
