package games.strategy.triplea.ui;

import games.strategy.engine.data.Change;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.events.GameDataChangeListener;
import games.strategy.engine.stats.IStat;
import games.strategy.engine.stats.ResourceStat;
import games.strategy.engine.stats.StatsSides;
import games.strategy.triplea.Constants;
import games.strategy.triplea.delegate.AbstractEndTurnDelegate;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import org.triplea.java.collections.IntegerMap;

class EconomyPanel extends JPanel implements GameDataChangeListener {
  private static final long serialVersionUID = -7713792841831042952L;
  private final List<ResourceStat> resourceStats = new ArrayList<>();
  private ResourceTableModel resourceModel;

  /** For tests (request 60). */
  ResourceTableModel model() {
    return resourceModel;
  }

  private GameData gameData;
  private final UiContext uiContext;
  // Flames and Steel (request 60): PUs per manpower point; 0 hides the "PU value" column
  private final int mpValue;

  EconomyPanel(final GameData data, final UiContext uiContext) {
    this.gameData = data;
    this.uiContext = uiContext;
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      mpValue = StatsSides.mpValue(data);
    }
    initLayout();
    gameData.addDataChangeListener(this);
  }

  protected void initLayout() {
    setLayout(new GridLayout(1, 1));
    resourceModel = new ResourceTableModel();
    final JTable table = new JTable(resourceModel);
    table.getTableHeader().setReorderingAllowed(false);
    final TableColumn column = table.getColumnModel().getColumn(0);
    column.setPreferredWidth(175);
    for (int i = 1; i < resourceModel.getColumnCount(); i++) {
      table.getColumnModel().getColumn(i).setHeaderRenderer(new DefaultTableCellRenderer());
      final JLabel label = (JLabel) table.getColumnModel().getColumn(i).getHeaderRenderer();
      if (i > resourceStats.size()) {
        // Flames and Steel (request 60): PUs plus manpower at its set value
        label.setText("PU value");
        label.setToolTipText("PUs + manpower \u00d7 " + mpValue + " (per-turn figure in brackets)");
        continue;
      }
      final Resource resource = resourceStats.get(i - 1).resource;
      label.setIcon(uiContext.getResourceImageFactory().getIcon(resource.getName()));
      label.setToolTipText(resource.getName());
    }
    final JScrollPane scroll = new JScrollPane(table);
    add(scroll);
  }

  class ResourceTableModel extends AbstractTableModel {
    private static final long serialVersionUID = 5197895788633898324L;
    private String[][] collectedData;

    ResourceTableModel() {
      setResourceColumns();
    }

    private void setResourceColumns() {
      for (final Resource resource : gameData.getResourceList().getResources()) {
        if (resource.getName().equals(Constants.VPS)) {
          continue;
        }
        resourceStats.add(new ResourceStat(resource));
      }
    }

    @Override
    public Object getValueAt(final int row, final int col) {
      return getCollectedData()[row][col];
    }

    @Override
    public String getColumnName(final int col) {
      return "";
    }

    @Override
    public int getColumnCount() {
      return resourceStats.size() + 1 + (mpValue > 0 ? 1 : 0);
    }

    @Override
    public int getRowCount() {
      return getCollectedData().length;
    }

    private synchronized String[][] getCollectedData() {
      if (collectedData == null) {
        loadData();
      }
      return collectedData;
    }

    private synchronized void loadData() {
      // copy so that the object doesn't change underneath us
      final GameData gameData = EconomyPanel.this.gameData;
      try (GameData.Unlocker ignored = gameData.acquireReadLock()) {
        final List<GamePlayer> players = gameData.getPlayerList().getPlayers();
        // Flames and Steel (request 60): the sides of "FS Stats Sides", else the alliances
        final Map<String, List<GamePlayer>> totals = StatsSides.totalRows(gameData);
        final Resource pus =
            gameData.getResourceList().getResourceOptional(Constants.PUS).orElse(null);
        final Resource mp = StatsSides.manpower(gameData).orElse(null);
        collectedData = new String[players.size() + totals.size()][getColumnCount()];
        int row = 0;
        final Map<GamePlayer, IntegerMap<Resource>> resourceIncomeMap = new HashMap<>();
        for (final GamePlayer player : players) {
          collectedData[row][0] = player.getName();
          final IntegerMap<Resource> resourceIncomes =
              AbstractEndTurnDelegate.findEstimatedIncome(player, gameData);
          resourceIncomeMap.put(player, resourceIncomes);
          int column = 1;
          for (final ResourceStat resourceStat : resourceStats) {
            final double amount = resourceStat.getValue(player, gameData, uiContext.getMapData());
            final int income = resourceIncomes.getInt(resourceStat.resource);
            collectedData[row][column++] = getResourceAmountAndIncome(amount, income);
          }
          if (mpValue > 0) {
            collectedData[row][column] =
                getResourceAmountAndIncome(
                    puValue(List.of(player), pus, mp), puValue(resourceIncomes, pus, mp));
          }
          row++;
        }
        for (final Map.Entry<String, List<GamePlayer>> side : totals.entrySet()) {
          collectedData[row][0] = "<html><b>" + side.getKey();
          int column = 1;
          final IntegerMap<Resource> sideIncome = new IntegerMap<>();
          side.getValue().forEach(p -> sideIncome.add(resourceIncomeMap.get(p)));
          for (final ResourceStat resourceStat : resourceStats) {
            final double amount =
                side.getValue().stream()
                    .mapToDouble(p -> resourceStat.getValue(p, gameData, uiContext.getMapData()))
                    .sum();
            final int income = sideIncome.getInt(resourceStat.resource);
            collectedData[row][column++] = getResourceAmountAndIncome(amount, income);
          }
          if (mpValue > 0) {
            collectedData[row][column] =
                getResourceAmountAndIncome(
                    puValue(side.getValue(), pus, mp), puValue(sideIncome, pus, mp));
          }
          row++;
        }
      }
    }

    /** Request 60: the players' PUs plus their manpower at the set value. */
    private double puValue(final List<GamePlayer> players, final Resource pus, final Resource mp) {
      return players.stream()
          .mapToInt(
              p ->
                  (pus == null ? 0 : p.getResources().getQuantity(pus))
                      + (mp == null ? 0 : p.getResources().getQuantity(mp)) * mpValue)
          .sum();
    }

    private int puValue(final IntegerMap<Resource> income, final Resource pus, final Resource mp) {
      return (pus == null ? 0 : income.getInt(pus))
          + (mp == null ? 0 : income.getInt(mp)) * mpValue;
    }

    private String getResourceAmountAndIncome(final double amount, final int income) {
      final StringBuilder resourceAmountAndIncome =
          new StringBuilder("<html><b>" + IStat.DECIMAL_FORMAT.format(amount) + "</b>&nbsp;(");
      if (income >= 0) {
        resourceAmountAndIncome.append("+");
      }
      resourceAmountAndIncome.append(income).append(")</html>");
      return resourceAmountAndIncome.toString();
    }

    synchronized void markDirty() {
      collectedData = null;
    }
  }

  @Override
  public void gameDataChanged(final Change change) {
    resourceModel.markDirty();
    SwingUtilities.invokeLater(this::repaint);
  }

  public void setGameData(final GameData data) {
    gameData.removeDataChangeListener(this);
    gameData = data;
    gameData.addDataChangeListener(this);
    gameDataChanged(null);
  }
}
