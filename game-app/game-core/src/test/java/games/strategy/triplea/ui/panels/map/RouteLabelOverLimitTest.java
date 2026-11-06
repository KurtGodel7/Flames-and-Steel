package games.strategy.triplea.ui.panels.map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.ResourceCollection;
import games.strategy.triplea.image.ResourceImageFactory;
import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import org.triplea.java.collections.IntegerMap;

/** Flames and Steel request 59: the route label is red when the move is over the limit. */
class RouteLabelOverLimitTest {

  private static BufferedImage label(final boolean overLimit) {
    final ResourceCollection fuel = mock(ResourceCollection.class);
    when(fuel.getResourcesCopy()).thenReturn(new IntegerMap<>());
    return MapRouteDrawer.newMovementLeftImage(
        "2", "1", overLimit, fuel, mock(ResourceImageFactory.class));
  }

  /** A background pixel just inside the rounded label, past the left curve and below the top. */
  private static Color background(final BufferedImage image) {
    return new Color(image.getRGB(image.getWidth() / 2, 2), true);
  }

  @Test
  void overTheLimitIsRed() {
    final Color c = background(label(true));
    assertThat(c.getRed() > 200 && c.getGreen() < 150 && c.getBlue() < 150, is(true));
  }

  @Test
  void withinTheLimitStaysStockGrey() {
    assertThat(background(label(false)), is(new Color(220, 220, 220)));
  }
}
