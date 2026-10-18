package games.strategy.triplea.ui;

import static java.text.MessageFormat.format;

import games.strategy.engine.data.Change;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameDataEvent;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.events.GameDataChangeListener;
import games.strategy.engine.data.properties.GameProperties;
import games.strategy.engine.player.PlayerBridge;
import games.strategy.triplea.Properties;
import games.strategy.triplea.attachments.PlayerAttachment;
import games.strategy.triplea.delegate.FreeConscripts;
import games.strategy.triplea.delegate.GameStepPropertiesHelper;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.data.PlaceableUnits;
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate;
import games.strategy.triplea.formatter.MyFormatter;
import games.strategy.triplea.formatter.UnitNames;
import games.strategy.triplea.ui.panels.map.MapSelectionListener;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.triplea.java.collections.CollectionUtils;
import org.triplea.swing.SwingComponents;

class PlacePanel extends AbstractMovePanel implements GameDataChangeListener {
  public static final String LBL_UNITS_LEFT_TO_PLACE = "Units left to place:";
  private static final long serialVersionUID = -4411301492537704785L;
  private final JLabel leftToPlaceLabel = createIndentedLabel();
  // Flames and Steel: units left unplaced last turn ("Unplaced units live when not placed")
  private final JLabel carriedOverLabel = createIndentedLabel();
  // Flames and Steel request 10: free conscript placements (China)
  private final JLabel freeConscriptLabel = createIndentedLabel();
  private final FreeConscriptOverlay freeConscriptOverlay;
  private boolean freeConscriptStatusShown;
  private final SimpleUnitPanel unitsToPlacePanel;
  private transient PlaceData placeData;
  private final transient MapSelectionListener placeMapSelectionListener =
      new DefaultMapSelectionListener() {

        private PlaceableUnits getUnitsToPlace(final Territory territory) {
          try (GameData.Unlocker ignored = getData().acquireReadLock()) {
            // not our territory
            if (!territory.isWater() && !territory.isOwnedBy(getCurrentPlayer())) {
              if (GameStepPropertiesHelper.isBid(getData())) {
                final PlayerAttachment pa = PlayerAttachment.get(territory.getOwner());
                if ((pa == null || !pa.getGiveUnitControl().contains(getCurrentPlayer()))
                    && !territory.anyUnitsMatch(Matches.unitIsOwnedBy(getCurrentPlayer()))) {
                  return new PlaceableUnits();
                }
              } else {
                return new PlaceableUnits();
              }
            }
            // get the units that can be placed on this territory.
            Collection<Unit> units = getCurrentPlayer().getUnits();
            if (territory.isWater()) {
              GameProperties properties = getData().getProperties();
              if (!(Properties.getProduceFightersOnCarriers(properties)
                  || Properties.getProduceNewFightersOnOldCarriers(properties)
                  || Properties.getLhtrCarrierProductionRules(properties)
                  || GameStepPropertiesHelper.isBid(getData()))) {
                units = CollectionUtils.getMatches(units, Matches.unitIsSea());
              } else {
                final Predicate<Unit> unitIsSeaOrCanLandOnCarrier =
                    Matches.unitIsSea().or(Matches.unitCanLandOnCarrier());
                units = CollectionUtils.getMatches(units, unitIsSeaOrCanLandOnCarrier);
              }
            } else {
              units = CollectionUtils.getMatches(units, Matches.unitIsNotSea());
            }
            if (units.isEmpty()) {
              return new PlaceableUnits();
            }
            // TODO: This is a remote network fetch (bad).
            // Though, the local place delegate is not a replacment. The server has
            // the 'player' and 'bridge' attributes set, which is done server side
            // and not client side. Using the local client bridge causes a NPE when
            // a game-client (in a networked game) tries to place units.
            final PlaceableUnits production =
                ((IAbstractPlaceDelegate) getPlayerBridge().getRemoteDelegate())
                    .getPlaceableUnits(units, territory);
            if (production.isError()) {
              JOptionPane.showMessageDialog(
                  getTopLevelAncestor(),
                  production.getErrorMessage(),
                  "Cannot produce units",
                  JOptionPane.INFORMATION_MESSAGE);
            }
            return production;
          }
        }

        @Override
        public void mouseEntered(final @Nullable Territory territory) {
          // Flames and Steel: "Sian: 2 free conscripts" while pointing at a highlighted territory
          final Integer free = territory == null ? null : freeConscriptOverlay.spots.get(territory);
          if (isActive() && free != null) {
            setStatusInfoMessage(territory.getName() + ": " + freeConscriptText(free));
            freeConscriptStatusShown = true;
          } else if (freeConscriptStatusShown) {
            clearStatusMessage();
            freeConscriptStatusShown = false;
          }
        }

        @Override
        public void territorySelected(final Territory territory, final MouseDetails e) {
          if (!isActive() || (e.getButton() != MouseEvent.BUTTON1)) {
            return;
          }
          final PlaceableUnits placeableUnits = getUnitsToPlace(territory);
          final Collection<Unit> units = placeableUnits.getUnits();
          final int maxUnits = placeableUnits.getMaxUnits();
          if (units.isEmpty()) {
            return;
          }
          final UnitChooser chooser =
              new UnitChooser(units, Map.of(), false, getMap().getUiContext());
          if (maxUnits >= 0) {
            chooser.setMaxAndShowMaxButton(maxUnits);
          }
          final JScrollPane scroll = getScrollPaneFromChooser(chooser);
          final int option =
              JOptionPane.showOptionDialog(
                  getTopLevelAncestor(),
                  scroll,
                  format("Place units in {0}", territory.getName()),
                  JOptionPane.OK_CANCEL_OPTION,
                  JOptionPane.PLAIN_MESSAGE,
                  null,
                  null,
                  null);
          if (option == JOptionPane.OK_OPTION) {
            final Collection<Unit> selectedUnits = chooser.getSelected();
            placeData = new PlaceData(selectedUnits, territory);
            updateUnits();
            if (selectedUnits.containsAll(units)) {
              leftToPlaceLabel.setText("");
            }
            release();
          }
        }

        @Nonnull
        private JScrollPane getScrollPaneFromChooser(final UnitChooser chooser) {
          final Dimension screenResolution = Toolkit.getDefaultToolkit().getScreenSize();
          final int availHeight = screenResolution.height - 120;
          final int availWidth = screenResolution.width - 40;
          final JScrollPane scroll = new JScrollPane(chooser);
          scroll.setBorder(BorderFactory.createEmptyBorder());
          scroll.setPreferredSize(
              new Dimension(
                  (scroll.getPreferredSize().width > availWidth
                      ? availWidth
                      : getPreferredWith(scroll, availHeight)),
                  (scroll.getPreferredSize().height > availHeight
                      ? availHeight
                      : getPreferredHeight(scroll, availWidth))));
          return scroll;
        }

        private int getPreferredHeight(final JScrollPane scroll, final int availWidth) {
          return scroll.getPreferredSize().height
              + (scroll.getPreferredSize().width > availWidth ? 26 : 0);
        }

        private int getPreferredWith(final JScrollPane scroll, final int availHeight) {
          return scroll.getPreferredSize().width
              + (scroll.getPreferredSize().height > availHeight ? 20 : 0);
        }
      };
  private GamePlayer lastPlayer;
  private boolean postProductionStep;

  PlacePanel(final TripleAFrame frame) {
    super(frame);
    undoableMovesPanel = new UndoablePlacementsPanel(this);
    unitsToPlacePanel = new SimpleUnitPanel(map.getUiContext());
    getData().addGameDataEventListener(GameDataEvent.GAME_STEP_CHANGED, this::updateStep);
    leftToPlaceLabel.setText(LBL_UNITS_LEFT_TO_PLACE);
    freeConscriptOverlay = new FreeConscriptOverlay(map.getUiContext().getMapData());
    freeConscriptLabel.setVisible(false);
  }

  private void updateStep() {
    final Collection<Unit> unitsToPlace;
    final boolean showUnitsToPlace;
    final GameData data = getData();
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      final GameStep step = data.getSequence().getStep();
      if (step == null) {
        return;
      }
      // Note: This doesn't use getCurrentPlayer() as that may not be updated yet.
      final GamePlayer player = step.getPlayerId();
      if (player == null) {
        return;
      }
      final boolean isNewPlayerTurn = !player.equals(lastPlayer);
      if (isNewPlayerTurn) {
        postProductionStep = false;
      }
      final Collection<Unit> playerUnits = player.getUnits();
      // If we're past the production step (even if player didn't produce anything) or
      // there are units that are available to place, show the panel (set unitsToPlace).
      showUnitsToPlace = (postProductionStep || !playerUnits.isEmpty());
      unitsToPlace = showUnitsToPlace ? playerUnits : List.of();
      if (GameStep.isPurchaseOrBidStepName(step.getName())) {
        postProductionStep = true;
      }
      lastPlayer = player;
      final String carriedOver = postProductionStep ? carriedOverText(player) : "";
      SwingUtilities.invokeLater(
          () -> {
            carriedOverLabel.setText(carriedOver);
            carriedOverLabel.setVisible(!carriedOver.isEmpty());
          });
      // During the place step, listen for changes to update the panel.
      if (GameStep.isPlaceStepName(step.getName())) {
        data.addDataChangeListener(this);
      } else {
        data.removeDataChangeListener(this);
      }
    }

    if (showUnitsToPlace) {
      updateUnitsInUnitsToPlacePanel(unitsToPlace);
    } else {
      SwingUtilities.invokeLater(unitsToPlacePanel::removeAll);
    }
  }

  /**
   * "Carried over from last turn: 10 (6 infantry, 4 conscripts)", or "" when nothing was left
   * unplaced last turn. Read under the data lock.
   */
  private static String carriedOverText(final GamePlayer player) {
    final PlayerAttachment attachment = PlayerAttachment.get(player);
    if (attachment == null || attachment.getCarriedOverUnits().totalValues() <= 0) {
      return "";
    }
    final var carried = attachment.getCarriedOverUnits();
    final List<String> parts = new ArrayList<>();
    for (final String type : carried.keySet().stream().sorted().toList()) {
      final int count = carried.getInt(type);
      parts.add(count + " " + MyFormatter.pluralize(UnitNames.of(type), count));
    }
    return "Carried over from last turn: "
        + carried.totalValues()
        + " ("
        + String.join(", ", parts)
        + ")";
  }

  private void updateUnitsInUnitsToPlacePanel(final Collection<Unit> newUnitsToPlace) {
    // Small hack: copy the unit list before passing it to a new thread.
    // This is to prevent ConcurrentModification. If the 'unitsToPlace' list is modified
    // later in this thread, before "SwingUtilities.invokeLater" can execute and complete,
    // then we will get a ConcurrentModification exception.
    // Ideally we would not modify the 'unitsToPlace' collection again except when
    // the swing thread signals that the user has taken action.
    // Short of that, we create a copy here.
    final Collection<Unit> unitsToPlaceCopy =
        Collections.unmodifiableCollection(new ArrayList<>(newUnitsToPlace));
    SwingUtilities.invokeLater(
        () -> {
          unitsToPlacePanel.setUnits(unitsToPlaceCopy);
          SwingComponents.redraw(unitsToPlacePanel);
        });
  }

  @Override
  public void gameDataChanged(final Change change) {
    final GameData data = getData();
    try (GameData.Unlocker ignored = data.acquireReadLock()) {
      final GamePlayer player = data.getSequence().getStep().getPlayerId();
      if (player != null) {
        final Collection<Unit> unitsToPlace = player.getUnits();
        updateUnitsInUnitsToPlacePanel(unitsToPlace);
        SwingUtilities.invokeLater(this::refreshFreeConscripts);
      }
    }
  }

  @Override
  protected Component getUnitScrollerPanel() {
    return new JPanel();
  }

  @Override
  public void display(final GamePlayer gamePlayer) {
    super.display(gamePlayer, " place");
  }

  private void refreshActionLabelText(final boolean bid) {
    SwingUtilities.invokeLater(
        () ->
            actionLabel.setText(
                bid
                    ? format("{0} place for bid", getCurrentPlayer().getName())
                    : format("{0} place", getCurrentPlayer().getName())));
  }

  PlaceData waitForPlace(final boolean bid, final PlayerBridge playerBridge) {
    setUp(playerBridge);
    // workaround: meant to be in setUpSpecific, but it requires a variable
    refreshActionLabelText(bid);
    waitForRelease();
    cleanUp();
    return placeData;
  }

  @Override
  public String toString() {
    return "PlacePanel";
  }

  @Override
  protected final void cancelMoveAction() {
    getMap().showMouseCursor();
    getMap().setMouseShadowUnits(null);
  }

  @Override
  protected final void undoMoveSpecific() {
    leftToPlaceLabel.setText(LBL_UNITS_LEFT_TO_PLACE);
    updateUnits();
    SwingUtilities.invokeLater(this::refreshFreeConscripts);
  }

  @Override
  protected final void cleanUpSpecific() {
    getMap().removeMapSelectionListener(placeMapSelectionListener);
    getMap().removeOverlay(freeConscriptOverlay);
    freeConscriptOverlay.spots = Map.of();
    SwingUtilities.invokeLater(() -> freeConscriptLabel.setVisible(false));
  }

  @Override
  protected final void setUpSpecific() {
    getMap().addMapSelectionListener(placeMapSelectionListener);
    getMap().addOverlay(freeConscriptOverlay);
    SwingUtilities.invokeLater(this::refreshFreeConscripts);
  }

  // ---- Flames and Steel request 10: free conscripts (China)

  /** Territories where the player may still place free conscripts, and how many. */
  private Map<Territory, Integer> freeConscriptSpots() {
    final GamePlayer player = getCurrentPlayer();
    if (player == null || FreeConscripts.allowance(player) <= 0) {
      return Map.of();
    }
    final org.triplea.java.collections.IntegerMap<Territory> placed =
        new org.triplea.java.collections.IntegerMap<>();
    for (final Object move : getUndoableMoves()) {
      if (move instanceof games.strategy.triplea.delegate.UndoablePlacement p) {
        placed.add(p.getPlaceTerritory(), FreeConscripts.count(p.getUnits()));
      }
    }
    final Map<Territory, Integer> spots = new java.util.LinkedHashMap<>();
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      for (final Territory t : getData().getMap().getTerritories()) {
        final int left = FreeConscripts.left(t, player, placed.getInt(t));
        if (left > 0) {
          spots.put(t, left);
        }
      }
    }
    return spots;
  }

  private static String freeConscriptText(final int n) {
    return n + (n == 1 ? " free conscript" : " free conscripts");
  }

  /** Highlights the territories with free conscripts left and updates the "left" line. */
  private void refreshFreeConscripts() {
    final Map<Territory, Integer> spots = isActive() ? freeConscriptSpots() : Map.of();
    final GamePlayer player = getCurrentPlayer();
    final int allowance = player == null ? 0 : FreeConscripts.allowance(player);
    freeConscriptOverlay.spots = spots;
    SwingUtilities.invokeLater(
        () -> {
          final int total = spots.values().stream().mapToInt(Integer::intValue).sum();
          freeConscriptLabel.setText(
              allowance <= 0
                  ? ""
                  : "Free conscript placements left: "
                      + total
                      + " ("
                      + allowance
                      + " per territory)");
          freeConscriptLabel.setVisible(allowance > 0);
          getMap().repaint();
        });
  }

  /** Light green on the territories where free conscripts can still go. */
  private static final class FreeConscriptOverlay
      implements games.strategy.triplea.ui.panels.map.MapOverlay {
    private volatile Map<Territory, Integer> spots = Map.of();
    private final games.strategy.triplea.ui.mapdata.MapData mapData;

    FreeConscriptOverlay(final games.strategy.triplea.ui.mapdata.MapData mapData) {
      this.mapData = mapData;
    }

    @Override
    public void paint(final java.awt.Graphics2D g, final GameData data) {
      for (final Territory t : spots.keySet()) {
        for (final java.awt.Polygon p : mapData.getPolygons(t)) {
          g.setColor(new java.awt.Color(120, 230, 120, 70));
          g.fill(p);
          g.setColor(new java.awt.Color(40, 160, 40, 200));
          g.setStroke(new java.awt.BasicStroke(2.5f));
          g.draw(p);
        }
      }
    }
  }

  /** Where the units left over could still go, for the end-of-placement question. */
  private String whereUnitsCouldGo() {
    final GamePlayer player = getCurrentPlayer();
    final List<String> lines = new ArrayList<>();
    final Collection<Unit> held = player.getUnits();
    final Map<Territory, Integer> spots = freeConscriptSpots();
    if (!spots.isEmpty() && held.stream().anyMatch(FreeConscripts::isConscript)) {
      final List<String> names =
          spots.entrySet().stream()
              .limit(8)
              .map(e -> e.getKey().getName() + " (" + e.getValue() + ")")
              .toList();
      lines.add(
          "Free conscripts can still go to "
              + spots.size()
              + " territories: "
              + String.join(", ", names)
              + (spots.size() > names.size() ? ", ..." : ""));
    }
    final List<String> factories = new ArrayList<>();
    final List<Territory> candidates;
    try (GameData.Unlocker ignored = getData().acquireReadLock()) {
      candidates =
          getData().getMap().getTerritories().stream()
              .filter(t -> !t.isWater() && t.isOwnedBy(player))
              .filter(t -> t.anyUnitsMatch(Matches.unitCanProduceUnits()))
              .toList();
    }
    // conscripts are already covered by the free spots listed above
    final Collection<Unit> others =
        CollectionUtils.getMatches(
            held,
            spots.isEmpty()
                ? Matches.unitIsNotSea()
                : Matches.unitIsNotSea().and(u -> !FreeConscripts.isConscript(u)));
    for (final Territory t : others.isEmpty() ? List.<Territory>of() : candidates) {
      final PlaceableUnits room =
          ((IAbstractPlaceDelegate) getPlayerBridge().getRemoteDelegate())
              .getPlaceableUnits(others, t);
      if (!room.isError() && room.getMaxUnits() != 0 && !room.getUnits().isEmpty()) {
        factories.add(
            t.getName() + " (" + (room.getMaxUnits() < 0 ? "any" : room.getMaxUnits()) + ")");
      }
    }
    if (!factories.isEmpty()) {
      lines.add("Factories with room: " + String.join(", ", factories));
    }
    if (Properties.getUnplacedUnitsLive(getData().getProperties())) {
      lines.add("Units you don't place now carry over to your next turn.");
    }
    return String.join("\n", lines);
  }

  @Override
  protected boolean doneMoveAction() {
    if (!getCurrentPlayer().getUnitCollection().isEmpty()) {
      final String where = whereUnitsCouldGo();
      final int option =
          JOptionPane.showConfirmDialog(
              getTopLevelAncestor(),
              "You have not placed all your units yet."
                  + (where.isEmpty() ? "" : "\n" + where)
                  + "\nAre you sure you want to end your turn?",
              "Confirm End Turn",
              JOptionPane.YES_NO_OPTION,
              JOptionPane.PLAIN_MESSAGE);
      if (option != JOptionPane.YES_OPTION) {
        return false;
      }
    }
    placeData = null;
    return true;
  }

  @Override
  protected boolean setCancelButton() {
    return false;
  }

  @Override
  protected final List<Component> getAdditionalButtons() {
    updateUnits();
    return Arrays.asList(
        SwingComponents.leftBox(leftToPlaceLabel),
        SwingComponents.leftBox(carriedOverLabel),
        SwingComponents.leftBox(freeConscriptLabel),
        add(unitsToPlacePanel));
  }

  private void updateUnits() {
    updateUnitsInUnitsToPlacePanel(getCurrentPlayer().getUnits());
  }
}
