package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Flames and Steel long-range raid (request 26), the "Amerika Bomber": the listed nations' aircraft
 * of the listed types may move from one air box straight to a bombing zone that is neither adjacent
 * nor in normal range, and there bomb only factories (and SPCs) in the listed territories. Range
 * and landing spots don't apply to these targets. After the strikes they fly back to the box they
 * came from.
 *
 * <p>Set by game properties, each a colon-separated list; when a property is missing the default
 * below is used, and an empty value switches the rule off:
 *
 * <ul>
 *   <li>{@value #UNITS} (default {@code flyingWing})
 *   <li>{@value #PLAYERS} (default {@code Germans})
 *   <li>{@value #FROM} (default {@code Air - Western Europe})
 *   <li>{@value #TO} (default {@code Air - Bomb USA})
 *   <li>{@value #TARGETS} (default the US territories east of the Mississippi)
 * </ul>
 */
@UtilityClass
public class LongRangeRaid {
  public static final String UNITS = "FS Long Range Raid Units";
  public static final String PLAYERS = "FS Long Range Raid Players";
  public static final String FROM = "FS Long Range Raid From";
  public static final String TO = "FS Long Range Raid To";
  public static final String TARGETS = "FS Long Range Raid Targets";

  static final String DEFAULT_UNITS = "flyingWing";
  static final String DEFAULT_PLAYERS = "Germans";
  static final String DEFAULT_FROM = "Air - Western Europe";
  // request 32: Bomb USA became Bomb America Atlantic; New Orleans (America Pacific) dropped
  static final String DEFAULT_TO = "Air - Bomb America Atlantic";
  static final String DEFAULT_TARGETS =
      "Chicago:Detroit:Pittsburgh:New York:Washington:New England:Kentucky-Tennessee:Carolinas"
          + ":Florida:Mobile";

  /** The label in the allocation grid and on the map. */
  public static final String LABEL = "Long-range raid";

  private static List<String> list(final GameData data, final String property, final String def) {
    final String value = data.getProperties().get(property, def);
    return value == null || value.isBlank()
        ? List.of()
        : Arrays.stream(value.split(":")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  private static Optional<Territory> zone(
      final GameData data, final String property, final String def) {
    final List<String> names = list(data, property, def);
    if (names.isEmpty()) {
      return Optional.empty();
    }
    final Territory territory = data.getMap().getTerritoryOrNull(names.get(0));
    return Optional.ofNullable(territory);
  }

  /** The box raids start from, if the rule is on. */
  public static Optional<Territory> from(final GameData data) {
    return zone(data, FROM, DEFAULT_FROM);
  }

  /** The bombing zone raids go to, if the rule is on. */
  public static Optional<Territory> to(final GameData data) {
    return zone(data, TO, DEFAULT_TO);
  }

  /** The territories whose factories a raid may bomb. */
  public static List<String> targets(final GameData data) {
    return list(data, TARGETS, DEFAULT_TARGETS);
  }

  /** Whether this nation's aircraft of this type may fly long-range raids. */
  public static boolean isRaider(final GamePlayer player, final UnitType type) {
    final GameData data = player.getData();
    return list(data, PLAYERS, DEFAULT_PLAYERS).contains(player.getName())
        && list(data, UNITS, DEFAULT_UNITS).contains(type.getName());
  }

  /** Whether this move is a long-range raid: a raider from the raid box to the raid zone. */
  public static boolean allowsMove(
      final GamePlayer player, final UnitType type, final Territory from, final Territory to) {
    final GameData data = player.getData();
    return isRaider(player, type)
        && from(data).filter(from::equals).isPresent()
        && to(data).filter(to::equals).isPresent();
  }

  /** Whether this nation's aircraft of this type in this zone bomb by the raid rules. */
  public static boolean isRaidZone(
      final GamePlayer player, final UnitType type, final Territory zone) {
    return isRaider(player, type) && to(player.getData()).filter(zone::equals).isPresent();
  }

  /** Whether a raid may bomb this territory. */
  public static boolean isTarget(final GameData data, final Territory territory) {
    return targets(data).contains(territory.getName());
  }

  /** Why a raider can't bomb this territory: it is not on the list. */
  public static String refusal(final GameData data, final Territory territory) {
    return territory.getName()
        + " is out of reach of a long-range raid; it may bomb only "
        + String.join(", ", targets(data))
        + ".";
  }
}
