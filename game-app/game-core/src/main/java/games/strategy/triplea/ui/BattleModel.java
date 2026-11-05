package games.strategy.triplea.ui;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.TerritoryEffect;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Properties;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.battle.BattleState;
import games.strategy.triplea.delegate.battle.IBattle;
import games.strategy.triplea.delegate.power.calculator.CombatValue;
import games.strategy.triplea.delegate.power.calculator.CombatValueBuilder;
import games.strategy.triplea.delegate.power.calculator.PowerStrengthAndRolls;
import games.strategy.triplea.image.HitPointBar;
import games.strategy.triplea.image.UnitImageFactory;
import games.strategy.triplea.util.UnitCategory;
import games.strategy.triplea.util.UnitSeparator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.table.DefaultTableModel;

final class BattleModel extends DefaultTableModel {
  private static final long serialVersionUID = 6913324191512043963L;
  private final UiContext uiContext;
  private final GameData gameData;
  // is the player the aggressor?
  private final BattleState.Side side;
  // Flames and Steel (request 14): changed on the Swing thread, read from the game thread too (the
  // retreat summary); a copy-on-write list can be read from any thread while it changes
  private final Collection<Unit> units;
  private final IBattle.BattleType battleType;
  private final Collection<TerritoryEffect> territoryEffects;
  // Flames and Steel: where the battle is; an air phase battle on an air front is a dogfight
  private final Territory location;
  // Flames and Steel: the other side as an air phase battle began. Losses can empty a side, and
  // with no enemies left the survivors of a dogfight were shown at land-strike values.
  private List<Unit> airBattleEnemies;
  private BattleModel enemyBattleModel = null;
  // Flames and Steel: the side's total power at the last refresh, for "about 1.3 hits a round"
  private volatile int lastTotalPower;
  // Flames and Steel (request 55): this round, attackers landing from the sea hold fire
  private volatile boolean landingHoldsFire;

  /**
   * Request 55: whether this round's attackers landing from the sea hold fire (round 1). They are
   * then shown in the blank column with a "Landing" badge.
   */
  void setLandingHoldsFire(final boolean landingHoldsFire) {
    if (this.landingHoldsFire != landingHoldsFire) {
      this.landingHoldsFire = landingHoldsFire;
      refresh();
    }
  }

  /** "British: 2 battleships 3/5, 5/5; 8 HP left; about 1.3 hits a round". */
  String summary(final GamePlayer player) {
    return BattleDamageText.sideSummary(player, units, lastTotalPower, gameData.getDiceSides());
  }

  BattleModel(
      final Collection<Unit> units,
      final BattleState.Side side,
      final IBattle.BattleType battleType,
      final GameData data,
      final Collection<TerritoryEffect> territoryEffects,
      final Territory location,
      final UiContext uiContext) {
    super(new Object[0][0], varDiceArray(data));
    this.uiContext = uiContext;
    gameData = data;
    this.side = side;
    // were going to modify the units
    this.units = new java.util.concurrent.CopyOnWriteArrayList<>(units);
    this.battleType = battleType;
    this.territoryEffects = territoryEffects;
    this.location = location;
  }

  private static String[] varDiceArray(final GameData data) {
    // TODO Soft set the maximum bonus to-hit plus 1 for 0 based count(+2 total currently)
    final String[] diceColumns = new String[data.getDiceSides() + 1];
    {
      for (int i = 0; i < diceColumns.length; i++) {
        if (i == 0) {
          diceColumns[i] = " ";
        } else {
          diceColumns[i] = Integer.toString(i);
        }
      }
    }
    return diceColumns;
  }

  void setEnemyBattleModel(final BattleModel enemyBattleModel) {
    this.enemyBattleModel = enemyBattleModel;
  }

  void notifyRetreat(final Collection<Unit> retreating) {
    if (units.removeAll(retreating)) {
      refresh();
    }
  }

  void removeCasualties(final Collection<Unit> killed) {
    if (units.removeAll(killed)) {
      refresh();
    }
  }

  void addUnits(final Collection<Unit> units) {
    if (this.units.addAll(units)) {
      refresh();
    }
  }

  Collection<Unit> getUnits() {
    return units;
  }

  /** refresh the model from units. */
  void refresh() {
    // TODO Soft set the maximum bonus to-hit plus 1 for 0 based count(+2 total currently)
    // Soft code the # of columns

    final List<List<TableData>> columns = new ArrayList<>(gameData.getDiceSides() + 1);
    for (int i = 0; i <= gameData.getDiceSides(); i++) {
      columns.add(i, new ArrayList<>());
    }
    final List<Unit> units = new ArrayList<>(this.units);
    // request 55: attackers landing from the sea roll nothing and give no support in round 1
    final java.util.Set<Unit> landing = landingUnits(units);
    final List<Unit> firing = new ArrayList<>(units);
    firing.removeAll(landing);
    final PowerStrengthAndRolls unitPowerAndRollsMap;
    final boolean isAirPreBattleOrPreRaid = battleType.isAirBattle();
    // Flames and Steel air phase battles show their own values (dogfight, strike, AA, fire back)
    final java.util.function.ToIntFunction<Unit> airZoneStrength;
    final boolean lhtrHeavyBombers = Properties.getLhtrHeavyBombers(gameData.getProperties());
    try (GameData.Unlocker ignored = gameData.acquireReadLock()) {
      final CombatValue combatValue;
      if (isAirPreBattleOrPreRaid) {
        combatValue =
            CombatValueBuilder.airBattleCombatValue()
                .side(BattleState.Side.DEFENSE)
                .lhtrHeavyBombers(lhtrHeavyBombers)
                .gameDiceSides(gameData.getDiceSides())
                .build();
      } else {
        combatValue =
            CombatValueBuilder.mainCombatValue()
                .enemyUnits(new ArrayList<>(enemyBattleModel.getUnits()))
                .friendlyUnits(firing)
                .side(side)
                .gameSequence(gameData.getSequence())
                .supportAttachments(gameData.getUnitTypeList().getSupportRules())
                .lhtrHeavyBombers(lhtrHeavyBombers)
                .gameDiceSides(gameData.getDiceSides())
                .territoryEffects(territoryEffects)
                .build();
      }
      unitPowerAndRollsMap = PowerStrengthAndRolls.build(firing, combatValue);
      lastTotalPower = unitPowerAndRollsMap.calculateTotalPower();
      if (airBattleEnemies == null && isAirPreBattleOrPreRaid) {
        final List<Unit> enemies =
            enemyBattleModel == null ? List.of() : new ArrayList<>(enemyBattleModel.getUnits());
        airBattleEnemies = AirCombat.isAirZoneBattle(units, enemies) ? enemies : List.of();
      }
      final boolean dogfight = AirCombat.isDogfight(location);
      airZoneStrength =
          isAirPreBattleOrPreRaid && (dogfight || !airBattleEnemies.isEmpty())
              ? u ->
                  Math.min(
                      gameData.getDiceSides(),
                      AirCombat.displayStrength(u, dogfight, airBattleEnemies))
              : null;
    }
    // Flames and Steel (request 25): ships supported by other ships (battleship support) are
    // marked, e.g. "+1 Battleship"
    final Map<Unit, String> shipSupport =
        airZoneStrength != null ? Map.of() : shipSupport(unitPowerAndRollsMap);
    final List<UnitCategory> unitCategories =
        UnitSeparator.getSortedUnitCategories(units, gameData, uiContext.getMapData());
    for (final UnitCategory category : unitCategories) {
      // per value rolled at, per support mark ("" for none): how many units
      final List<Map<String, Integer>> shift = new ArrayList<>();
      for (int i = 0; i <= gameData.getDiceSides(); i++) {
        shift.add(new java.util.TreeMap<>());
      }
      for (final Unit current : category.getUnits()) {
        if (landing.contains(current)) {
          shift.get(0).merge(TableData.LANDING, 1, Integer::sum);
          continue;
        }
        final int strength =
            airZoneStrength != null
                ? airZoneStrength.applyAsInt(current)
                : unitPowerAndRollsMap.getStrength(current);
        shift.get(strength).merge(shipSupport.getOrDefault(current, ""), 1, Integer::sum);
      }
      for (int i = 0; i <= gameData.getDiceSides(); i++) {
        final int value = i;
        shift
            .get(i)
            .forEach(
                (support, count) ->
                    columns
                        .get(value)
                        .add(
                            new TableData(
                                UnitImageFactory.ImageKey.of(category),
                                count,
                                uiContext,
                                category.getDamaged(),
                                category.getHitPoints(),
                                support,
                                value)));
      }
      // TODO Kev determine if we need to identify if the unit is hit/disabled
    }
    // find the number of rows
    // this will be the size of the largest column
    int rowCount = 1;
    for (final List<TableData> column : columns) {
      rowCount = Math.max(rowCount, column.size());
    }
    setNumRows(rowCount);
    for (int row = 0; row < rowCount; row++) {
      for (int column = 0; column < columns.size(); column++) {
        // if the column has that many items, add to the table, else add null
        if (columns.get(column).size() > row) {
          setValueAt(columns.get(column).get(row), row, column);
        } else {
          setValueAt(TableData.NULL, row, column);
        }
      }
    }
  }

  /** Request 55: the attackers shown as landing from the sea (holding fire) this round. */
  private java.util.Set<Unit> landingUnits(final Collection<Unit> units) {
    if (!landingHoldsFire
        || side != BattleState.Side.OFFENSE
        || battleType.isAirBattle()
        || location == null
        || location.isWater()) {
      return java.util.Set.of();
    }
    final java.util.Set<String> exempt =
        games.strategy.triplea.delegate.battle.AmphibiousLanding.exemptTypes(
            gameData.getProperties());
    final java.util.Set<Unit> result = new java.util.HashSet<>();
    for (final Unit unit : units) {
      if (games.strategy.triplea.delegate.battle.AmphibiousLanding.isLanding(unit, exempt)) {
        result.add(unit);
      }
    }
    return result;
  }

  /**
   * Request 25: for each ship supported by other ships, the support in words, e.g. "+1 Battleship"
   * or "+1 Yamato Battleship".
   */
  static Map<Unit, String> shipSupport(final PowerStrengthAndRolls power) {
    final Map<Unit, Map<String, Integer>> bySupporter = new java.util.HashMap<>();
    power
        .getUnitSupportPowerMap()
        .forEach(
            (supporter, supported) -> {
              if (!Matches.unitIsSea().test(supporter)) {
                return;
              }
              for (final Unit unit : supported.keySet()) {
                if (Matches.unitIsSea().test(unit) && supported.getInt(unit) != 0) {
                  bySupporter
                      .computeIfAbsent(unit, k -> new java.util.TreeMap<>())
                      .merge(
                          games.strategy.triplea.delegate.battle.casualty.StickyCasualtyChoice
                              .displayName(supporter.getType()),
                          supported.getInt(unit),
                          Integer::sum);
                }
              }
            });
    final Map<Unit, String> result = new java.util.HashMap<>();
    bySupporter.forEach(
        (unit, bonuses) -> {
          final List<String> parts = new ArrayList<>();
          bonuses.forEach((name, bonus) -> parts.add((bonus > 0 ? "+" : "") + bonus + " " + name));
          result.put(unit, String.join(", ", parts));
        });
    return result;
  }

  @Override
  public boolean isCellEditable(final int row, final int column) {
    return false;
  }

  static final class TableData {
    static final TableData NULL = new TableData();
    // request 55: the support mark of attackers landing from the sea (holding fire this round)
    static final String LANDING = "Landing";
    private GamePlayer player;
    private UnitType unitType;
    private int count;
    private ImageIcon icon;
    private int hits;
    private int hitPoints;
    // request 25: e.g. "+1 Battleship" for ships supported by a battleship, or ""
    private String support = "";
    // the value these units roll at
    private int value;

    private UiContext uiContext;

    private TableData() {}

    TableData(
        final UnitImageFactory.ImageKey imageKey,
        final int count,
        final UiContext uiContext,
        final int hits,
        final int hitPoints,
        final String support,
        final int value) {
      this.support = support;
      this.value = value;
      this.player = imageKey.getPlayer();
      this.count = count;
      this.unitType = imageKey.getType();
      // Flames and Steel: units with 2+ hit points carry their hit-point bar
      final ImageIcon plain =
          HitPointBar.withBar(
              uiContext.getUnitImageFactory().getIcon(imageKey), hitPoints, hits, 1.0);
      // request 25: supported ships carry a green "+1" badge (the tooltip says from what)
      // request 55: units landing from the sea carry a grey "Landing" badge
      this.icon =
          support.isEmpty()
              ? plain
              : LANDING.equals(support)
                  ? withBadge(plain, LANDING, new java.awt.Color(0x60, 0x60, 0x60))
                  : withSupportBadge(plain, support.split(" ")[0]);
      this.hits = hits;
      this.hitPoints = hitPoints;
      this.uiContext = uiContext;
    }

    /** The icon with a small green badge, e.g. "+1", in its top left corner. */
    static ImageIcon withSupportBadge(final ImageIcon icon, final String badge) {
      return withBadge(icon, badge, new java.awt.Color(0x2E, 0x7D, 0x32));
    }

    /** The icon with a small badge of the given colour in its top left corner. */
    static ImageIcon withBadge(
        final ImageIcon icon, final String badge, final java.awt.Color background) {
      final int width = Math.max(1, icon.getIconWidth());
      final int height = Math.max(1, icon.getIconHeight());
      final java.awt.image.BufferedImage image =
          new java.awt.image.BufferedImage(
              width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
      final java.awt.Graphics2D g = image.createGraphics();
      g.drawImage(icon.getImage(), 0, 0, null);
      g.setRenderingHint(
          java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
      g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 12));
      final java.awt.FontMetrics fm = g.getFontMetrics();
      final int w = fm.stringWidth(badge) + 8;
      final int h = fm.getHeight();
      g.setColor(background);
      g.fillRoundRect(0, 0, w, h, 8, 8);
      g.setColor(java.awt.Color.WHITE);
      g.drawString(badge, 4, fm.getAscent());
      g.dispose();
      return new ImageIcon(image);
    }

    void updateStamp(final JLabel stamp) {
      if (count == 0) {
        stamp.setText("");
        stamp.setIcon(null);
        stamp.setToolTipText(null);
      } else {
        // Flames and Steel: multi-hit units say what they have left, e.g. "x1  3/5"
        final String text =
            "x"
                + count
                + (hitPoints > 1 ? "  " + Math.max(0, hitPoints - hits) + "/" + hitPoints : "");
        stamp.setText(text);
        stamp.setIcon(icon);
        MapUnitTooltipManager.setUnitTooltip(
            stamp, unitType, player, count, hits, hitPoints, uiContext);
        if (LANDING.equals(support)) {
          stamp.setToolTipText(
              "<html><b>Landing from the sea:</b> "
                  + (count == 1 ? "this unit holds" : "these units hold")
                  + " fire this round and can be taken as casualties. "
                  + (count == 1 ? "It fires" : "They fire")
                  + " from round 2.<br>"
                  + Objects.toString(stamp.getToolTipText(), "")
                      .replaceFirst("(?i)^<html>", "")
                      .replaceFirst("(?i)</html>$", "")
                  + "</html>");
        } else if (!support.isEmpty()) {
          stamp.setToolTipText(
              "<html><b>"
                  + support
                  + " support:</b> "
                  + (count == 1 ? "this ship rolls" : "these ships roll")
                  + " at "
                  + value
                  + ".<br>"
                  + Objects.toString(stamp.getToolTipText(), "")
                      .replaceFirst("(?i)^<html>", "")
                      .replaceFirst("(?i)</html>$", "")
                  + "</html>");
        }
      }
    }
  }
}
