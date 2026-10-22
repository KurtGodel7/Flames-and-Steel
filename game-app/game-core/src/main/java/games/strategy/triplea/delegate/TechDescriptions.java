package games.strategy.triplea.delegate;

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
            airDefenseRolls)) {
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
      if (dogfightRolls.getInt(type) != 0) {
        clauses.add(
            capitalize(plural)
                + " roll "
                + count(dogfightRolls.getInt(type))
                + " extra dogfight "
                + dice(dogfightRolls.getInt(type)));
      }
      // request 27: interception (fighters) and air defense (bombers) are separate values
      airValue(clauses, interception.getInt(type), interceptionRolls.getInt(type), "interception", plural);
      airValue(clauses, airDefense.getInt(type), airDefenseRolls.getInt(type), "air defense", plural);
    }
    return clauses.isEmpty() ? "" : capitalize(String.join("; ", clauses)) + ".";
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
