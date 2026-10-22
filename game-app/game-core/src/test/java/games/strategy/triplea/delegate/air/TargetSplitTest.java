package games.strategy.triplea.delegate.air;

import static games.strategy.triplea.delegate.GameDataTestUtil.bomber;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Request 28 part B: one type split over several targets, never more than there are. */
class TargetSplitTest {
  private final GameData gameData = TestMapGameData.REVISED.getGameData();
  private final Territory zone = territory("1 Sea Zone", gameData);
  private final UnitType bomber = bomber(gameData);
  private final Target lithuania =
      new Target(territory("Karelia S.S.R.", gameData), TargetKind.LAND);
  private final Target vitebsk = new Target(territory("Archangel", gameData), TargetKind.LAND);

  @Test
  void oneTypeSplitsOverTwoTargetsAndNoTarget() {
    List<TargetAssignment> staged = List.of();
    staged = TargetSplit.adjust(staged, zone, bomber, lithuania, 1, 5);
    for (int i = 0; i < 3; i++) {
      staged = TargetSplit.adjust(staged, zone, bomber, vitebsk, 1, 5);
    }

    assertThat(TargetSplit.count(staged, zone, bomber, lithuania), is(1));
    assertThat(TargetSplit.count(staged, zone, bomber, vitebsk), is(3));
    assertThat(TargetSplit.assigned(staged, zone, bomber), is(4)); // 1 with no target
    assertThat(staged.size(), is(2));
  }

  @Test
  void countsNeverExceedTheBombersThere() {
    List<TargetAssignment> staged = List.of();
    for (int i = 0; i < 4; i++) {
      staged = TargetSplit.adjust(staged, zone, bomber, lithuania, 1, 2);
    }
    assertThat(TargetSplit.count(staged, zone, bomber, lithuania), is(2));
    staged = TargetSplit.adjust(staged, zone, bomber, vitebsk, 1, 2);
    assertThat(TargetSplit.count(staged, zone, bomber, vitebsk), is(0));
    // and never below 0
    staged = TargetSplit.adjust(staged, zone, bomber, vitebsk, -1, 2);
    assertThat(TargetSplit.count(staged, zone, bomber, vitebsk), is(0));
    staged = TargetSplit.adjust(staged, zone, bomber, lithuania, -2, 2);
    assertThat(staged.isEmpty(), is(true));
  }
}
