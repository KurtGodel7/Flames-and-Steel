package games.strategy.triplea.ui.panels.map;

import games.strategy.engine.data.GameData;
import java.awt.Graphics2D;

/**
 * Flames and Steel: extra drawing on top of the map (air zone highlights, allocation arrows). The
 * graphics are in unscaled map coordinates: (0, 0) is the map's top left corner.
 */
public interface MapOverlay {
  void paint(Graphics2D g, GameData data);
}
