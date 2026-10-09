package games.strategy.triplea.delegate;

import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GamePlayer;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;

/** Flames and Steel lend-lease munitions: conversion into PUs, and capture. */
@UtilityClass
public class Munitions {

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
