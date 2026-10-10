package games.strategy.triplea.ui.research;

import games.strategy.triplea.ui.research.ResearchView.Category;
import games.strategy.triplea.ui.research.ResearchView.State;
import games.strategy.triplea.ui.research.ResearchView.Tech;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.Timer;
import javax.swing.UIManager;

/**
 * Flames and Steel: draws a nation's research categories as blocks, each listing its techs in
 * research order with their state colour and star track. Clicking a block reports the category.
 */
public final class ResearchBoard extends JPanel implements Scrollable {
  /** A focus tag drawn on a category block, e.g. "C1 P". */
  public record Tag(String text, Color color, boolean solid) {}

  /** What the board reports back. */
  public interface Listener {
    void categoryClicked(String category);

    void categoryHovered(@Nullable String category);
  }

  static final Color OWNED_FILL = new Color(0x4C, 0xAF, 0x50);
  static final Color OWNED_BORDER = new Color(0x2E, 0x7D, 0x32);
  static final Color UNLOCKED_FILL = new Color(0xFF, 0xD8, 0x6B);
  static final Color UNLOCKED_BORDER = new Color(0xB2, 0x80, 0x00);
  static final Color OPEN_FILL = new Color(0xF5, 0xF5, 0xF5);
  static final Color OPEN_BORDER = new Color(0xBD, 0xBD, 0xBD);
  static final Color STAR_FILLED = new Color(0xFF, 0xC4, 0x00);
  static final Color STAR_FILLED_EDGE = new Color(0x8A, 0x60, 0x00);
  static final Color STAR_EMPTY = new Color(0xD6, 0xD6, 0xD6);
  static final Color STAR_EMPTY_EDGE = new Color(0x9A, 0x9A, 0x9A);
  static final Color NEW_MARK = new Color(0xFF, 0x4F, 0x00);
  private static final Color BLOCK_FILL = Color.WHITE;
  private static final Color BLOCK_BORDER = new Color(0xB8, 0xB8, 0xB8);
  private static final Color HEADER_FILL = new Color(0xE3, 0xE8, 0xEC);
  private static final Color HEADER_COMPLETE = new Color(0xD3, 0xEC, 0xD3);
  private static final Color HOVER = new Color(0x1E, 0x88, 0xE5);
  private static final Color TEXT = new Color(0x21, 0x21, 0x21);

  private static final int MIN_BLOCK_WIDTH = 250;
  private static final int GAP = 10;
  private static final int HEADER_HEIGHT = 26;
  private static final int ROW_HEIGHT = 23;
  private static final int STAR_SPACING = 14;
  private static final long PULSE_MILLIS = 4000;

  private final Listener listener;
  private @Nullable ResearchView view;
  private Map<String, List<Tag>> tags = Map.of();
  private Set<String> greyed = Set.of();
  private Map<String, Integer> newStars = Map.of();
  private boolean selecting;
  private @Nullable String hovered;
  private long pulseStart;
  private final Timer pulse = new Timer(60, e -> repaint());

  public ResearchBoard(final Listener listener) {
    super(new MasonryLayout());
    this.listener = listener;
    setBackground(UIManager.getColor("Panel.background"));
  }

  public void setView(final ResearchView view) {
    if (this.view == null || !this.view.player().equals(view.player())) {
      hovered = null;
    }
    this.view = view;
    removeAll();
    for (final Category category : view.categories()) {
      add(new Block(category));
    }
    revalidate();
    repaint();
  }

  public void setTags(final Map<String, List<Tag>> tags) {
    this.tags = tags;
    repaint();
  }

  /** Categories drawn greyed (not choosable right now). */
  public void setGreyed(final Set<String> greyed) {
    this.greyed = greyed;
    repaint();
  }

  /** Whether clicking a block chooses it (hand cursor, hover outline). */
  public void setSelecting(final boolean selecting) {
    this.selecting = selecting;
    repaint();
  }

  /**
   * Stars just gained, per tech name; they pulse for a few seconds when they first appear and stay
   * marked.
   */
  public void setNewStars(final Map<String, Integer> newStars) {
    if (!newStars.equals(this.newStars)) {
      pulseStart = System.currentTimeMillis();
      if (!newStars.isEmpty()) {
        pulse.start();
      }
    }
    this.newStars = newStars;
    repaint();
  }

  private float pulsePhase() {
    final long elapsed = System.currentTimeMillis() - pulseStart;
    if (elapsed > PULSE_MILLIS) {
      pulse.stop();
      return -1;
    }
    return (float) (0.5 + 0.5 * Math.sin(elapsed / 120.0));
  }

  @Override
  public Dimension getPreferredScrollableViewportSize() {
    return getPreferredSize();
  }

  @Override
  public int getScrollableUnitIncrement(
      final Rectangle visibleRect, final int orientation, final int direction) {
    return ROW_HEIGHT;
  }

  @Override
  public int getScrollableBlockIncrement(
      final Rectangle visibleRect, final int orientation, final int direction) {
    return visibleRect.height - ROW_HEIGHT;
  }

  @Override
  public boolean getScrollableTracksViewportWidth() {
    return true;
  }

  @Override
  public boolean getScrollableTracksViewportHeight() {
    return false;
  }

  /** One category: header with name and focus tags, then a row per tech. */
  private final class Block extends JComponent {
    private final Category category;

    Block(final Category category) {
      this.category = category;
      final MouseAdapter mouse =
          new MouseAdapter() {
            @Override
            public void mouseReleased(final MouseEvent e) {
              if (contains(e.getPoint())) {
                listener.categoryClicked(category.name());
              }
            }

            @Override
            public void mouseEntered(final MouseEvent e) {
              hovered = category.name();
              listener.categoryHovered(category.name());
              ResearchBoard.this.repaint();
            }

            @Override
            public void mouseExited(final MouseEvent e) {
              if (category.name().equals(hovered)) {
                hovered = null;
                listener.categoryHovered(null);
                ResearchBoard.this.repaint();
              }
            }
          };
      addMouseListener(mouse);
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(
          MIN_BLOCK_WIDTH, HEADER_HEIGHT + category.techs().size() * ROW_HEIGHT + 8);
    }

    @Override
    protected void paintComponent(final Graphics graphics) {
      final Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      final boolean grey = greyed.contains(category.name());
      final boolean clickable = selecting && !grey;
      setCursor(Cursor.getPredefinedCursor(clickable ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
      if (grey) {
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.38f));
      }
      final int w = getWidth();
      final int h = getHeight();
      g.setColor(BLOCK_FILL);
      g.fillRoundRect(0, 0, w - 1, h - 1, 10, 10);
      g.setColor(category.isComplete() ? HEADER_COMPLETE : HEADER_FILL);
      g.fillRoundRect(0, 0, w - 1, HEADER_HEIGHT, 10, 10);
      g.fillRect(0, HEADER_HEIGHT - 6, w - 1, 6);
      final boolean hover = clickable && category.name().equals(hovered);
      g.setColor(hover ? HOVER : BLOCK_BORDER);
      g.setStroke(new BasicStroke(hover ? 2.5f : 1f));
      g.drawRoundRect(1, 1, w - 3, h - 3, 10, 10);
      g.setStroke(new BasicStroke(1f));

      final Font base = getFont() == null ? UIManager.getFont("Label.font") : getFont();
      final Font bold = base.deriveFont(Font.BOLD, 13f);
      final Font plain = base.deriveFont(Font.PLAIN, 12f);
      final Font small = base.deriveFont(Font.BOLD, 11f);

      // header: name, then tags from the right
      int right = w - 6;
      g.setFont(small);
      final List<Tag> blockTags =
          category.isComplete()
              ? List.of(new Tag("complete", OWNED_BORDER, true))
              : tags.getOrDefault(category.name(), List.of());
      for (int i = blockTags.size() - 1; i >= 0; i--) {
        right = drawTag(g, blockTags.get(i), right) - 4;
      }
      g.setFont(bold);
      g.setColor(TEXT);
      drawClipped(g, category.name(), 8, 18, right - 8);

      final float phase = pulsePhase();
      int y = HEADER_HEIGHT + 4;
      for (int i = 0; i < category.techs().size(); i++) {
        paintTech(g, category.techs().get(i), i == category.nextIndex(), y, w, plain, phase);
        y += ROW_HEIGHT;
      }
      g.dispose();
    }

    private int drawTag(final Graphics2D g, final Tag tag, final int right) {
      final FontMetrics fm = g.getFontMetrics();
      final int tw = fm.stringWidth(tag.text()) + 10;
      final int x = right - tw;
      if (tag.solid()) {
        g.setColor(tag.color());
        g.fillRoundRect(x, 5, tw, 17, 8, 8);
        g.setColor(Color.WHITE);
      } else {
        g.setColor(Color.WHITE);
        g.fillRoundRect(x, 5, tw, 17, 8, 8);
        g.setColor(tag.color());
        g.setStroke(new BasicStroke(1.6f));
        g.drawRoundRect(x, 5, tw, 17, 8, 8);
        g.setStroke(new BasicStroke(1f));
      }
      g.drawString(tag.text(), x + 5, 18);
      return x;
    }

    private void paintTech(
        final Graphics2D g,
        final Tech tech,
        final boolean next,
        final int y,
        final int w,
        final Font font,
        final float phase) {
      final int x = 10;
      final int cw = w - 18;
      final int ch = ROW_HEIGHT - 4;
      final int gained = newStars.getOrDefault(tech.name(), 0);
      final Color fill;
      final Color border;
      switch (tech.state()) {
        case OWNED -> {
          fill = OWNED_FILL;
          border = OWNED_BORDER;
        }
        case UNLOCKED -> {
          fill = UNLOCKED_FILL;
          border = UNLOCKED_BORDER;
        }
        default -> {
          fill = OPEN_FILL;
          border = OPEN_BORDER;
        }
      }
      g.setColor(fill);
      g.fillRoundRect(x, y, cw, ch, 8, 8);
      g.setColor(gained > 0 ? NEW_MARK : border);
      g.setStroke(new BasicStroke(gained > 0 || tech.state() == State.UNLOCKED ? 2f : 1f));
      g.drawRoundRect(x, y, cw, ch, 8, 8);
      g.setStroke(new BasicStroke(1f));
      if (next) {
        // where the next star goes
        final Polygon arrow = new Polygon();
        arrow.addPoint(1, y + 4);
        arrow.addPoint(7, y + ch / 2);
        arrow.addPoint(1, y + ch - 4);
        g.setColor(TEXT);
        g.fillPolygon(arrow);
      }

      // stars (or the fixed turn) from the right
      int starsLeft;
      final int cy = y + ch / 2;
      if (tech.fixedRound() > 0) {
        g.setFont(font);
        final String text = "turn " + tech.fixedRound();
        starsLeft = x + cw - 6 - g.getFontMetrics().stringWidth(text);
        g.setColor(tech.state() == State.OWNED ? Color.WHITE : TEXT);
        g.drawString(text, starsLeft, y + ch - 5);
      } else {
        starsLeft = x + cw - 4 - tech.required() * STAR_SPACING;
        for (int s = 0; s < tech.required(); s++) {
          final int sx = starsLeft + s * STAR_SPACING + STAR_SPACING / 2;
          final boolean filled = s < tech.stars();
          final boolean isNew = filled && s >= tech.stars() - gained;
          if (isNew) {
            final float alpha = phase < 0 ? 0.55f : 0.25f + 0.75f * phase;
            g.setColor(
                new Color(
                    NEW_MARK.getRed(),
                    NEW_MARK.getGreen(),
                    NEW_MARK.getBlue(),
                    (int) (alpha * 255)));
            final int r = phase < 0 ? 8 : 8 + Math.round(3 * phase);
            g.fillOval(sx - r, cy - r, 2 * r, 2 * r);
          }
          paintStar(g, sx, cy, 6, filled);
        }
      }

      g.setFont(next ? font.deriveFont(Font.BOLD) : font);
      g.setColor(tech.state() == State.OWNED ? Color.WHITE : TEXT);
      drawClipped(g, tech.displayName(), x + 7, y + ch - 5, starsLeft - x - 12);
    }
  }

  static void paintStar(
      final Graphics2D g, final int cx, final int cy, final int r, final boolean filled) {
    final Polygon star = new Polygon();
    for (int i = 0; i < 10; i++) {
      final double angle = -Math.PI / 2 + i * Math.PI / 5;
      final double radius = i % 2 == 0 ? r : r * 0.45;
      star.addPoint(
          (int) Math.round(cx + radius * Math.cos(angle)),
          (int) Math.round(cy + radius * Math.sin(angle)));
    }
    g.setColor(filled ? STAR_FILLED : STAR_EMPTY);
    g.fillPolygon(star);
    g.setColor(filled ? STAR_FILLED_EDGE : STAR_EMPTY_EDGE);
    g.drawPolygon(star);
  }

  private static void drawClipped(
      final Graphics2D g, final String text, final int x, final int y, final int width) {
    final FontMetrics fm = g.getFontMetrics();
    if (fm.stringWidth(text) <= width) {
      g.drawString(text, x, y);
      return;
    }
    String shown = text;
    while (!shown.isEmpty() && fm.stringWidth(shown + "…") > width) {
      shown = shown.substring(0, shown.length() - 1);
    }
    g.drawString(shown + "…", x, y);
  }

  /** Columns as wide as fit; each block goes into the currently shortest column. */
  private static final class MasonryLayout implements LayoutManager {
    @Override
    public void addLayoutComponent(final String name, final Component comp) {}

    @Override
    public void removeLayoutComponent(final Component comp) {}

    private static int columns(final int width) {
      return Math.max(1, (width - GAP) / (MIN_BLOCK_WIDTH + GAP));
    }

    private static int availableWidth(final Container parent) {
      final int width = parent.getParent() == null ? 0 : parent.getParent().getWidth();
      return width > 0 ? width : 5 * (MIN_BLOCK_WIDTH + GAP) + GAP;
    }

    @Override
    public Dimension preferredLayoutSize(final Container parent) {
      final int width = availableWidth(parent);
      final int columns = columns(width);
      final int[] heights = new int[columns];
      for (final Component c : parent.getComponents()) {
        final int col = shortest(heights);
        heights[col] += c.getPreferredSize().height + GAP;
      }
      int max = 0;
      for (final int h : heights) {
        max = Math.max(max, h);
      }
      return new Dimension(columns * (MIN_BLOCK_WIDTH + GAP) + GAP, max + GAP);
    }

    @Override
    public Dimension minimumLayoutSize(final Container parent) {
      return new Dimension(MIN_BLOCK_WIDTH + 2 * GAP, 100);
    }

    @Override
    public void layoutContainer(final Container parent) {
      final int width = Math.max(parent.getWidth(), MIN_BLOCK_WIDTH + 2 * GAP);
      final int columns = columns(width);
      final int blockWidth = (width - GAP * (columns + 1)) / columns;
      final int[] heights = new int[columns];
      for (final Component c : parent.getComponents()) {
        final int col = shortest(heights);
        final int h = c.getPreferredSize().height;
        c.setBounds(GAP + col * (blockWidth + GAP), GAP + heights[col], blockWidth, h);
        heights[col] += h + GAP;
      }
    }

    private static int shortest(final int[] heights) {
      int best = 0;
      for (int i = 1; i < heights.length; i++) {
        if (heights[i] < heights[best]) {
          best = i;
        }
      }
      return best;
    }
  }
}
