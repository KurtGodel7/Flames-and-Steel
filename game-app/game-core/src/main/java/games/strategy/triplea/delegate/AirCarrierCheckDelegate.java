package games.strategy.triplea.delegate;

import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.message.IRemote;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment.ZoneType;
import games.strategy.triplea.formatter.MyFormatter;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel end-of-round carrier check. A step named "airCarrierSnapshot" (start of round)
 * records each carrier's air box; a step named "airCarrierCheck" (end of round) compares. In each
 * carrier-only (ocean) front, a nation's aircraft beyond its carriers' capacity there follow
 * carriers that sailed to another box (up to those carriers' capacity, jets only with fleet
 * carriers); the rest are destroyed, cheapest first. A sunk carrier takes nothing with it.
 */
public class AirCarrierCheckDelegate extends BaseTripleADelegate {
  private Map<Unit, Territory> carrierZoneAtStart = new HashMap<>();

  @Override
  public void start() {
    super.start();
    final String step = getData().getSequence().getStep().getName();
    if (step.startsWith("airCarrierSnapshot")) {
      carrierZoneAtStart = new HashMap<>();
      for (final Territory zone : AirZoneAttachment.getAirZones(getData().getMap())) {
        for (final Unit carrier : carriersIn(zone, null)) {
          carrierZoneAtStart.put(carrier, zone);
        }
      }
    } else if (step.startsWith("airCarrierCheck")) {
      for (final GamePlayer player : getData().getPlayerList().getPlayers()) {
        for (final Territory zone : AirZoneAttachment.getAirZones(getData().getMap())) {
          checkZone(player, zone);
        }
      }
      carrierZoneAtStart = new HashMap<>();
    }
  }

  @Override
  public boolean delegateCurrentlyRequiresUserInput() {
    return false;
  }

  private void checkZone(final GamePlayer player, final Territory zone) {
    final ZoneType type = AirZoneAttachment.get(zone).orElseThrow().getZoneType();
    if (type != ZoneType.OCEAN) {
      return;
    }
    final List<Unit> dependent = carrierDependentAircraft(player, zone);
    if (dependent.isEmpty()) {
      return;
    }
    final List<Unit> excess = fit(dependent, carriersIn(zone, player));
    if (excess.isEmpty()) {
      return;
    }
    // carriers that sailed from this zone to another air zone take their share along
    final Map<Territory, List<Unit>> movedCarriers = new LinkedHashMap<>();
    for (final Map.Entry<Unit, Territory> entry : carrierZoneAtStart.entrySet()) {
      final Unit carrier = entry.getKey();
      if (entry.getValue().equals(zone) && carrier.isOwnedBy(player)) {
        currentZone(carrier)
            .filter(now -> !now.equals(zone))
            .ifPresent(
                now -> movedCarriers.computeIfAbsent(now, z -> new ArrayList<>()).add(carrier));
      }
    }
    List<Unit> left = excess;
    final CompositeChange change = new CompositeChange();
    final List<String> lines = new ArrayList<>();
    for (final Map.Entry<Territory, List<Unit>> entry : movedCarriers.entrySet()) {
      final List<Unit> stillLeft = fit(left, entry.getValue());
      final List<Unit> transferred = new ArrayList<>(left);
      transferred.removeAll(stillLeft);
      if (!transferred.isEmpty()) {
        change.add(ChangeFactory.moveUnits(zone, entry.getKey(), transferred));
        lines.add(
            MyFormatter.unitsToText(transferred)
                + " follow their carriers from "
                + zone.getName()
                + " to "
                + entry.getKey().getName());
      }
      left = stillLeft;
    }
    if (!left.isEmpty()) {
      change.add(ChangeFactory.removeUnits(zone, left));
      lines.add(
          MyFormatter.unitsToText(left)
              + " in "
              + zone.getName()
              + " have no carrier space and are lost");
    }
    bridge.getHistoryWriter().startEvent(player.getName() + " carrier check in " + zone.getName());
    lines.forEach(line -> bridge.getHistoryWriter().addChildToEvent(line));
    bridge.addChange(change);
  }

  /**
   * Places the aircraft on the carriers' slots, most expensive first; returns the aircraft that do
   * not fit. Jets need fleet-carrier slots; other aircraft use ordinary slots first.
   */
  private List<Unit> fit(final List<Unit> aircraft, final List<Unit> carriers) {
    int fleetSlots = 0;
    int otherSlots = 0;
    for (final Unit carrier : carriers) {
      if (AirUnitAttachment.isFleetCarrier(carrier)) {
        fleetSlots += carrier.getUnitAttachment().getCarrierCapacity();
      } else {
        otherSlots += carrier.getUnitAttachment().getCarrierCapacity();
      }
    }
    final IntegerMap<UnitType> costs =
        aircraft.isEmpty() ? new IntegerMap<>() : bridge.getCostsForTuv(aircraft.get(0).getOwner());
    final List<Unit> sorted = new ArrayList<>(aircraft);
    sorted.sort(Comparator.comparingInt((Unit u) -> costs.getInt(u.getType())).reversed());
    final List<Unit> notFitting = new ArrayList<>();
    for (final Unit unit : sorted) {
      final int cost = Math.max(1, unit.getUnitAttachment().getCarrierCost());
      if (AirUnitAttachment.isFleetCarrierOnly(unit)) {
        if (fleetSlots >= cost) {
          fleetSlots -= cost;
        } else {
          notFitting.add(unit);
        }
      } else if (otherSlots >= cost) {
        otherSlots -= cost;
      } else if (fleetSlots >= cost) {
        fleetSlots -= cost;
      } else {
        notFitting.add(unit);
      }
    }
    return notFitting;
  }

  /** The nation's aircraft in a carrier-only front: all of them need carriers. */
  private static List<Unit> carrierDependentAircraft(
      final GamePlayer player, final Territory zone) {
    return zone.getUnitCollection()
        .getMatches(u -> u.isOwnedBy(player) && Matches.unitIsAir().test(u));
  }

  /** Carriers (of the player, or of anyone if null) in the zone's sea zones. */
  private static List<Unit> carriersIn(final Territory zone, final GamePlayer player) {
    final List<Unit> carriers = new ArrayList<>();
    for (final Territory seaZone :
        AirZoneAttachment.get(zone).orElseThrow().getCoveredTerritories()) {
      carriers.addAll(
          seaZone
              .getUnitCollection()
              .getMatches(
                  u ->
                      u.getUnitAttachment().getCarrierCapacity() > 0
                          && (player == null || u.isOwnedBy(player))));
    }
    return carriers;
  }

  /** The air zone covering the carrier's sea zone now, if it is still afloat. */
  private Optional<Territory> currentZone(final Unit carrier) {
    for (final Territory zone : AirZoneAttachment.getAirZones(getData().getMap())) {
      for (final Territory seaZone :
          AirZoneAttachment.get(zone).orElseThrow().getCoveredTerritories()) {
        if (seaZone.getUnitCollection().contains(carrier)) {
          return Optional.of(zone);
        }
      }
    }
    return Optional.empty();
  }

  @Override
  public Serializable saveState() {
    final CarrierCheckState state = new CarrierCheckState();
    state.superState = super.saveState();
    state.carrierZoneAtStart = new HashMap<>(carrierZoneAtStart);
    return state;
  }

  @Override
  public void loadState(final Serializable state) {
    final CarrierCheckState s = (CarrierCheckState) state;
    super.loadState(s.superState);
    carrierZoneAtStart = new HashMap<>(s.carrierZoneAtStart);
  }

  @Override
  public Class<? extends IRemote> getRemoteType() {
    return null;
  }

  private static final class CarrierCheckState implements Serializable {
    @Serial private static final long serialVersionUID = 2219503947118861532L;

    private Serializable superState;
    private Map<Unit, Territory> carrierZoneAtStart;
  }
}
