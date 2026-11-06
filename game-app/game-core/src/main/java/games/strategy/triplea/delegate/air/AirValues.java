package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.attachments.TechAttachment;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.formatter.TechNames;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javax.annotation.Nullable;
import lombok.experimental.UtilityClass;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel (request 53 item 5): the values an aircraft type really uses in the air stage,
 * for this nation now (tech and nation bonuses included), as the purchase screen, Edit mode and
 * unit tooltips show them. Aircraft never fight in ordinary battles, so stock's attack and defense
 * numbers would mislead: fighter-types show dogfight and interception, bomber-types attack and air
 * defense (and strategic bombing where they have it).
 */
@UtilityClass
public class AirValues {

  /** Whether the type is an aircraft with air-stage values (has an airUnitAttachment). */
  public static boolean applies(final UnitType type) {
    return type.getUnitAttachment().isAir() && AirUnitAttachment.get(type).isPresent();
  }

  /**
   * The purchase screen's line under the name: "Dogfight 1 | Interception 1 | Range 3 | Hits 2" or
   * "Attack 2 | Air defense 1 | Range 3 | Hits 2 | Bombing 2". Empty for other units.
   */
  public static String statLine(final UnitType type, final GamePlayer player) {
    if (!applies(type)) {
      return "";
    }
    final List<String> parts = new ArrayList<>();
    if (AirUnitAttachment.get(type).orElseThrow().isFighter()) {
      parts.add("Dogfight " + AirCombat.dogfightValue(type, player));
      parts.add("Interception " + AirCombat.interceptionValue(type, player));
    } else {
      parts.add("Attack " + type.getUnitAttachment().getAttack(player));
      parts.add("Air defense " + AirCombat.airDefenseValue(type, player));
    }
    parts.add("Range " + AirRange.range(type, player));
    parts.add("Hits " + hits(type, player));
    bombing(type, player).ifPresent(b -> parts.add("Bombing " + b));
    return String.join(" | ", parts);
  }

  /** As {@link #statLine}, on two lines for the narrow purchase cells. */
  public static String statLineHtml(final UnitType type, final GamePlayer player) {
    final String line = statLine(type, player);
    if (line.isEmpty()) {
      return "";
    }
    final String[] parts = line.split(" \\| ");
    if (parts.length < 2) {
      return "<html>" + unbreakable(line) + "</html>";
    }
    // request 57: two lines of about equal length ("Attack 3 | Air defense 1" / "Range 8 | Hits 2 |
    // Bombing 1/2"), each kept whole so a narrow tile never wraps it one word per line
    int split = 1;
    int best = Integer.MAX_VALUE;
    for (int k = 1; k < parts.length; k++) {
      final int longer =
          Math.max(
              String.join(" | ", Arrays.copyOfRange(parts, 0, k)).length(),
              String.join(" | ", Arrays.copyOfRange(parts, k, parts.length)).length());
      if (longer < best) {
        best = longer;
        split = k;
      }
    }
    return "<html>"
        + unbreakable(String.join(" | ", Arrays.copyOfRange(parts, 0, split)))
        + "<br>"
        + unbreakable(String.join(" | ", Arrays.copyOfRange(parts, split, parts.length)))
        + "</html>";
  }

  /** The text with non-breaking spaces, so an HTML label keeps it on one line. */
  public static String unbreakable(final String text) {
    return text.replace(" ", "&nbsp;");
  }

  /**
   * Request 53: one short line per value, in HTML for a tooltip: what it is, the type's own value
   * and every bonus or penalty in force (the nation's own, then each tech), and techs the nation
   * may still get that change it. Empty for other units.
   */
  public static String explanationHtml(final UnitType type, final GamePlayer player) {
    if (!applies(type)) {
      return "";
    }
    final AirUnitAttachment air = AirUnitAttachment.get(type).orElseThrow();
    final List<String> lines = new ArrayList<>();
    if (air.isFighter()) {
      lines.add(
          line(
              "Dogfight",
              "hits on this or less in a dogfight",
              type,
              player,
              air.getDogfight(),
              AirCombat.dogfightValue(type, player),
              TechAttachment::getDogfightBonus,
              TechAbilityAttachment::getDogfightBonus));
      lines.add(
          line(
              "Interception",
              "hits on this or less against bombers",
              type,
              player,
              air.getInterception(),
              AirCombat.interceptionValue(type, player),
              TechAttachment::getInterceptionBonus,
              TechAbilityAttachment::getInterceptionBonus));
    } else {
      final int attack = type.getUnitAttachment().getAttack(player);
      final int attackBonus = type.getData().getTechTracker().getAttackBonus(player, type);
      lines.add(
          line(
              "Attack",
              "hits on this or less in a strike on land units or ships",
              type,
              player,
              attack - attackBonus,
              attack,
              TechAttachment::getAttackBonus,
              TechAbilityAttachment::getAttackBonus));
      lines.add(
          line(
              "Air defense",
              "hits on this or less firing back at interceptors",
              type,
              player,
              air.getAirDefense(),
              AirCombat.airDefenseValue(type, player),
              null,
              TechAbilityAttachment::getBomberAirDefenseBonus));
    }
    lines.add("<b>Range " + AirRange.range(type, player) + "</b>: how far it flies from its base");
    lines.add("<b>Hits " + hits(type, player) + "</b>: hits it takes to destroy");
    if (bombing(type, player).isPresent()) {
      lines.add(
          line(
              "Bombing",
              "PUs one strategic bombing hit destroys",
              type,
              player,
              air.getBombingDamage(),
              bombing(type, player).orElseThrow(),
              null,
              TechAbilityAttachment::getBombingBonus));
    }
    lines.add(
        "<i>Aircraft fight only in the air stage; stock attack and defense are not used.</i>");
    return String.join("<br>", lines);
  }

  private static int hits(final UnitType type, final GamePlayer player) {
    return type.getUnitAttachment().getHitPoints()
        + type.getData().getTechTracker().getHitPointBonus(player, type);
  }

  private static Optional<Integer> bombing(final UnitType type, final GamePlayer player) {
    final int base = AirUnitAttachment.get(type).map(AirUnitAttachment::getBombingDamage).orElse(0);
    if (base <= 0) {
      return Optional.empty();
    }
    return Optional.of(
        Math.max(0, base + type.getData().getTechTracker().getBombingBonus(player, type)));
  }

  private static String line(
      final String name,
      final String meaning,
      final UnitType type,
      final GamePlayer player,
      final int base,
      final int total,
      final @Nullable Function<TechAttachment, IntegerMap<UnitType>> nation,
      final Function<TechAbilityAttachment, IntegerMap<UnitType>> tech) {
    final StringBuilder sb =
        new StringBuilder("<b>").append(name).append(' ').append(total).append("</b>: ");
    sb.append(meaning);
    final List<String> sources = new ArrayList<>();
    final int nationBonus =
        nation == null ? 0 : nation.apply(player.getTechAttachment()).getInt(type);
    if (nationBonus != 0) {
      sources.add(
          AirCombat.adjective(player)
              + (nationBonus < 0 ? " starting penalty " : " bonus ")
              + signed(nationBonus));
    }
    final Collection<TechAdvance> have =
        TechTracker.getCurrentTechAdvances(player, type.getData().getTechnologyFrontier());
    for (final TechAdvance t : have) {
      final int b = bonus(t, tech, type);
      if (b != 0) {
        sources.add(TechNames.display(t, player) + " " + signed(b));
      }
    }
    if (!sources.isEmpty()) {
      sb.append(" (type ").append(base).append("; ").append(String.join(", ", sources)).append(')');
    }
    // techs the nation may still get that change the value
    final List<String> later = new ArrayList<>();
    for (final TechAdvance t : player.getTechnologyFrontierList().getAdvances()) {
      if (have.contains(t)) {
        continue;
      }
      final int b = bonus(t, tech, type);
      if (b != 0) {
        later.add(TechNames.display(t, player) + " " + signed(b));
      }
    }
    if (!later.isEmpty()) {
      sb.append(nationBonus < 0 ? "; penalty offset by " : "; later: ")
          .append(String.join(", ", later));
    }
    return sb.toString();
  }

  private static int bonus(
      final TechAdvance t,
      final Function<TechAbilityAttachment, IntegerMap<UnitType>> getter,
      final UnitType type) {
    final TechAbilityAttachment a = TechAbilityAttachment.get(t);
    return a == null ? 0 : getter.apply(a).getInt(type);
  }

  private static String signed(final int n) {
    return n > 0 ? "+" + n : "−" + (-n);
  }
}
