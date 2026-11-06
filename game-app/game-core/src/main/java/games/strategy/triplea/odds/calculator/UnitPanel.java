package games.strategy.triplea.odds.calculator;

import static games.strategy.triplea.image.UnitImageFactory.ImageKey;

import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.ui.UiContext;
import games.strategy.triplea.util.UnitCategory;
import games.strategy.ui.ScrollableTextField;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.triplea.java.collections.IntegerMap;

/** Panel showing a unit image. */
public class UnitPanel extends JPanel {
  private static final long serialVersionUID = 1509643150038705671L;
  private final UnitCategory category;
  private final ScrollableTextField textField;
  // Flames and Steel (request 58): how many of these attackers land from the sea (null: no box)
  @Nullable private final ScrollableTextField fromSeaField;
  private final List<Runnable> listeners = new ArrayList<>();

  UnitPanel(
      final UiContext uiContext, final UnitCategory category, final IntegerMap<UnitType> costs) {
    this(uiContext, category, costs, false);
  }

  /**
   * @param fromSea whether to show a second box: how many of these units land from the sea
   *     (attacking land units in a land battle), prefilled from the units' moves this turn
   */
  UnitPanel(
      final UiContext uiContext,
      final UnitCategory category,
      final IntegerMap<UnitType> costs,
      final boolean fromSea) {
    this.category = category;
    textField = new ScrollableTextField(0, 512);
    textField.setShowMaxAndMin(false);
    if (fromSea) {
      fromSeaField = new ScrollableTextField(0, 512);
      fromSeaField.setShowMaxAndMin(false);
      fromSeaField.setToolTipText(
          "How many of these land from the sea this turn: they hold fire and give no support in"
              + " round 1, and they allow shore bombardment");
      fromSeaField.addChangeListener(field -> notifyListeners());
    } else {
      fromSeaField = null;
    }
    textField.addChangeListener(
        field -> {
          if (fromSeaField != null) {
            fromSeaField.setMax(textField.getValue());
          }
          notifyListeners();
        });

    final String toolTipText =
        "<html>"
            + category.getType().getName()
            + ":  "
            + costs.getInt(category.getType())
            + " cost, <br /> &nbsp;&nbsp;&nbsp;&nbsp; "
            + uiContext.getTooltipProperties().getTooltip(category.getType(), category.getOwner())
            + "</html>";
    setCount(category.getUnits().size());
    if (fromSeaField != null) {
      fromSeaField.setMax(category.getUnits().size());
      fromSeaField.setValue(
          (int) category.getUnits().stream().filter(Unit::getWasAmphibious).count());
    }
    setLayout(new GridBagLayout());

    final JLabel label = new JLabel(uiContext.getUnitImageFactory().getIcon(ImageKey.of(category)));
    label.setToolTipText(toolTipText);
    add(
        label,
        new GridBagConstraints(
            0,
            0,
            1,
            1,
            0,
            0,
            GridBagConstraints.EAST,
            GridBagConstraints.NONE,
            new Insets(0, 0, 0, 10),
            0,
            0));
    add(
        textField,
        new GridBagConstraints(
            1,
            0,
            1,
            1,
            0,
            0,
            GridBagConstraints.EAST,
            GridBagConstraints.NONE,
            new Insets(0, 0, 0, 0),
            0,
            0));
    if (fromSeaField != null) {
      final JLabel seaLabel = new JLabel("from sea");
      seaLabel.setToolTipText(fromSeaField.getToolTipText());
      add(
          seaLabel,
          new GridBagConstraints(
              2,
              0,
              1,
              1,
              0,
              0,
              GridBagConstraints.EAST,
              GridBagConstraints.NONE,
              new Insets(0, 8, 0, 4),
              0,
              0));
      add(
          fromSeaField,
          new GridBagConstraints(
              3,
              0,
              1,
              1,
              0,
              0,
              GridBagConstraints.EAST,
              GridBagConstraints.NONE,
              new Insets(0, 0, 0, 0),
              0,
              0));
    }
  }

  List<Unit> getUnits() {
    final List<Unit> units =
        category.getType().createTemp(textField.getValue(), category.getOwner());
    if (!units.isEmpty()) {
      // creating the unit just makes it, we want to make sure it is damaged if the category says it
      // is damaged
      if (category.getHitPoints() > 1 && category.getDamaged() > 0) {
        // we do not need to use bridge and change factory here because this is not sent over the
        // network. these are
        // just some temporary units for the battle calc.
        for (final Unit u : units) {
          u.setHits(category.getDamaged());
        }
      }
      if (category.getDisabled() && Matches.unitTypeCanBeDamaged().test(category.getType())) {
        // add 1 because it is the max operational damage and we want to disable it
        final int unitDamage =
            Math.max(0, 1 + category.getUnitAttachment().getMaxOperationalDamage());
        for (final Unit unit : units) {
          unit.setUnitDamage(unitDamage);
        }
      }
      // request 58: the first "from sea" of them landed from the sea this turn
      final int fromSea = Math.min(getFromSea(), units.size());
      for (int i = 0; i < fromSea; i++) {
        units.get(i).setWasAmphibious(true);
      }
    }
    return units;
  }

  /** Request 58: how many of these land from the sea (0 without the box). */
  int getFromSea() {
    return fromSeaField == null ? 0 : Math.min(fromSeaField.getValue(), textField.getValue());
  }

  /** Request 58: sets how many land from the sea (no effect without the box). */
  void setFromSea(final int value) {
    if (fromSeaField != null) {
      fromSeaField.setValue(Math.min(value, textField.getValue()));
    }
  }

  boolean hasFromSea() {
    return fromSeaField != null;
  }

  void setCount(final int value) {
    textField.setValue(value);
  }

  int getCount() {
    return textField.getValue();
  }

  void addChangeListener(final Runnable listener) {
    listeners.add(listener);
  }

  private void notifyListeners() {
    listeners.forEach(Runnable::run);
  }
}
