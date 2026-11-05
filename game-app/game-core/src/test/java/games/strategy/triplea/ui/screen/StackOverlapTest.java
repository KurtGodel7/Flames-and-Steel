package games.strategy.triplea.ui.screen;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Request 53 item 6: a unit stack is never drawn over another. */
class StackOverlapTest {
  private static final List<Rectangle> BATTLESHIP = List.of(new Rectangle(100, 100, 27, 27));

  @Test
  void pointsThreePixelsApartOverlap() {
    // Gulf of Finland: two place.txt points 3 px apart
    assertThat(TileManager.overlaps(BATTLESHIP, new Point(103, 100), 27, 27), is(true));
  }

  @Test
  void stacksSideBySideDoNot() {
    assertThat(TileManager.overlaps(BATTLESHIP, new Point(127, 100), 27, 27), is(false));
    // a pixel of touching is allowed
    assertThat(TileManager.overlaps(BATTLESHIP, new Point(126, 100), 27, 27), is(false));
    assertThat(TileManager.overlaps(BATTLESHIP, new Point(100, 127), 27, 27), is(false));
  }
}
