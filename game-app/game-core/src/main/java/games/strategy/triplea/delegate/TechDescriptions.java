package games.strategy.triplea.delegate;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.delegate.air.AirCombat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.experimental.UtilityClass;
import org.triplea.java.collections.IntegerMap;

/**
 * Flames and Steel (request 21): a tech's unit bonuses in plain words, from its
 * techAbilityAttachment, e.g. Night Action: "+1 attack and defense for cruisers; Yamato battleships
 * roll one extra die on attack and defense." Empty for a tech whose effects are not unit bonuses
 * (triggers and the like).
 */
@UtilityClass
public class TechDescriptions {
  /** Request 35: game property "FS Tech Description &lt;tech&gt;" (and "... &lt;player&gt;"). */
  public static final String PREFIX = "FS Tech Description ";

  /**
   * Request 35: the description shown for a tech: the nation's own property ("FS Tech Description
   * Propaganda1 Russians"), else the tech's ("FS Tech Description Propaganda1"), else the text
   * built from its unit bonuses.
   */
  public static String describe(final TechAdvance tech, final GamePlayer player) {
    if (player != null && tech.getData() != null) {
      final String own =
          tech.getData()
              .getProperties()
              .get(PREFIX + tech.getName() + " " + player.getName(), "");
      if (!own.isBlank()) {
        return own;
      }
    }
    return describeAnyNation(tech);
  }

  /** As {@link #describe(TechAdvance, GamePlayer)} where no nation is meant (the tech table). */
  public static String describeAnyNation(final TechAdvance tech) {
    if (tech.getData() != null) {
      final String general = tech.getData().getProperties().get(PREFIX + tech.getName(), "");
      if (!general.isBlank()) {
        return general;
      }
    }
    return describe(tech);
  }

  public static String describe(final TechAdvance tech) {
    final TechAbilityAttachment ability = TechAbilityAttachment.get(tech);
    if (ability == null) {
      return "";
    }
    final IntegerMap<UnitType> attack = ability.getAttackBonus();
    final IntegerMap<UnitType> defense = ability.getDefenseBonus();
    final IntegerMap<UnitType> attackRolls = ability.getAttackRollsBonus();
    final IntegerMap<UnitType> defenseRolls = ability.getDefenseRollsBonus();
    final IntegerMap<UnitType> movement = ability.getMovementBonus();
    final IntegerMap<UnitType> dogfight = ability.getDogfightBonus();
    final IntegerMap<UnitType> dogfightRolls = ability.getDogfightRollsBonus();
    final IntegerMap<UnitType> interception = ability.getInterceptionBonus();
    final IntegerMap<UnitType> interceptionRolls = ability.getInterceptionRollsBonus();
    final IntegerMap<UnitType> airDefense = ability.getBomberAirDefenseBonus();
    final IntegerMap<UnitType> airDefenseRolls = ability.getBomberAirDefenseRollsBonus();
    final IntegerMap<UnitType> windowPenalty = ability.getInterceptionPenaltyAgainst();
    // request 39: bomber bonus dice, and extra hit points (Fw-190G, P-47)
    final IntegerMap<UnitType> landBonus = ability.getLandBonus();
    final IntegerMap<UnitType> navalBonus = ability.getNavalBonus();
    final IntegerMap<UnitType> landBonusSet = ability.getLandBonusSet();
    final IntegerMap<UnitType> navalBonusSet = ability.getNavalBonusSet();
    final IntegerMap<UnitType> hitPoints = ability.getHitPointBonus();
    final Set<UnitType> types = new LinkedHashSet<>();
    for (final IntegerMap<UnitType> map :
        List.of(
            attack,
            defense,
            attackRolls,
            defenseRolls,
            movement,
            dogfight,
            dogfightRolls,
            interception,
            interceptionRolls,
            airDefense,
            airDefenseRolls,
            windowPenalty,
            landBonus,
            navalBonus,
            landBonusSet,
            navalBonusSet,
            hitPoints)) {
      types.addAll(map.keySet());
    }
    final List<String> clauses = new ArrayList<>();
    for (final UnitType type : types) {
      final String plural = plural(type);
      bonus(clauses, attack.getInt(type), defense.getInt(type), "attack", "defense", plural);
      rolls(clauses, attackRolls.getInt(type), defenseRolls.getInt(type), plural);
      if (movement.getInt(type) != 0) {
        clauses.add(signed(movement.getInt(type)) + " movement for " + plural);
      }
      if (dogfight.getInt(type) != 0) {
        clauses.add(signed(dogfight.getInt(type)) + " dogfight value for " + plural);
      }
      if (windowPenalty.getInt(type) != 0) {
        clauses.add(
            "enemy fighters intercepting bombers that include your "
                + plural
                + " lose "
                + windowPenalty.getInt(type)
                + " interception (never below 1)");
      }
      if (dogfightRolls.getInt(type) != 0) {
        clauses.add(
            capitalize(plural)
                + " roll "
                + count(dogfightRolls.getInt(type))
                + " extra dogfight "
                + dice(dogfightRolls.getInt(type)));
      }
      strikeBonus(clauses, landBonus.getInt(type), landBonusSet.getInt(type), "land units", plural);
      strikeBonus(clauses, navalBonus.getInt(type), navalBonusSet.getInt(type), "ships", plural);
      if (hitPoints.getInt(type) != 0) {
        clauses.add(signed(hitPoints.getInt(type)) + " hit point for " + plural);
      }
      // request 27: interception (fighters) and air defense (bombers) are separate values
      airValue(clauses, interception.getInt(type), interceptionRolls.getInt(type), "interception", plural);
      airValue(clauses, airDefense.getInt(type), airDefenseRolls.getInt(type), "air defense", plural);
    }
    return clauses.isEmpty() ? "" : capitalize(String.join("; ", clauses)) + ".";
  }

  /** Request 39: "dive bombers' bonus die against land units is now 3", "+1 to ...". */
  private static void strikeBonus(
      final List<String> clauses,
      final int added,
      final int set,
      final String what,
      final String plural) {
    if (set > 0) {
      clauses.add(
          plural
              + "' bonus die against "
              + what
              + " is now "
              + set
              + " (if not higher already)");
    }
    if (added != 0) {
      clauses.add(signed(added) + " to " + plural + "' bonus die against " + what);
    }
  }

  private static void airValue(
      final List<String> clauses,
      final int bonus,
      final int rolls,
      final String what,
      final String plural) {
    if (bonus != 0) {
      clauses.add(signed(bonus) + " " + what + " value for " + plural);
    }
    if (rolls != 0) {
      clauses.add(
          capitalize(plural) + " roll " + count(rolls) + " extra " + what + " " + dice(rolls));
    }
  }

  private static void bonus(
      final List<String> clauses,
      final int a,
      final int d,
      final String first,
      final String second,
      final String plural) {
    if (a != 0 && a == d) {
      clauses.add(signed(a) + " " + first + " and " + second + " for " + plural);
      return;
    }
    if (a != 0) {
      clauses.add(signed(a) + " " + first + " for " + plural);
    }
    if (d != 0) {
      clauses.add(signed(d) + " " + second + " for " + plural);
    }
  }

  private static void rolls(
      final List<String> clauses, final int a, final int d, final String plural) {
    if (a != 0 && a == d) {
      clauses.add(
          capitalize(plural)
              + " roll "
              + count(a)
              + " extra "
              + dice(a)
              + " on attack and defense");
      return;
    }
    if (a != 0) {
      clauses.add(capitalize(plural) + " roll " + count(a) + " extra " + dice(a) + " on attack");
    }
    if (d != 0) {
      clauses.add(capitalize(plural) + " roll " + count(d) + " extra " + dice(d) + " on defense");
    }
  }

  private static String plural(final UnitType type) {
    final String name = AirCombat.unitName(type);
    return name.endsWith("s") ? name + "es" : name + "s";
  }

  private static String signed(final int n) {
    return (n > 0 ? "+" : "") + n;
  }

  private static String count(final int n) {
    return switch (n) {
      case 1 -> "one";
      case 2 -> "two";
      case 3 -> "three";
      default -> String.valueOf(n);
    };
  }

  private static String dice(final int n) {
    return n == 1 ? "die" : "dice";
  }

  private static String capitalize(final String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
