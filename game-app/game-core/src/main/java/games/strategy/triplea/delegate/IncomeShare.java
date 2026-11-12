package games.strategy.triplea.delegate;

import games.strategy.engine.data.Change;
import games.strategy.engine.data.CompositeChange;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Constants;
import games.strategy.triplea.Properties;
import games.strategy.triplea.formatter.PlayerNames;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel (request 65): a nation that pays its income on, e.g. Vichy France.
 *
 * <p>Game property {@code "FS Income Share <payer>"} = {@code recipient:percent:territories;...}.
 * Each part names a recipient, a percent and the territories whose income it shares; the single
 * word {@value #REST} instead of territories means every territory not named in another part. For
 * Vichy: {@code Germans:50:REST;Japanese:50:Tonkin:Annam:Cambodia:Cochinchina}.
 *
 * <p>When the payer collects income, each recipient gets ⌊income of its territories this turn ×
 * percent / 100⌋ PUs (each part rounded on its own total). The payer keeps none of the PUs it
 * gained in the step: the rest disappears. A territory counts only while the payer still owns it
 * and could collect income from it. Manpower is not shared.
 */
public final class IncomeShare {
  public static final String PREFIX = "FS Income Share ";
  public static final String REST = "REST";

  private IncomeShare() {}

  /** One part of the property: who gets what percent of which territories' income. */
  public record Part(
      GamePlayer recipient, int percent, List<Territory> territories, boolean rest) {}

  /** One payment worked out for this turn. */
  public record Payment(GamePlayer recipient, int percent, int income, int pus) {}

  public static boolean applies(final GamePlayer player) {
    return !parts(player).isEmpty();
  }

  /** The parts of the payer's property; unknown players or territories are skipped. */
  public static List<Part> parts(final GamePlayer player) {
    final List<Part> result = new ArrayList<>();
    if (player == null || player.isNull() || player.getData() == null) {
      return result;
    }
    final GameState data = player.getData();
    final String text = data.getProperties().get(PREFIX + player.getName(), "").trim();
    if (text.isEmpty()) {
      return result;
    }
    for (final String part : text.split(";")) {
      final String[] bits = part.trim().split(":");
      if (bits.length < 2) {
        continue;
      }
      final GamePlayer recipient = data.getPlayerList().getPlayerId(bits[0].trim());
      final int percent;
      try {
        percent = Math.max(0, Math.min(100, Integer.parseInt(bits[1].trim())));
      } catch (final NumberFormatException e) {
        continue;
      }
      if (recipient == null) {
        continue;
      }
      boolean rest = false;
      final List<Territory> territories = new ArrayList<>();
      for (int i = 2; i < bits.length; i++) {
        final String name = bits[i].trim();
        if (name.equals(REST)) {
          rest = true;
        } else {
          final Territory t = data.getMap().getTerritoryOrNull(name);
          if (t != null) {
            territories.add(t);
          }
        }
      }
      result.add(new Part(recipient, percent, territories, rest));
    }
    return result;
  }

  /** What the payer pays this turn from the territories it owns now. */
  public static List<Payment> payments(final GamePlayer player) {
    final List<Part> parts = parts(player);
    final GameState data = player.getData();
    final Set<Territory> named = new HashSet<>();
    parts.forEach(p -> named.addAll(p.territories()));
    final List<Territory> owned = data.getMap().getTerritoriesOwnedBy(player);
    final int multiplier = Properties.getPuMultiplier(data.getProperties());
    final List<Payment> result = new ArrayList<>();
    for (final Part part : parts) {
      final List<Territory> group = new ArrayList<>();
      for (final Territory t : owned) {
        if (part.territories().contains(t) || (part.rest() && !named.contains(t))) {
          group.add(t);
        }
      }
      final int income = AbstractEndTurnDelegate.getProduction(group, data) * multiplier;
      result.add(
          new Payment(part.recipient(), part.percent(), income, income * part.percent() / 100));
    }
    return result;
  }

  /**
   * Pays the shares and removes every PU the payer gained in this step ({@code before} = its
   * resources when the step began). Returns the report line, or "" when the payer has no shares.
   */
  public static String pay(
      final IDelegateBridge bridge, final GamePlayer player, final IntegerMap<Resource> before) {
    if (!applies(player)) {
      return "";
    }
    final GameData data = bridge.getData();
    final Resource pus = data.getResourceList().getResourceOrThrow(Constants.PUS);
    final List<Payment> payments = payments(player);
    final CompositeChange change = new CompositeChange();
    final List<String> paid = new ArrayList<>();
    for (final Payment p : payments) {
      if (p.pus() > 0) {
        change.add(ChangeFactory.changeResourcesChange(p.recipient(), pus, p.pus()));
      }
      paid.add(
          PlayerNames.display(p.recipient()) + " " + p.pus() + (p.pus() == 1 ? " PU" : " PUs"));
    }
    final int gained = player.getResources().getQuantity(pus) - before.getInt(pus);
    if (gained > 0) {
      final Change keepNothing = ChangeFactory.changeResourcesChange(player, pus, -gained);
      change.add(keepNothing);
    }
    // "Vichy France pays Germans 10 PUs and Japanese 3 PUs"
    final String message =
        PlayerNames.display(player)
            + " pays "
            + join(paid)
            + (paid.isEmpty() ? "nothing" : "")
            + " ("
            + detail(payments)
            + "); it keeps nothing.";
    bridge.getHistoryWriter().startEvent(message);
    if (!change.isEmpty()) {
      bridge.addChange(change);
    }
    return "<br />" + message + "<br />";
  }

  private static String join(final List<String> items) {
    if (items.size() <= 1) {
      return String.join("", items);
    }
    return String.join(", ", items.subList(0, items.size() - 1))
        + " and "
        + items.get(items.size() - 1);
  }

  private static String detail(final List<Payment> payments) {
    final List<String> parts = new ArrayList<>();
    for (final Payment p : payments) {
      parts.add(p.percent() + "% of " + p.income());
    }
    return String.join(", ", parts);
  }
}
