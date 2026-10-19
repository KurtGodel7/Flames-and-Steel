package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.Territory;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.ui.mapdata.MapData;
import games.strategy.triplea.util.UnitSeparator;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.prefs.Preferences;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Flames and Steel (request 9 item 4, request 20): where each air front's box sits on the air map.
 * A box goes to the spot nearest its front's centre, over a sea zone in or next to the front, that
 * covers no land (nor a land border), unit stack, territory name, strip box or other box, so it
 * only ever sits over open sea; with no such spot it goes to its front's own place in the strip
 * under the map. A box the player dragged stays where it was left (remembered between games on this
 * computer).
 */
@Slf4j
final class BoxPlacer {
  private static final int STEP = 8;
  private static final int MAX_RADIUS = 1200;
  private static final String PREFS_NODE = "flames_and_steel/airBoxSpots";

  private final Map<Territory, Placement> auto = new HashMap<>();
  private final Map<String, Point> user = new HashMap<>();
  private final Map<Territory, Rectangle> boxes = new HashMap<>();
  // request 20: fronts with no clear sea near them; their boxes dock at the edge of the view
  private final java.util.Set<Territory> docked = new java.util.HashSet<>();
  private List<Rectangle> keepClear = List.of();
  private List<Rectangle> strip = List.of();

  BoxPlacer() {
    try {
      final Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
      for (final String key : prefs.keys()) {
        final String[] xy = prefs.get(key, "").split(",");
        if (xy.length == 2) {
          user.put(key, new Point(Integer.parseInt(xy[0]), Integer.parseInt(xy[1])));
        }
      }
    } catch (final Exception e) {
      log.debug("No saved air box positions", e);
    }
  }

  /** What boxes must not cover: unit stacks and territory names. Call when the map changes. */
  void refresh(final GameData d, final MapData mapData, final int unitWidth, final int unitHeight) {
    final List<Rectangle> clear = new ArrayList<>();
    final List<Rectangle> boxStrip = new ArrayList<>();
    for (final Territory t : d.getMap().getTerritories()) {
      if (AirZoneAttachment.isAirZone(t)) {
        boxStrip.add(mapData.getBoundingRect(t));
        continue;
      }
      if (!t.getUnits().isEmpty()) {
        final int stacks = UnitSeparator.categorize(t.getUnits()).size();
        final List<Point> places = mapData.getPlacementPoints(t);
        for (int i = 0; i < Math.min(stacks, places.size()); i++) {
          final Point p = places.get(i);
          clear.add(new Rectangle(p.x, p.y, unitWidth, unitHeight + 6));
        }
      }
      // request 22: only names the map draws (it draws no sea zone names unless set to)
      if ((!t.isWater() || mapData.drawSeaZoneNames())
          && mapData.shouldDrawTerritoryName(t.getName())) {
        mapData
            .getNamePlacementPoint(t)
            .or(() -> Optional.ofNullable(mapData.getCenter(t)))
            .ifPresent(p -> clear.add(new Rectangle(p.x - 45, p.y - 14, 90, 20)));
      }
    }
    keepClear = clear;
    strip = boxStrip;
    auto.clear();
    docked.clear();
    boxes.clear();
  }

  /** Where a front's box goes and which of the offered shapes (layouts) it uses there. */
  record Placement(Point centre, int shape) {}

  /**
   * The zone's box: where the player dragged it (first shape), else the first offered shape that
   * fits in the front's own open sea nearest its centre, else docked (first shape).
   *
   * @param shapes the box's possible sizes, most preferred first
   */
  Placement place(
      final Territory zone,
      final Point anchor,
      final List<java.awt.Dimension> shapes,
      final MapData mapData,
      final GameData d) {
    final Point dragged = this.user.get(zone.getName());
    final Placement placement =
        dragged != null
            ? new Placement(dragged, 0)
            : auto.computeIfAbsent(zone, z -> findSpot(z, anchor, shapes, mapData, d));
    final java.awt.Dimension size = shapes.get(placement.shape());
    boxes.put(zone, box(placement.centre(), size.width, size.height));
    return placement;
  }

  private Placement findSpot(
      final Territory zone,
      final Point anchor,
      final List<java.awt.Dimension> shapes,
      final MapData mapData,
      final GameData d) {
    landMask(mapData, d);
    final List<Polygon> home = homeSeas(zone, mapData);
    for (int s = 0; s < shapes.size(); s++) {
      final Optional<Point> spot = search(zone, anchor, shapes.get(s), home, mapData);
      if (spot.isPresent()) {
        return new Placement(spot.get(), s);
      }
    }
    // nowhere clear in the front's own sea: docked at the edge of the view
    docked.add(zone);
    final @Nullable Rectangle own = mapData.getBoundingRect(zone);
    return new Placement(
        own == null ? anchor : new Point((int) own.getCenterX(), (int) own.getCenterY()), 0);
  }

  /**
   * Requests 20 and 22: the spot nearest the front's centre (searching outward) where the box lies
   * wholly in the front's own sea zones and covers no land, land border, photo card, plaque, unit
   * stack, drawn name, strip box or other box. The tags straddling its top edge count as part of
   * it.
   */
  private Optional<Point> search(
      final Territory zone,
      final Point anchor,
      final java.awt.Dimension size,
      final List<Polygon> home,
      final MapData mapData) {
    if (home.isEmpty()) {
      return Optional.empty();
    }
    final int width = size.width;
    final int height = size.height;
    final Rectangle area =
        new Rectangle(
            anchor.x - MAX_RADIUS - width,
            anchor.y - MAX_RADIUS - height,
            2 * (MAX_RADIUS + width),
            2 * (MAX_RADIUS + height));
    final List<Rectangle> near = keepClear.stream().filter(area::intersects).toList();
    final java.awt.Dimension map = mapData.getMapDimensions();
    for (int radius = 0; radius <= MAX_RADIUS; radius += STEP) {
      final int count = radius == 0 ? 1 : Math.max(8, (int) (2 * Math.PI * radius / STEP));
      for (int i = 0; i < count; i++) {
        final double angle = 2 * Math.PI * i / count;
        final Point c =
            new Point(
                anchor.x + (int) Math.round(radius * Math.cos(angle)),
                anchor.y + (int) Math.round(radius * Math.sin(angle)));
        final Rectangle r = box(c, width, height);
        final Rectangle padded =
            new Rectangle(r.x - 2, r.y - TAG_ROOM, r.width + 4, r.height + TAG_ROOM + 6);
        if (padded.x < 0
            || padded.y < 0
            || padded.x + padded.width > map.width
            || padded.y + padded.height > map.height
            || !inside(padded, home)
            || coversLand(padded)
            || near.stream().anyMatch(padded::intersects)
            || strip.stream().anyMatch(padded::intersects)
            || boxes.entrySet().stream()
                .anyMatch(o -> !o.getKey().equals(zone) && padded.intersects(o.getValue()))) {
          continue;
        }
        return Optional.of(c);
      }
    }
    return Optional.empty();
  }

  /** Whether the rectangle's centre, corners and edge midpoints all lie in the front's seas. */
  static boolean inside(final Rectangle r, final List<Polygon> home) {
    final int[] xs = {r.x, r.x + r.width / 2, r.x + r.width - 1};
    final int[] ys = {r.y, r.y + r.height / 2, r.y + r.height - 1};
    for (final int x : xs) {
      for (final int y : ys) {
        if (home.stream().noneMatch(p -> p.contains(x, y))) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * The shapes of the sea zones a front's box may sit on: only those the front itself covers
   * (request 22: never a neighbouring front's sea). Bombing zones cover land only, so they dock.
   */
  static List<Polygon> homeSeas(final Territory zone, final MapData mapData) {
    final List<Polygon> result = new ArrayList<>();
    for (final Territory t : AirZoneAttachment.get(zone).orElseThrow().getCoveredTerritories()) {
      if (t.isWater()) {
        result.addAll(mapData.getPolygons(t));
      }
    }
    return result;
  }

  // request 22: the map's pictures, to find the photo cards and the title plaque painted on them
  private @Nullable java.util.function.Function<String, Optional<java.awt.image.BufferedImage>>
      tiles;

  void setTiles(
      final java.util.function.Function<String, Optional<java.awt.image.BufferedImage>> tiles) {
    this.tiles = tiles;
  }

  // a block of DECO_BLOCK map pixels counts as a card or plaque if this share of it is not sea
  private static final int DECO_BLOCK = 16;
  private static final double DECO_SHARE = 0.35;

  /**
   * Marks the photo cards and the title plaque in the mask: blocks of the map picture where much of
   * the area is not sea-coloured. Land is marked already; thin border lines are too thin to count.
   */
  private void markDecorations(final java.awt.image.BufferedImage mask) {
    if (tiles == null) {
      return;
    }
    final int tile = 256;
    final int across = (maskWidth * MASK_SCALE + tile - 1) / tile;
    final int down = (maskHeight * MASK_SCALE + tile - 1) / tile;
    for (int tx = 0; tx < across; tx++) {
      for (int ty = 0; ty < down; ty++) {
        final Optional<java.awt.image.BufferedImage> base =
            tiles.apply("baseTiles/" + tx + "_" + ty + ".png");
        if (base.isEmpty()) {
          continue;
        }
        final java.awt.image.BufferedImage img =
            new java.awt.image.BufferedImage(tile, tile, java.awt.image.BufferedImage.TYPE_INT_RGB);
        final java.awt.Graphics2D g = img.createGraphics();
        g.drawImage(base.get(), 0, 0, null);
        tiles
            .apply("reliefTiles/" + tx + "_" + ty + ".png")
            .ifPresent(r -> g.drawImage(r, 0, 0, null));
        g.dispose();
        for (int bx = 0; bx < tile; bx += DECO_BLOCK) {
          for (int by = 0; by < tile; by += DECO_BLOCK) {
            int notSea = 0;
            int total = 0;
            for (int x = bx; x < bx + DECO_BLOCK; x += 2) {
              for (int y = by; y < by + DECO_BLOCK; y += 2) {
                final int rgb = img.getRGB(x, y);
                final int r = (rgb >> 16) & 0xFF;
                final int b = rgb & 0xFF;
                total++;
                if (b - r < 25) {
                  notSea++;
                }
              }
            }
            if (notSea > total * DECO_SHARE) {
              final int mx = (tx * tile + bx) / MASK_SCALE;
              final int my = (ty * tile + by) / MASK_SCALE;
              for (int x = mx; x < Math.min(maskWidth, mx + DECO_BLOCK / MASK_SCALE); x++) {
                for (int y = my; y < Math.min(maskHeight, my + DECO_BLOCK / MASK_SCALE); y++) {
                  mask.getRaster().setSample(x, y, 0, 255);
                }
              }
            }
          }
        }
      }
    }
  }

  // room above a box for the half of its "Dogfight" and status tags that sticks out
  private static final int TAG_ROOM = 10;
  // the land mask: 1 cell per MASK_SCALE map pixels, as a summed-area table
  private static final int MASK_SCALE = 4;
  private int[] landSum;
  private int maskWidth;
  private int maskHeight;

  /** Draws every land territory (with its border) once into a small mask. */
  // the mask, built once in the background when the game loads (it reads every map tile)
  private @Nullable java.util.concurrent.CompletableFuture<Void> maskBuild;

  /** Starts building the land, card and plaque mask, so the air view opens without a pause. */
  synchronized void prepare(final MapData mapData, final GameData d) {
    if (maskBuild == null) {
      maskBuild =
          java.util.concurrent.CompletableFuture.runAsync(() -> buildLandMask(mapData, d))
              .exceptionally(
                  e -> {
                    log.warn("Could not prepare the air box map", e);
                    return null;
                  });
    }
  }

  private void landMask(final MapData mapData, final GameData d) {
    prepare(mapData, d);
    maskBuild.join();
    if (landSum == null) {
      // the background build failed: build it here
      buildLandMask(mapData, d);
    }
  }

  private synchronized void buildLandMask(final MapData mapData, final GameData d) {
    if (landSum != null) {
      return;
    }
    final java.awt.Dimension map = mapData.getMapDimensions();
    maskWidth = map.width / MASK_SCALE + 1;
    maskHeight = map.height / MASK_SCALE + 1;
    final java.awt.image.BufferedImage img =
        new java.awt.image.BufferedImage(
            maskWidth, maskHeight, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
    final java.awt.Graphics2D g = img.createGraphics();
    g.scale(1.0 / MASK_SCALE, 1.0 / MASK_SCALE);
    g.setColor(java.awt.Color.WHITE);
    g.setStroke(new java.awt.BasicStroke(MASK_SCALE * 2f));
    for (final Territory t : d.getMap().getTerritories()) {
      if (t.isWater() || AirZoneAttachment.isAirZone(t)) {
        continue;
      }
      for (final Polygon p : mapData.getPolygons(t)) {
        g.fill(p);
        g.draw(p);
      }
    }
    g.dispose();
    markDecorations(img);
    final java.awt.image.Raster raster = img.getRaster();
    final int[] sum = new int[(maskWidth + 1) * (maskHeight + 1)];
    for (int y = 0; y < maskHeight; y++) {
      int row = 0;
      for (int x = 0; x < maskWidth; x++) {
        row += raster.getSample(x, y, 0) > 0 ? 1 : 0;
        sum[(y + 1) * (maskWidth + 1) + x + 1] = sum[y * (maskWidth + 1) + x + 1] + row;
      }
    }
    landSum = sum;
  }

  /** Whether any land (or a land border) lies inside the rectangle (map pixels). */
  boolean coversLand(final Rectangle r) {
    final int x0 = Math.max(0, Math.floorDiv(r.x, MASK_SCALE));
    final int y0 = Math.max(0, Math.floorDiv(r.y, MASK_SCALE));
    final int x1 = Math.min(maskWidth, Math.floorDiv(r.x + r.width, MASK_SCALE) + 1);
    final int y1 = Math.min(maskHeight, Math.floorDiv(r.y + r.height, MASK_SCALE) + 1);
    if (x0 >= x1 || y0 >= y1) {
      return false;
    }
    final int w = maskWidth + 1;
    return landSum[y1 * w + x1] - landSum[y0 * w + x1] - landSum[y1 * w + x0] + landSum[y0 * w + x0]
        > 0;
  }

  static Rectangle box(final Point centre, final int width, final int height) {
    return new Rectangle(centre.x - width / 2, centre.y - height / 2, width, height);
  }

  /** Whether the rectangle touches a unit stack or a drawn name (for tests). */
  boolean touchesStackOrName(final Rectangle r) {
    return keepClear.stream().anyMatch(r::intersects);
  }

  /** Whether the front's box docks at the edge of the view (no clear sea near it, not dragged). */
  boolean isDocked(final Territory zone) {
    return !user.containsKey(zone.getName()) && docked.contains(zone);
  }

  /** The player dragged the box here. */
  void moveTo(final Territory zone, final Point centre) {
    user.put(zone.getName(), new Point(centre));
  }

  /** Back to the automatic spot (double-click on the box's title). */
  void reset(final Territory zone) {
    user.remove(zone.getName());
    auto.remove(zone);
    docked.remove(zone);
    save(zone.getName(), null);
  }

  /** Remembers a dragged box's spot for later games. */
  void remember(final Territory zone) {
    save(zone.getName(), user.get(zone.getName()));
  }

  private static void save(final String key, final @Nullable Point p) {
    try {
      final Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
      if (p == null) {
        prefs.remove(key);
      } else {
        prefs.put(key, p.x + "," + p.y);
      }
      prefs.flush();
    } catch (final Exception e) {
      log.debug("Could not save the air box position", e);
    }
  }

  /** Whether any of the territory's shapes reaches into the rectangle. */
  static boolean covers(final Rectangle box, final Territory t, final MapData mapData) {
    for (final Polygon p : mapData.getPolygons(t)) {
      if (p.intersects(box)) {
        return true;
      }
    }
    return false;
  }
}
