package games.strategy.triplea.delegate.power.calculator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.triplea.delegate.battle.BattleState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Flames and Steel request 25 on the installed map: battleship support (+1, one ship per
 * battleship, two per Yamato) goes to destroyers first; a cruiser gets it only when every destroyer
 * is supported. The same on attack and defence, and the same whether the side's ships fire together
 * or destroyers fire on their own (naval combat phases). Skipped when the map is not installed.
 */
class BattleshipSupportTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer americans;
  private GamePlayer japanese;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = GameParser.parse(XML, false).orElseThrow();
    americans = data.getPlayerList().getPlayerId("Americans");
    japanese = data.getPlayerList().getPlayerId("Japanese");
  }

  private List<Unit> ships(final String type, final int count) {
    return data.getUnitTypeList().getUnitType(type).orElseThrow().create(count, americans);
  }

  private CombatValue combatValue(final Collection<Unit> side, final BattleState.Side role) {
    return CombatValueBuilder.mainCombatValue()
        .enemyUnits(data.getUnitTypeList().getUnitType("cruiser").orElseThrow().create(1, japanese))
        .friendlyUnits(side)
        .side(role)
        .gameSequence(data.getSequence())
        .supportAttachments(data.getUnitTypeList().getSupportRules())
        .lhtrHeavyBombers(false)
        .gameDiceSides(data.getDiceSides())
        .territoryEffects(List.of())
        .build();
  }

  /** Base value of destroyers 1, cruisers 2 (attack and defence alike). */
  private static int base(final Unit unit) {
    return unit.getType().getName().equals("destroyer") ? 1 : 2;
  }

  /** How many of these units roll above their base value, firing in these groups. */
  private int supported(
      final List<Unit> side,
      final List<Unit> counted,
      final BattleState.Side role,
      final boolean destroyersFireAlone) {
    final CombatValue value = combatValue(side, role);
    final List<List<Unit>> groups = new ArrayList<>();
    if (destroyersFireAlone) {
      groups.add(side.stream().filter(u -> u.getType().getName().equals("destroyer")).toList());
      groups.add(side.stream().filter(u -> !u.getType().getName().equals("destroyer")).toList());
    } else {
      groups.add(side);
    }
    int result = 0;
    for (final List<Unit> group : groups) {
      final PowerStrengthAndRolls power = PowerStrengthAndRolls.build(group, value);
      for (final Unit unit : group) {
        if (counted.contains(unit) && power.getStrength(unit) > base(unit)) {
          result++;
        }
      }
    }
    return result;
  }

  private record Fleet(List<Unit> all, List<Unit> destroyers, List<Unit> cruisers) {}

  private Fleet fleet(final int battleships, final int yamatos) {
    final List<Unit> destroyers = ships("destroyer", 2);
    final List<Unit> cruisers = ships("cruiser", 2);
    final List<Unit> all = new ArrayList<>();
    // cruisers listed first, so the order on the list can't be what decides
    all.addAll(cruisers);
    all.addAll(destroyers);
    all.addAll(ships("battleship", battleships));
    all.addAll(ships("yamatoBattleship", yamatos));
    return new Fleet(all, destroyers, cruisers);
  }

  private void check(
      final Fleet fleet, final int destroyers, final int cruisers, final BattleState.Side role) {
    for (final boolean apart : new boolean[] {false, true}) {
      final String how = role + (apart ? ", destroyers firing alone" : ", firing together");
      assertThat(
          "destroyers supported, " + how,
          supported(fleet.all(), fleet.destroyers(), role, apart),
          is(destroyers));
      assertThat(
          "cruisers supported, " + how,
          supported(fleet.all(), fleet.cruisers(), role, apart),
          is(cruisers));
    }
  }

  @ParameterizedTest
  @EnumSource(BattleState.Side.class)
  void oneBattleshipSupportsOneDestroyer(final BattleState.Side role) {
    check(fleet(1, 0), 1, 0, role);
  }

  @ParameterizedTest
  @EnumSource(BattleState.Side.class)
  void threeBattleshipsSupportBothDestroyersAndOneCruiser(final BattleState.Side role) {
    check(fleet(3, 0), 2, 1, role);
  }

  @ParameterizedTest
  @EnumSource(BattleState.Side.class)
  void yamatoAndBattleshipSupportBothDestroyersAndOneCruiser(final BattleState.Side role) {
    check(fleet(1, 1), 2, 1, role);
  }

  @ParameterizedTest
  @EnumSource(BattleState.Side.class)
  void supportMovesToACruiserWhenADestroyerIsSunk(final BattleState.Side role) {
    // 2 battleships: both destroyers supported, no cruiser
    final Fleet fleet = fleet(2, 0);
    check(fleet, 2, 0, role);
    // a destroyer sinks: next round its support goes to a cruiser
    final Unit sunk = fleet.destroyers().get(0);
    final List<Unit> afterwards = new ArrayList<>(fleet.all());
    afterwards.remove(sunk);
    final List<Unit> destroyersLeft = List.of(fleet.destroyers().get(1));
    for (final boolean apart : new boolean[] {false, true}) {
      assertThat(supported(afterwards, destroyersLeft, role, apart), is(1));
      assertThat(supported(afterwards, fleet.cruisers(), role, apart), is(1));
    }
  }

  @ParameterizedTest
  @EnumSource(BattleState.Side.class)
  void supportedShipsRollOneHigher(final BattleState.Side role) {
    final Fleet fleet = fleet(1, 0);
    final PowerStrengthAndRolls power =
        PowerStrengthAndRolls.build(fleet.all(), combatValue(fleet.all(), role));
    final List<Integer> destroyerValues =
        fleet.destroyers().stream().map(power::getStrength).sorted().toList();
    assertThat(destroyerValues, is(List.of(1, 2)));
    assertThat(fleet.cruisers().stream().map(power::getStrength).toList(), is(List.of(2, 2)));
  }
}
