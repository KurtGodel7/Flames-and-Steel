package games.strategy.triplea.ui.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.events.GameDataChangeListener;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment.ZoneType;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.air.AirAllocation;
import games.strategy.triplea.delegate.air.AirAllocation.CarrierLoad;
import games.strategy.triplea.ui.DefaultMapSelectionListener;
import games.strategy.triplea.ui.UiContext;
import games.strategy.triplea.ui.mapdata.MapData;
import games.strategy.triplea.ui.panels.map.MapOverlay;
import games.strategy.triplea.ui.panels.map.MapPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Flames and Steel: air zone boxes on the map. Hovering a box tints every territory and sea zone it
 * covers; hovering a territory outlines the box it belongs to. Open-ocean and coastal boxes show
 * each nation's carrier load, e.g. "US 6/8 (jets 2/4)".
 */
public final class AirZoneOverlay implements MapOverlay {
  static final Color COVER_FILL = new Color(255, 230, 80, 70);
  static final Color COVER_LINE = new Color(255, 214, 0);
  private static final Color LABEL_BACK = new Color(0, 0, 0, 170);
  private static final Color DOGFIGHT = new Color(255, 70, 50);

  private final UiContext uiContext;
  private final @Nullable MapPanel mapPanel;
  private @Nullable Territory hovered;
  private @Nullable Territory selectedZone;
  private Map<Territory, Territory> zoneOfTerritory = Map.of();
  private @Nullable GameData listening;
  private volatile boolean labelsDirty = true;
  private Map<Territory, List<Label>> labels = Map.of();
  private List<Territory> contested = List.of();
  private final GameDataChangeListener changeListener = change -> labelsDirty = true;

  private record Label(String text, Color color, boolean over) {}

  /** Without a map panel: for drawing tests only (no hover). */
  AirZoneOverlay(final UiContext uiContext, final GameData data) {
    this.uiContext = uiContext;
    this.mapPanel = null;
    setGameData(data);
  }

  public AirZoneOverlay(final UiContext uiContext, final MapPanel mapPanel, final GameData data) {
    this.uiContext = uiContext;
    this.mapPanel = mapPanel;
    setGameData(data);
    mapPanel.addMapSelectionListener(
        new DefaultMapSelectionListener() {
          @Override
          public void mouseEntered(final @Nullable Territory territory) {
            if (!Objects.equals(hovered, territory)) {
              hovered = territory;
              mapPanel.repaint();
            }
          }
        });
  }

  public void setGameData(final GameData data) {
    if (listening != null) {
      listening.removeDataChangeListener(changeListener);
    }
    listening = data;
    data.addDataChangeListener(changeListener);
    labelsDirty = true;
    final Map<Territory, Territory> zones = new HashMap<>();
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      for (final Territory zone : AirZoneAttachment.getAirZones(data.getMap())) {
        for (final Territory t :
            AirZoneAttachment.get(zone).orElseThrow().getCoveredTerritories()) {
          zones.put(t, zone);
        }
      }
    }
    zoneOfTerritory = zones;
  }

  /** The zone whose aircraft are selected (tinted like a hovered zone), or null. */
  public void setSelectedZone(final @Nullable Territory zone) {
    selectedZone = zone;
    if (mapPanel != null) {
      mapPanel.repaint();
    }
  }

  /** For drawing tests: pretend the mouse is over this territory. */
  void setHovered(final @Nullable Territory territory) {
    hovered = territory;
  }

  /** Whether the map has air zones at all. */
  public static boolean isUsed(final GameData data) {
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      return !AirZoneAttachment.getAirZones(data.getMap()).isEmpty();
    }
  }

  @Override
  public void paint(final Graphics2D g, final GameData data) {
    final MapData mapData = uiContext.getMapData();
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      final Territory hover = hovered;
      if (hover != null && AirZoneAttachment.isAirZone(hover)) {
        paintCover(g, mapData, hover);
      }
      if (selectedZone != null && !selectedZone.equals(hover)) {
        paintCover(g, mapData, selectedZone);
      }
      if (labelsDirty) {
        labelsDirty = false;
        final Map<Territory, List<Label>> computed = new HashMap<>();
        for (final Territory zone : AirZoneAttachment.getAirZones(data.getMap())) {
          final ZoneType type = AirZoneAttachment.get(zone).orElseThrow().getZoneType();
          if (type == ZoneType.OCEAN) {
            computed.put(zone, carrierLabels(mapData, data, zone));
          }
        }
        labels = computed;
        contested =
            AirZoneAttachment.getAirZones(data.getMap()).stream()
                .filter(AirZoneOverlay::isContested)
                .toList();
      }
    }
    labels.forEach((zone, zoneLabels) -> paintLabels(g, mapData, zone, zoneLabels));
    for (final Territory zone : contested) {
      outline(g, mapData, zone, DOGFIGHT, 4f);
      final Rectangle box = mapData.getBoundingRect(zone);
      AirDraw.tag(g, "Air battle", box.x + box.width - 55, box.y + 30, DOGFIGHT);
    }
  }

  /** Enemy aircraft share the zone: a dogfight is due (not in the reserve). */
  private static boolean isContested(final Territory zone) {
    if (AirZoneAttachment.get(zone).orElseThrow().getZoneType() == ZoneType.RESERVE) {
      return false;
    }
    final List<GamePlayer> owners =
        zone.getUnitCollection().getMatches(Matches.unitIsAir()).stream()
            .map(Unit::getOwner)
            .distinct()
            .toList();
    for (final GamePlayer a : owners) {
      for (final GamePlayer b : owners) {
        if (a.getData().getRelationshipTracker().isAtWar(a, b)) {
          return true;
        }
      }
    }
    return false;
  }

  static void paintCover(final Graphics2D g, final MapData mapData, final Territory zone) {
    for (final Territory t : AirZoneAttachment.get(zone).orElseThrow().getCoveredTerritories()) {
      for (final Polygon p : mapData.getPolygons(t)) {
        g.setColor(COVER_FILL);
        g.fillPolygon(p);
        g.setColor(COVER_LINE);
        g.setStroke(new BasicStroke(2.5f));
        g.drawPolygon(p);
      }
    }
    outline(g, mapData, zone, COVER_LINE, 4f);
  }

  static void outline(
      final Graphics2D g,
      final MapData mapData,
      final Territory territory,
      final Color color,
      final float width) {
    g.setColor(color);
    g.setStroke(new BasicStroke(width));
    for (final Polygon p : mapData.getPolygons(territory)) {
      g.drawPolygon(p);
    }
  }

  private static List<Label> carrierLabels(
      final MapData mapData, final GameData data, final Territory zone) {
    final List<Label> lines = new ArrayList<>();
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      final List<UnitType> aircraft = new ArrayList<>();
      for (final Unit u :
          zone.getUnitCollection()
              .getMatches(u -> u.isOwnedBy(player) && Matches.unitIsAir().test(u))) {
        aircraft.add(u.getType());
      }
      final CarrierLoad load = AirAllocation.carrierLoad(player, zone, aircraft);
      if (load.cost() == 0 && load.capacity() == 0) {
        continue;
      }
      final StringBuilder line =
          new StringBuilder(shortName(player))
              .append(' ')
              .append(load.cost())
              .append('/')
              .append(load.capacity());
      if (load.jetCost() > 0 || load.fleetCapacity() > 0) {
        line.append(" (jets ").append(load.jetCost()).append('/').append(load.fleetCapacity());
        line.append(')');
      }
      lines.add(
          new Label(
              line.toString(),
              mapData.getPlayerColor(player.getName()),
              load.cost() > load.capacity() || load.jetCost() > load.fleetCapacity()));
    }
    return lines;
  }

  private static void paintLabels(
      final Graphics2D g, final MapData mapData, final Territory zone, final List<Label> lines) {
    if (lines.isEmpty()) {
      return;
    }
    final Rectangle box = mapData.getBoundingRect(zone);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
    final FontMetrics fm = g.getFontMetrics();
    final int lineHeight = fm.getHeight() + 2;
    int y = box.y + box.height - 4 - lineHeight * lines.size();
    for (final Label label : lines) {
      final int width = fm.stringWidth(label.text()) + 22;
      g.setColor(LABEL_BACK);
      g.fillRoundRect(box.x + 4, y, width, lineHeight, 6, 6);
      g.setColor(label.color());
      g.fillRect(box.x + 8, y + 4, 9, lineHeight - 8);
      g.setColor(label.over() ? new Color(255, 120, 90) : Color.WHITE);
      g.drawString(label.text(), box.x + 20, y + fm.getAscent() + 1);
      y += lineHeight;
    }
  }

  /** The carrier lines of a zone, e.g. "US 6/8 (jets 2/4)". Call with the read lock held. */
  static List<String> carrierLines(final GameData data, final Territory zone) {
    final List<String> lines = new ArrayList<>();
    for (final GamePlayer player : data.getPlayerList().getPlayers()) {
      final List<UnitType> aircraft = new ArrayList<>();
      for (final Unit u :
          zone.getUnitCollection()
              .getMatches(u -> u.isOwnedBy(player) && Matches.unitIsAir().test(u))) {
        aircraft.add(u.getType());
      }
      final CarrierLoad load = AirAllocation.carrierLoad(player, zone, aircraft);
      if (load.cost() == 0 && load.capacity() == 0) {
        continue;
      }
      lines.add(
          shortName(player)
              + " "
              + load.cost()
              + "/"
              + load.capacity()
              + (load.jetCost() > 0 || load.fleetCapacity() > 0
                  ? " (jets " + load.jetCost() + "/" + load.fleetCapacity() + ")"
                  : "")
              + (load.cost() > load.capacity() || load.jetCost() > load.fleetCapacity()
                  ? " over!"
                  : ""));
    }
    return lines;
  }

  /** The nation spelled out: "Italy", "United States"; other nations by their player name. */
  static String nationName(final GamePlayer player) {
    return switch (player.getName()) {
      case "Americans" -> "United States";
      case "British" -> "United Kingdom";
      case "Russians" -> "Soviet Union";
      case "Germans" -> "Germany";
      case "Italians" -> "Italy";
      case "Japanese" -> "Japan";
      case "Chinese" -> "China";
      default -> player.getName();
    };
  }

  /** "US", "UK", "USSR" and so on; other nations by their first three letters. */
  static String shortName(final GamePlayer player) {
    return switch (player.getName()) {
      case "Americans" -> "US";
      case "British" -> "UK";
      case "Russians" -> "USSR";
      case "Germans" -> "GER";
      case "Italians" -> "ITA";
      case "Japanese" -> "JPN";
      case "Chinese" -> "CHN";
      default ->
          player.getName().substring(0, Math.min(3, player.getName().length())).toUpperCase();
    };
  }
}
