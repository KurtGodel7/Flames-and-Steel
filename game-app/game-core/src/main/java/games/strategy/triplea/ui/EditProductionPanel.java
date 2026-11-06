package games.strategy.triplea.ui;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.NamedAttachable;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.NationUnits;
import games.strategy.triplea.image.UnitImageFactory;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import org.triplea.java.collections.IntegerMap;

/**
 * Edit mode's "Add Units" window. Flames and Steel (request 53 item 1): it offers only units that
 * make sense there: units the owning nation can have (see {@link NationUnits}), land units and
 * aircraft on land, ships and carrier aircraft at sea, each at the nation's real price or with no
 * price at all. A "Show all units" box, off by default, offers every unit with art, for repairs.
 */
class EditProductionPanel extends ProductionPanel {
  private static final long serialVersionUID = 5826523459539469173L;

  private final @Nullable Territory territory;
  private final JCheckBox showAll = new JCheckBox("Show all units");

  private EditProductionPanel(final UiContext uiContext, final @Nullable Territory territory) {
    super(uiContext);
    this.territory = territory;
    showAll.setToolTipText(
        "Also lists other nations' units and units that can't be in this territory, for rare"
            + " repairs.");
    showAll.addActionListener(e -> rebuildRules());
  }

  static IntegerMap<ProductionRule> getProduction(
      final GamePlayer gamePlayer,
      final JFrame parent,
      final GameData data,
      final UiContext uiContext) {
    return getProduction(gamePlayer, null, parent, data, uiContext);
  }

  static IntegerMap<ProductionRule> getProduction(
      final GamePlayer gamePlayer,
      final @Nullable Territory territory,
      final JFrame parent,
      final GameData data,
      final UiContext uiContext) {
    return new EditProductionPanel(uiContext, territory)
        .show(gamePlayer, parent, data, false, new IntegerMap<>());
  }

  @Override
  protected boolean enforcesPurchaseLimits() {
    return false;
  }

  @Override
  protected void calculateLimits() {
    for (final Rule current : getRules()) {
      current.setMax(99);
    }
  }

  @Override
  protected void initLayout() {
    super.initLayout();
    add(
        showAll,
        new GridBagConstraints(
            0,
            4,
            30,
            1,
            1,
            1,
            GridBagConstraints.WEST,
            GridBagConstraints.NONE,
            new Insets(0, 8, 8, 0),
            0,
            0));
  }

  /** "Show all units" changed: list again, keeping the counts already typed. */
  private void rebuildRules() {
    final Map<UnitType, Integer> counts = new HashMap<>();
    for (final Rule r : getRules()) {
      if (r.getQuantity() > 0 && r.getProductionRule().getAnyResultKey() instanceof UnitType t) {
        counts.merge(t, r.getQuantity(), Integer::sum);
      }
    }
    rules.clear();
    initRules(gamePlayer, new IntegerMap<>());
    for (final Rule r : getRules()) {
      if (r.getProductionRule().getAnyResultKey() instanceof UnitType t) {
        r.setQuantity(counts.getOrDefault(t, 0));
      }
    }
    initLayout();
    calculateLimits();
    revalidate();
    if (dialog != null) {
      dialog.pack();
      dialog.setLocationRelativeTo(dialog.getParent());
    }
  }

  @Override
  protected void initRules(
      final GamePlayer player, final IntegerMap<ProductionRule> initialPurchase) {
    try (GameData.Unlocker ignored = this.data.acquireReadLock()) {
      gamePlayer = player;
      final Map<UnitType, Optional<ProductionRule>> offered =
          new LinkedHashMap<>(NationUnits.of(player));
      if (!showAll.isSelected()) {
        offered.keySet().removeIf(t -> territory != null && !NationUnits.fits(t, territory));
      } else {
        // every unit we have art for, for rare repairs
        for (final UnitType ut : data.getUnitTypeList().getAllUnitTypes()) {
          if (!offered.containsKey(ut) && hasArt(player, ut)) {
            offered.put(ut, Optional.empty());
          }
        }
      }
      offered.forEach(
          (type, rule) -> {
            final Rule r = new Rule(rule.orElseGet(() -> freeRule(type)), player);
            r.setQuantity(initialPurchase.getInt(r.getProductionRule()));
            rules.add(r);
          });
    }
  }

  private boolean hasArt(final GamePlayer player, final UnitType ut) {
    try {
      final UnitImageFactory imageFactory = uiContext.getUnitImageFactory();
      return imageFactory != null
          && imageFactory.hasImage(
              UnitImageFactory.ImageKey.builder().player(player).type(ut).build());
    } catch (final Exception e) {
      return false;
    }
  }

  /** A rule for a unit never bought: no price shown (a placeholder price would mislead). */
  private ProductionRule freeRule(final UnitType ut) {
    final IntegerMap<NamedAttachable> result = new IntegerMap<>();
    result.add(ut, 1);
    return new ProductionRule(ut.getName(), data, result, new IntegerMap<Resource>());
  }
}
