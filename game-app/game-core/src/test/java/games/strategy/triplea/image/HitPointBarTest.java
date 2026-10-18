package games.strategy.triplea.image;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HitPointBarTest {
  @Test
  void rowsSplitSixOrMoreIntoTwo() {
    assertThat(HitPointBar.rows(5), is(new int[] {5}));
    assertThat(HitPointBar.rows(6), is(new int[] {3, 3}));
    assertThat(HitPointBar.rows(7), is(new int[] {4, 3}));
    assertThat(HitPointBar.rows(8), is(new int[] {4, 4}));
  }

  @Test
  void noBarForOneHitPoint() {
    assertThat(HitPointBar.height(1, 0.6), is(0));
    assertThat(HitPointBar.height(2, 0.6), is(5)); // 3 px row + backing
    assertThat(HitPointBar.height(8, 0.6), is(9)); // two rows, 1 px gap
  }

  @Test
  void yamatoSegmentsAreEqualAndFillInReadingOrder() {
    // Yamato at units.scale 0.6: its 64 px image is 38 px wide
    final List<List<Color>> rows = paint(38, 8, 3);
    assertThat(rows.size(), is(2));
    for (final List<Color> row : rows) {
      assertThat(row.size(), is(4));
    }
    assertThat(
        rows.get(0),
        is(List.of(HitPointBar.GREEN, HitPointBar.GREEN, HitPointBar.GREEN, HitPointBar.GREEN)));
    assertThat(
        rows.get(1),
        is(List.of(HitPointBar.GREEN, HitPointBar.RED, HitPointBar.RED, HitPointBar.RED)));
  }

  @Test
  void everySegmentHasTheSameWidth() {
    for (int hp = 2; hp <= 8; hp++) {
      for (final int iconWidth : new int[] {28, 38, 48}) {
        final BufferedImage image = render(iconWidth, hp, 0);
        final List<Integer> widths = new ArrayList<>();
        final int y = 2; // middle of the top row
        int run = 0;
        for (int x = 0; x < image.getWidth(); x++) {
          if (new Color(image.getRGB(x, y), true).equals(HitPointBar.GREEN)) {
            run++;
          } else if (run > 0) {
            widths.add(run);
            run = 0;
          }
        }
        if (run > 0) {
          widths.add(run);
        }
        assertThat(widths.stream().distinct().count(), is(1L));
        assertThat(widths.size(), is(HitPointBar.rows(hp)[0]));
      }
    }
  }

  private static BufferedImage render(final int iconWidth, final int hp, final int hits) {
    final BufferedImage image = new BufferedImage(iconWidth, 20, BufferedImage.TYPE_INT_ARGB);
    final Graphics2D g = image.createGraphics();
    HitPointBar.paint(g, 0, 0, iconWidth, hp, hits, 0.6);
    g.dispose();
    return image;
  }

  /** Colour of each segment, row by row, sampled from the painted bar. */
  private static List<List<Color>> paint(final int iconWidth, final int hp, final int hits) {
    final BufferedImage image = render(iconWidth, hp, hits);
    final List<List<Color>> rows = new ArrayList<>();
    for (int y = 2; y < image.getHeight(); y += 4) { // row middles: 2, 6
      final List<Color> row = new ArrayList<>();
      Color previous = null;
      for (int x = 0; x < image.getWidth(); x++) {
        final Color c = new Color(image.getRGB(x, y), true);
        final boolean segment = c.equals(HitPointBar.GREEN) || c.equals(HitPointBar.RED);
        if (segment && !c.equals(previous)) {
          row.add(c);
        }
        previous = segment ? c : null;
      }
      if (!row.isEmpty()) {
        rows.add(row);
      }
    }
    return rows;
  }
}
