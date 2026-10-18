package games.strategy.triplea.ui.screen;

import static games.strategy.triplea.image.UnitImageFactory.ImageKey;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Properties;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.image.HitPointBar;
import games.strategy.triplea.image.MapImage;
import games.strategy.triplea.image.UnitImageFactory;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.triplea.ui.UiContext;
import games.strategy.triplea.ui.mapdata.MapData;
import games.strategy.triplea.ui.screen.drawable.AbstractDrawable;
import games.strategy.triplea.util.UnitCategory;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import lombok.Getter;

/**
 * Draws units for the associated territory.
 *
 * <p>If all units cannot be drawn within the territory bounds, they will be drawn in a single
 * horizontal row, overflowing to the right of the territory. A solid black line, rooted at the
 * territory's default placement point, will be drawn under all units in this case.
 */
public class UnitsDrawer extends AbstractDrawable {
  /** Pixels between the icon's bottom edge and the hit-point bar (clear of the count outline). */
  private static final int BAR_SPACING = 2;

  private static final int DRAW_MARGIN = 6;
  private static final int OVERFLOW_LINE_HEIGHT = 3;

  @Getter private final Point placementPoint;
  private final boolean overflow;
  private final UiContext uiContext;
  private final UnitCategory unitCategory;
  @Getter @Nullable private final Territory territory;

  /** Identifies the location where a nation flag is drawn relative to a unit. */
  public enum UnitFlagDrawMode {
    NONE,
    SMALL_FLAG,
    LARGE_FLAG,
  }

  public UnitsDrawer(
      final UnitCategory unitCategory,
      final @Nullable Territory territory,
      final Point placementPoint,
      final boolean overflow,
      final UiContext uiContext) {

    this.unitCategory = unitCategory;
    this.territory = territory;
    this.placementPoint = placementPoint;
    this.overflow = overflow;
    this.uiContext = uiContext;
  }

  public Rectangle getPlacementRectangle() {
    UnitImageFactory factory = uiContext.getUnitImageFactory();
    return new Rectangle(
        placementPoint.x,
        placementPoint.y,
        factory.getUnitImageWidth(),
        factory.getUnitImageHeight());
  }

  public String getPlayer() {
    return unitCategory.getOwner().getName();
  }

  @Override
  public void draw(
      final Rectangle bounds,
      final GameData data,
      final Graphics2D graphics,
      final MapData mapData) {
    // If there are too many Units at one point a black line is drawn to make clear which units
    // belong to where
    final var factory = uiContext.getUnitImageFactory();
    if (overflow) {
      // Flames and Steel: below the room for hit-point bars, so the bars never merge into it
      graphics.setColor(Color.BLACK);
      graphics.fillRect(
          placementPoint.x - bounds.x - 2,
          placementPoint.y - bounds.y + overflowLineOffset(),
          factory.getUnitImageWidth() + 2,
          OVERFLOW_LINE_HEIGHT);
    }
    final GamePlayer owner = unitCategory.getOwner();
    final boolean damagedImage =
        unitCategory.getDamaged() > 0 || unitCategory.getBombingDamage() > 0;

    final UnitType unitType = unitCategory.getType();
    final var imageKey =
        ImageKey.builder()
            .type(unitType)
            .player(owner)
            .damaged(damagedImage)
            .disabled(unitCategory.getDisabled())
            .build();

    final Image img = factory.getImage(imageKey);
    final int maxRange = new Unit(unitType, owner, data).getMaxMovementAllowed();

    drawUnitByDrawMode(bounds, graphics, maxRange, owner, img);

    // more than 1 unit of this category
    int unitsCount = unitCategory.getUnits().size();
    if (unitsCount != 1) {
      drawMultipleUnits(bounds, graphics, mapData, unitsCount, img, factory);
    }
    displayHitPoints(bounds, graphics, img);
    // Display Factory Damage
    if (Properties.getDamageFromBombingDoneToUnitsInsteadOfTerritories(data.getProperties())
        && Matches.unitTypeCanBeDamaged().test(unitType)) {
      displayFactoryDamage(bounds, graphics);
    }
  }

  private void drawUnitByDrawMode(
      Rectangle bounds, Graphics2D graphics, int maxRange, GamePlayer owner, Image img) {
    final UnitFlagDrawMode drawMode =
        ClientSetting.unitFlagDrawMode.getValue().orElse(UnitFlagDrawMode.NONE);

    if (drawMode == UnitFlagDrawMode.LARGE_FLAG) {
      // If unit is not in the "excluded list" it will get drawn
      if (maxRange != 0) {
        final Image flag = uiContext.getFlagImageFactory().getFlag(owner);
        final int xOffset = img.getWidth(null) / 2 - flag.getWidth(null) / 2;
        final int yOffset = img.getHeight(null) / 2 - flag.getHeight(null) / 4 - 5;
        graphics.drawImage(
            flag,
            (placementPoint.x - bounds.x) + xOffset,
            (placementPoint.y - bounds.y) + yOffset,
            null);
      }
      drawUnit(graphics, img, bounds);
    } else if (drawMode == UnitFlagDrawMode.SMALL_FLAG) {
      drawUnit(graphics, img, bounds);
      // If unit is not in the "excluded list" it will get drawn
      if (maxRange != 0) {
        final Image flag = uiContext.getFlagImageFactory().getSmallFlag(owner);
        final int xOffset = img.getWidth(null) - flag.getWidth(null);
        final int yOffset = img.getHeight(null) - flag.getHeight(null);
        // This Method draws the Flag in the lower right corner of the unit image. Since the
        // position is the upper left corner we have to move the picture up by the height and
        // left by the width.
        graphics.drawImage(
            flag,
            (placementPoint.x - bounds.x) + xOffset,
            (placementPoint.y - bounds.y) + yOffset,
            null);
      }
    } else {
      drawUnit(graphics, img, bounds);
    }
  }

  private void drawMultipleUnits(
      Rectangle bounds,
      Graphics2D graphics,
      MapData mapData,
      int unitsCount,
      Image img,
      UnitImageFactory factory) {
    final int stackSize = mapData.getDefaultUnitsStackSize();
    if (stackSize > 0) { // Display more units as a stack
      for (int i = 1; i < unitsCount && i < stackSize; i++) {
        graphics.drawImage(
            img, placementPoint.x + 2 * i - bounds.x, placementPoint.y - 2 * i - bounds.y, null);
      }
      if (unitsCount > stackSize) {
        final String s = String.valueOf(unitsCount);

        drawOutlinedText(
            graphics,
            s,
            placementPoint.x - bounds.x + 2 * stackSize + factory.getUnitImageWidth() * 6 / 10,
            placementPoint.y - 2 * stackSize - bounds.y + factory.getUnitImageHeight() / 3,
            MapImage.getPropertyUnitCountColor(),
            MapImage.getPropertyUnitCountOutline());
      }
    } else { // Display a white number at the bottom of the unit
      final String s = String.valueOf(unitsCount);
      drawOutlinedText(
          graphics,
          s,
          placementPoint.x - bounds.x + factory.getUnitCounterOffsetWidth(),
          placementPoint.y - bounds.y + factory.getUnitCounterOffsetHeight(),
          MapImage.getPropertyUnitCountColor(),
          MapImage.getPropertyUnitCountOutline());
    }
  }

  /** This draws the given image onto the given graphics object. */
  private void drawUnit(final Graphics2D graphics, final Image image, final Rectangle bounds) {
    graphics.drawImage(image, placementPoint.x - bounds.x, placementPoint.y - bounds.y, null);

    // draw unit icons in top right corner
    final List<Image> unitIcons =
        uiContext
            .getUnitIconImageFactory()
            .getImages(unitCategory.getOwner().getName(), unitCategory.getType().getName());
    for (final Image unitIcon : unitIcons) {
      final int xOffset = image.getWidth(null) - unitIcon.getWidth(null);
      graphics.drawImage(
          unitIcon, (placementPoint.x - bounds.x) + xOffset, (placementPoint.y - bounds.y), null);
    }
  }

  /** Hit points of the units in this group (all share type, owner and damage), with techs. */
  private int hitPoints() {
    return unitCategory.getUnits().isEmpty()
        ? unitCategory.getType().getUnitAttachment().getHitPoints()
        : Unit.maxHitPoints(unitCategory.getUnits().get(0));
  }

  /**
   * Flames and Steel: units with 2 or more hit points show a hit-point bar under the icon (hit
   * points include tech bonuses), clear of the unit-count number drawn on the icon's bottom edge.
   */
  private void displayHitPoints(
      final Rectangle bounds, final Graphics2D graphics, final Image img) {
    HitPointBar.paint(
        graphics,
        placementPoint.x - bounds.x,
        placementPoint.y - bounds.y + img.getHeight(null) + BAR_SPACING,
        img.getWidth(null),
        hitPoints(),
        unitCategory.getDamaged(),
        uiContext.getUnitImageFactory().getScaleFactor());
  }

  /**
   * The map area this drawer paints on: its own image (which can be larger than the map's unit
   * size, e.g. the Yamato), the hit-point bar under it and a small margin for the count and flag.
   * The map uses it to pick the tiles to draw on, so nothing is cut off at a tile edge.
   */
  /**
   * Distance from the top of the unit to the overflow line: under the unit's image and the room a
   * two-row hit-point bar needs.
   */
  private int overflowLineOffset() {
    final var factory = uiContext.getUnitImageFactory();
    final int imageHeight =
        Math.max(
            factory.getUnitImageHeight(),
            factory.getImage(ImageKey.of(unitCategory)).getHeight(null));
    return imageHeight + BAR_SPACING + HitPointBar.height(6, factory.getScaleFactor()) + 1;
  }

  public Rectangle getDrawRectangle() {
    final var factory = uiContext.getUnitImageFactory();
    final Image img = factory.getImage(ImageKey.of(unitCategory));
    final int width = Math.max(factory.getUnitImageWidth(), img.getWidth(null));
    final int height =
        overflow
            ? overflowLineOffset() + OVERFLOW_LINE_HEIGHT
            : Math.max(factory.getUnitImageHeight(), img.getHeight(null))
                + BAR_SPACING
                + HitPointBar.height(hitPoints(), factory.getScaleFactor());
    return new Rectangle(
        placementPoint.x - DRAW_MARGIN,
        placementPoint.y - DRAW_MARGIN,
        width + 2 * DRAW_MARGIN,
        height + 2 * DRAW_MARGIN);
  }

  private void displayFactoryDamage(final Rectangle bounds, final Graphics2D graphics) {
    int bombingDamage = unitCategory.getBombingDamage();
    if (bombingDamage > 0) {
      final String s = String.valueOf(bombingDamage);
      final var factory = uiContext.getUnitImageFactory();
      drawOutlinedText(
          graphics,
          s,
          placementPoint.x - bounds.x + factory.getUnitImageWidth() / 4,
          placementPoint.y - bounds.y + factory.getUnitImageHeight() / 4,
          MapImage.getPropertyUnitFactoryDamageColor(),
          MapImage.getPropertyUnitFactoryDamageOutline());
    }
  }

  public static void drawOutlinedText(
      final Graphics2D graphics,
      final String s,
      final int x,
      final int y,
      final Color textColor,
      final Color outlineColor) {
    final var font = MapImage.getPropertyMapFont();
    if (font.getSize() > 0) {
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      graphics.setFont(font);
      graphics.setColor(outlineColor);
      graphics.drawString(s, x - 1, y - 1);
      graphics.drawString(s, x - 1, y + 1);
      graphics.drawString(s, x + 1, y + 1);
      graphics.drawString(s, x + 1, y - 1);
      graphics.setColor(textColor);
      graphics.drawString(s, x, y);
    }
  }

  List<Unit> getUnits() {
    // note - it may be the case where the territory is being changed as a result to a mouse click,
    // and the map units haven't updated yet, so the unit count from the territory won't match the
    // units in count
    if (territory == null) {
      return Collections.emptyList();
    }
    final UnitType unitType = unitCategory.getType();
    final Predicate<Unit> selectedUnits =
        Matches.unitIsOfType(unitType)
            .and(Matches.unitIsOwnedBy(unitCategory.getOwner()))
            .and(
                unitCategory.getDamaged() > 0
                    ? Matches.unitHasTakenSomeDamage()
                    : Matches.unitHasNotTakenAnyDamage())
            .and(
                unitCategory.getBombingDamage() > 0
                    ? Matches.unitHasTakenSomeBombingUnitDamage()
                    : Matches.unitHasNotTakenAnyBombingUnitDamage());
    return territory.getMatches(selectedUnits);
  }

  @Override
  public DrawLevel getLevel() {
    return DrawLevel.UNITS_LEVEL;
  }
}
