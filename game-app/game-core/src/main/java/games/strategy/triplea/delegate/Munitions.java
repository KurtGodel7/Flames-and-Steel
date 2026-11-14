package games.strategy.triplea.delegate;

import games.strategy.engine.data.Change;
import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.history.IDelegateHistoryWriter;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.MunitionsAttachment;
import games.strategy.triplea.attachments.PlayerAttachment;
import games.strategy.triplea.formatter.MyFormatter;
import games.strategy.triplea.formatter.PlayerNames;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;

/**
 * Flames and Steel lend-lease munitions: conversion into PUs, and capture.
 *
 * <p>Request 32: the nations listed in {@value #ABSORB_PLAYERS} (the two American nations) absorb
 * each other's munitions on their land at the end of their own turn, at most {@value #ABSORB_BASE}
 * plus one per tech in {@value #ABSORB_TECHS} they have, newest arrivals first; the rest wait for a
 * later turn.
 */
@UtilityClass
public class Munitions {
  public static final String ABSORB_PLAYERS = "FS Munitions Absorb Players";
  public static final String ABSORB_BASE = "FS Munitions Absorb Base";
  public static final String ABSORB_TECHS = "FS Munitions Absorb Techs";

  private static List<String> list(final GameState data, final String property) {
    final String value = data.getProperties().get(property, "");
    return value.isBlank() ? List.of() : List.of(value.split(":"));
  }

  /** How many munitions the nation may absorb this turn: the base plus one per listed tech. */
  public static int absorbLimit(final GamePlayer player) {
    final GameState data = player.getData();
    final List<String> techs = list(data, ABSORB_TECHS);
    final long owned =
        TechTracker.getCurrentTechAdvances(player, data.getTechnologyFrontier()).stream()
            .filter(t -> techs.contains(t.getName()))
            .count();
    return data.getProperties().get(ABSORB_BASE, 2) + (int) owned;
  }

  /**
   * Request 32: marks munitions that moved with when they arrived (round * 1000 + step index), so
   * the newest are absorbed first. An empty change if none moved.
   */
  public static Change stampArrival(final GameState data, final Collection<Unit> moved) {
    final int stamp = data.getSequence().getRound() * 1000 + data.getSequence().getStepIndex();
    final CompositeChange change = new CompositeChange();
    for (final Unit unit : moved) {
      final List<Unit> carried = new ArrayList<>(unit.getTransporting());
      carried.add(unit);
      for (final Unit u : carried) {
        if (MunitionsAttachment.isMunitions(u) && u.getArrived() != stamp) {
          change.add(ChangeFactory.unitPropertyChange(u, stamp, Unit.PropertyName.ARRIVED));
        }
      }
    }
    return change;
  }

  /**
   * Request 32: at the end of the turn of a nation listed in {@value #ABSORB_PLAYERS}, the other
   * listed nations' munitions on its land become its PUs, at most {@link #absorbLimit} of them,
   * newest arrivals first. Returns text for the end turn report.
   */
  public static String absorb(final IDelegateBridge bridge, final GamePlayer player) {
    final GameState data = bridge.getData();
    final List<String> players = list(data, ABSORB_PLAYERS);
    if (!players.contains(player.getName())) {
      return "";
    }
    final List<Territory> places = new ArrayList<>();
    final List<Unit> waiting = new ArrayList<>();
    for (final Territory t : data.getMap().getTerritoriesOwnedBy(player)) {
      if (t.isWater()) {
        continue;
      }
      for (final Unit u :
          t.getMatches(
              u ->
                  MunitionsAttachment.isMunitions(u)
                      && !u.isOwnedBy(player)
                      && players.contains(u.getOwner().getName()))) {
        waiting.add(u);
        places.add(t);
      }
    }
    if (waiting.isEmpty()) {
      return "";
    }
    final List<Integer> order = new ArrayList<>();
    for (int i = 0; i < waiting.size(); i++) {
      order.add(i);
    }
    // newest first; equal stamps keep map order
    order.sort(Comparator.comparingInt((Integer i) -> -waiting.get(i).getArrived()));
    final int limit = Math.min(absorbLimit(player), waiting.size());
    final CompositeChange change = new CompositeChange();
    final List<Unit> absorbed = new ArrayList<>();
    int total = 0;
    for (final int i : order.subList(0, limit)) {
      final Unit u = waiting.get(i);
      absorbed.add(u);
      total += MunitionsAttachment.valueOf(u);
      change.add(ChangeFactory.removeUnits(places.get(i), List.of(u)));
    }
    if (absorbed.isEmpty()) {
      return "";
    }
    change.add(ChangeFactory.changeResourcesChange(player, pus(bridge), total));
    final int left = waiting.size() - limit;
    final String from =
        String.join(
            " and ",
            absorbed.stream().map(u -> PlayerNames.display(u.getOwner())).distinct().toList());
    final String text =
        PlayerNames.display(player)
            + " absorbs "
            + absorbed.size()
            + (absorbed.size() == 1 ? " munition" : " munitions")
            + " from "
            + from
            + " ("
            + total
            + MyFormatter.pluralize(" PU", total)
            + ")"
            + (left > 0 ? "; " + left + (left == 1 ? " waits" : " wait") : "")
            + ".";
    bridge.getHistoryWriter().startEvent(text, absorbed);
    bridge.addChange(change);
    return text + "<br />";
  }

  /**
   * At the end of the turn of a nation that converts munitions, munitions (anyone's) on its land
   * become its PUs. Returns text for the end turn report.
   */
  public static String convert(final IDelegateBridge bridge, final GamePlayer player) {
    final PlayerAttachment attachment = PlayerAttachment.get(player);
    if (attachment == null || !attachment.getConvertsMunitions()) {
      return "";
    }
    final CompositeChange change = new CompositeChange();
    final List<Unit> converted = new ArrayList<>();
    int total = 0;
    for (final Territory t : bridge.getData().getMap().getTerritoriesOwnedBy(player)) {
      if (t.isWater()) {
        continue;
      }
      final Collection<Unit> munitions = t.getMatches(MunitionsAttachment::isMunitions);
      if (!munitions.isEmpty()) {
        total += munitions.stream().mapToInt(MunitionsAttachment::valueOf).sum();
        converted.addAll(munitions);
        change.add(ChangeFactory.removeUnits(t, munitions));
      }
    }
    if (converted.isEmpty()) {
      return "";
    }
    change.add(ChangeFactory.changeResourcesChange(player, pus(bridge), total));
    final String text =
        player.getName()
            + " turn "
            + MyFormatter.unitsToText(converted)
            + " into "
            + total
            + MyFormatter.pluralize(" PU", total);
    bridge.getHistoryWriter().startEvent(text, converted);
    bridge.addChange(change);
    return text + "<br />";
  }

  /**
   * When a territory is captured, munitions there that belong to the captor's enemies become the
   * captor's PUs.
   */
  public static void capture(
      final IDelegateBridge bridge,
      final Territory territory,
      final GamePlayer captor,
      final @Nullable UndoableMove changeTracker) {
    final Collection<Unit> munitions =
        territory.getMatches(
            u ->
                MunitionsAttachment.isMunitions(u)
                    && bridge.getData().getRelationshipTracker().isAtWar(captor, u.getOwner()));
    if (munitions.isEmpty()) {
      return;
    }
    final int total = munitions.stream().mapToInt(MunitionsAttachment::valueOf).sum();
    final CompositeChange change = new CompositeChange();
    change.add(ChangeFactory.removeUnits(territory, munitions));
    change.add(ChangeFactory.changeResourcesChange(captor, pus(bridge), total));
    final IDelegateHistoryWriter history = bridge.getHistoryWriter();
    history.addChildToEvent(
        captor.getName()
            + " capture "
            + MyFormatter.unitsToText(munitions)
            + " in "
            + territory.getName()
            + ": "
            + total
            + MyFormatter.pluralize(" PU", total),
        munitions);
    bridge.addChange(change);
    if (changeTracker != null) {
      changeTracker.addChange(change);
    }
  }

  private static Resource pus(final IDelegateBridge bridge) {
    return bridge.getData().getResourceList().getResourceOrThrow(Constants.PUS);
  }
}
