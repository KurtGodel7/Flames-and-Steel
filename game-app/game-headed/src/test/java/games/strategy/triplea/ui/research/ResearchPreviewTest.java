package games.strategy.triplea.ui.research;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.delegate.data.ResearchResults;
import games.strategy.triplea.delegate.data.ResearchResults.Breakthrough;
import java.awt.Component;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.triplea.java.collections.IntegerMap;

/** Temporary: renders the research screen from the installed game XML to PNG files. */
class ResearchPreviewTest {
  private static final String OUT = System.getProperty("preview.out", "build/preview");

  @Test
  void render() throws Exception {
    final Path xml =
        Path.of(
            System.getProperty("user.home"),
            "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
    final GameData data = games.strategy.triplea.ui.TestMaps.load();
    final GamePlayer germans = data.getPlayerList().getPlayerId("Germans");
    new File(OUT).mkdirs();
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            final JFrame owner = new JFrame();
            owner.setBounds(-6000, -6000, 1700, 1000);
            final ResearchScreen screen = new ResearchScreen(owner, data);
            screen.askForChoice(germans);
            screen.setBounds(-6000, -6000, 1600, 950);
            screen.validate();
            snap(screen, "1-choose.png");
            System.out.println("FOCUS start: " + focusTexts(screen));
            // the swap button on centre 1
            find(screen, "swap|0").doClick();
            System.out.println("FOCUS after swap 1: " + focusTexts(screen));
            // P of centre 2, then the category centre 3's primary holds: the two swap
            toggle(screen, 2).doClick();
            final String c3p = focusTexts(screen).get(4).substring(3);
            screen.onCategoryClicked(c3p);
            System.out.println("FOCUS after picking " + c3p + " for C2 P: " + focusTexts(screen));
            snap(screen, "1b-swapped.png");

            final IntegerMap<String> stars =
                new IntegerMap<>(germans.getTechAttachment().getResearchStars());
            stars.add("Jet", 1);
            stars.add("Radar", 1);
            stars.add("StrategicBomber", 1);
            data.performChange(
                ChangeFactory.attachmentPropertyChange(
                    germans.getTechAttachment(), stars, "researchStars"));
            screen.showResults(
                germans,
                ResearchResults.success(
                    List.of(),
                    List.of(
                        new Breakthrough(1, "primary", "Jets", "Jet", 3, 5),
                        new Breakthrough(2, "random", "Electronics", "Radar", 2, 2),
                        new Breakthrough(3, "secondary", "Air", "StrategicBomber", 3, 4),
                        new Breakthrough(0, "Goettingen", "Jets", null, 0, 0))));
            screen.validate();
            snap(screen, "2-results.png");
            screen.showNation(data.getPlayerList().getPlayerId("Chinese"));
            screen.validate();
            snap(screen, "3-china.png");
            screen.dispose();
            owner.dispose();
          } catch (final Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  private static List<String> focusTexts(final java.awt.Container root) {
    final List<String> texts = new java.util.ArrayList<>();
    for (final Component c : root.getComponents()) {
      if (c instanceof javax.swing.JToggleButton b
          && (b.getText().startsWith("P: ") || b.getText().startsWith("S: "))) {
        texts.add(b.getText());
      }
      if (c instanceof java.awt.Container k) {
        texts.addAll(focusTexts(k));
      }
    }
    return texts;
  }

  private static javax.swing.JToggleButton toggle(final java.awt.Container root, final int index) {
    final List<javax.swing.JToggleButton> all = new java.util.ArrayList<>();
    collect(root, all);
    return all.get(index);
  }

  private static void collect(
      final java.awt.Container root, final List<javax.swing.JToggleButton> out) {
    for (final Component c : root.getComponents()) {
      if (c instanceof javax.swing.JToggleButton b
          && (b.getText().startsWith("P: ") || b.getText().startsWith("S: "))) {
        out.add(b);
      }
      if (c instanceof java.awt.Container k) {
        collect(k, out);
      }
    }
  }

  private static javax.swing.JButton find(final java.awt.Container root, final String name) {
    for (final Component c : root.getComponents()) {
      if (c instanceof javax.swing.JButton b && name.equals(b.getName())) {
        return b;
      }
      if (c instanceof java.awt.Container k) {
        final javax.swing.JButton f = find(k, name);
        if (f != null) {
          return f;
        }
      }
    }
    return null;
  }

  private static void snap(final Component c, final String name) throws Exception {
    c.doLayout();
    final Component content = ((javax.swing.JDialog) c).getContentPane();
    layoutAll(content);
    final BufferedImage image =
        new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
    content.printAll(image.getGraphics());
    ImageIO.write(image, "png", new File(OUT, name));
  }

  private static void layoutAll(final Component c) {
    c.doLayout();
    if (c instanceof java.awt.Container container) {
      for (final Component child : container.getComponents()) {
        layoutAll(child);
      }
    }
  }
}
