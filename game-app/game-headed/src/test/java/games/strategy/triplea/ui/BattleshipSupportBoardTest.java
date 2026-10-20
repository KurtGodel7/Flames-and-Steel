package games.strategy.triplea.ui;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.delegate.battle.BattleState;
import games.strategy.triplea.delegate.battle.IBattle;
import games.strategy.triplea.settings.ClientSetting;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Request 25: the battle board marks ships with battleship support ("+1 Battleship") and puts them
 * in the column of the value they roll at. Draws build/preview/r25-battle-board.png.
 */
class BattleshipSupportBoardTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");

  @Test
  void supportedShipsAreMarked() throws Exception {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    ClientSetting.initialize();
    final GameData data = GameParser.parse(XML, false).orElseThrow();
    final GamePlayer americans = data.getPlayerList().getPlayerId("Americans");
    final GamePlayer japanese = data.getPlayerList().getPlayerId("Japanese");
    final Territory sea =
        data.getMap().getTerritoryOrThrow("Sea Zone 225 Hawaiian Islands - South");
    final List<Unit> fleet = new ArrayList<>();
    for (final String[] spec :
        new String[][] {{"battleship", "3"}, {"destroyer", "2"}, {"cruiser", "2"}}) {
      fleet.addAll(
          data.getUnitTypeList()
              .getUnitType(spec[0])
              .orElseThrow()
              .create(Integer.parseInt(spec[1]), americans));
    }
    final List<Unit> enemy =
        data.getUnitTypeList().getUnitType("cruiser").orElseThrow().create(2, japanese);
    final UiContext ui = new UiContext(data);
    SwingUtilities.invokeAndWait(
        () -> {
          final BattleModel ours =
              new BattleModel(
                  fleet,
                  BattleState.Side.DEFENSE,
                  IBattle.BattleType.NORMAL,
                  data,
                  List.of(),
                  sea,
                  ui);
          final BattleModel theirs =
              new BattleModel(
                  enemy,
                  BattleState.Side.OFFENSE,
                  IBattle.BattleType.NORMAL,
                  data,
                  List.of(),
                  sea,
                  ui);
          ours.setEnemyBattleModel(theirs);
          theirs.setEnemyBattleModel(ours);
          ours.refresh();
          // column = value rolled at; both destroyers at 2 (1 + 1), one cruiser at 3 (2 + 1)
          final List<String> cells = new ArrayList<>();
          final JLabel stamp = new JLabel();
          for (int row = 0; row < ours.getRowCount(); row++) {
            for (int column = 0; column < ours.getColumnCount(); column++) {
              final Object value = ours.getValueAt(row, column);
              if (value instanceof BattleModel.TableData data1
                  && data1 != BattleModel.TableData.NULL) {
                data1.updateStamp(stamp);
                if (!stamp.getText().isEmpty()) {
                  cells.add(column + ": " + stamp.getText() + " | " + stamp.getToolTipText());
                }
              }
            }
          }
          final String all = String.join("\n", cells);
          // destroyers: both at 2 (1 + 1); cruisers: one at 3 (2 + 1), one at 2
          assertThat(
              all,
              containsString("2: x2 | <html><b>+1 Battleship support:</b> these ships roll at 2"));
          assertThat(
              all,
              containsString(
                  "3: x1  2/2 | <html><b>+1 Battleship support:</b> this ship rolls at 3"));
          assertThat(cells.stream().filter(c -> c.contains("support")).count(), is(2L));
          snap(ours);
        });
  }

  private static void snap(final BattleModel model) {
    final JTable table = new JTable(model);
    table.setDefaultRenderer(
        Object.class,
        (t, value, selected, focus, row, column) -> {
          final JLabel stamp = new JLabel();
          if (value instanceof BattleModel.TableData d) {
            d.updateStamp(stamp);
          }
          return stamp;
        });
    table.setRowHeight(60);
    final JScrollPane pane = new JScrollPane(table);
    pane.setSize(560, 60 * model.getRowCount() + 30);
    pane.doLayout();
    pane.getViewport().doLayout();
    table.setSize(560, 60 * model.getRowCount());
    table.doLayout();
    pane.validate();
    final BufferedImage img =
        new BufferedImage(pane.getWidth(), pane.getHeight(), BufferedImage.TYPE_INT_RGB);
    pane.printAll(img.getGraphics());
    try {
      new File("build/preview").mkdirs();
      ImageIO.write(img, "png", new File("build/preview/r25-battle-board.png"));
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }
}
