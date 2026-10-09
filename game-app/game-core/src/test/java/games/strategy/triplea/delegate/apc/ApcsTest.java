package games.strategy.triplea.delegate.apc;

import static games.strategy.triplea.delegate.GameDataTestUtil.armour;
import static games.strategy.triplea.delegate.GameDataTestUtil.bomber;
import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.russians;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.DefaultAttachment;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.ApcAttachment;
import games.strategy.triplea.attachments.PlayerAttachment;
import games.strategy.triplea.attachments.TechAbilityAttachment;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.OriginalOwnerTracker;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.TechTracker;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel APCs on the Revised test map. The factory unit type stands in for the APC;
 * armour is an advanced unit built 2 per turn, bombers 1 every 2 turns (needing heavy bombers).
 */
class ApcsTest {
  private final GameData gameData = TestMapGameData.REVISED.getGameData();
  private final GamePlayer germans = germans(gameData);
  private final GamePlayer russians = russians(gameData);
  private final Territory germany = territory("Germany", gameData);
  private final Territory russia = territory("Russia", gameData);
  private UnitType apcType;
  private Unit germanApc;
  private Unit russianApc;

  @BeforeEach
  void setUp() {
    apcType = gameData.getUnitTypeList().getUnitTypeOrThrow("factory");
    apc(apcType, "isApc", "true");
    apc(armour(gameData), "apcUnitsPerTurn", "2");
    apc(bomber(gameData), "apcUnitsPerTurn", "1");
    apc(bomber(gameData), "apcTurnsPerUnit", "2");
    apc(bomber(gameData), "apcRequiredTech", tech("heavyBomber").getName());
    playerAttachment(germans);
    playerAttachment(russians);
    germanApc = germany.getUnitCollection().getMatches(Matches.unitIsOfType(apcType)).get(0);
    russianApc = russia.getUnitCollection().getMatches(Matches.unitIsOfType(apcType)).get(0);
    for (final Territory t : gameData.getMap().getTerritories()) {
      final var factories = t.getUnitCollection().getMatches(Matches.unitIsOfType(apcType));
      if (!factories.isEmpty()) {
        gameData.performChange(
            OriginalOwnerTracker.addOriginalOwnerChange(factories, t.getOwner()));
      }
    }
  }

  @Test
  void limitIsTheLowerOfTheNationalCapAndTheAdvancedTechs() {
    playerAttachment(germans).getPropertyOrThrowUnchecked("apcLimit", "3");
    assertThat(Apcs.getLimit(germans), is(0)); // no Advanced tech yet

    giveTech(germans, "jetPower", 2);
    assertThat(Apcs.getLimit(germans), is(2));

    giveTech(germans, "rocket", 5);
    assertThat(Apcs.getLimit(germans), is(3));
  }

  @Test
  void capturedApcCountsForItsBuilderNotTheCaptor() {
    final int germanCount = Apcs.countTowardsLimit(germans, gameData.getMap());
    final int russianCount = Apcs.countTowardsLimit(russians, gameData.getMap());

    capture(russia, russianApc, germans);

    assertThat(Apcs.countTowardsLimit(germans, gameData.getMap()), is(germanCount));
    assertThat(Apcs.countTowardsLimit(russians, gameData.getMap()), is(russianCount));
  }

  @Test
  void capturedApcBuildsNothingForTheCaptor() {
    capture(russia, russianApc, germans);

    assertThat(Apcs.getOwnApcs(germans, gameData.getMap()).contains(russianApc), is(false));
    assertThat(
        Apcs.validate(germans, Map.of(russianApc, armour(gameData)), gameData.getMap()).isPresent(),
        is(true));
  }

  @Test
  void apcBuildsItsUnitsInItsTerritory() {
    final IDelegateBridge bridge = newDelegateBridge(germans);
    final int armourBefore = count(germany, armour(gameData));
    Apcs.setProduction(bridge, germans, Map.of(germanApc, armour(gameData)));

    Apcs.produce(bridge, germans);

    assertThat(count(germany, armour(gameData)), is(armourBefore + 2));
  }

  @Test
  void slowUnitsAppearEveryOtherTurn() {
    final IDelegateBridge bridge = newDelegateBridge(germans);
    giveTech(germans, "heavyBomber", 0);
    final int bombersBefore = count(germany, bomber(gameData));
    Apcs.setProduction(bridge, germans, Map.of(germanApc, bomber(gameData)));

    Apcs.produce(bridge, germans);
    assertThat(count(germany, bomber(gameData)), is(bombersBefore));

    Apcs.produce(bridge, germans);
    assertThat(count(germany, bomber(gameData)), is(bombersBefore + 1));
  }

  @Test
  void shipsAppearInAnAdjacentSeaZone() {
    final IDelegateBridge bridge = newDelegateBridge(germans);
    final UnitType ship = gameData.getUnitTypeList().getUnitTypeOrThrow("submarine");
    apc(ship, "apcUnitsPerTurn", "2");
    Apcs.setProduction(bridge, germans, Map.of(germanApc, ship));

    Apcs.produce(bridge, germans);

    assertThat(count(germany, ship), is(0));
    final int nearby =
        gameData.getMap().getNeighbors(germany).stream()
            .filter(Territory::isWater)
            .mapToInt(
                t ->
                    t.getUnitCollection()
                        .countMatches(
                            Matches.unitIsOfType(ship).and(Matches.unitIsOwnedBy(germans))))
            .sum();
    assertThat(nearby > 0, is(true));
  }

  @Test
  void unitsNeedTheirTech() {
    assertThat(Apcs.getBuildableTypes(germans), contains(armour(gameData)));
    assertThat(
        Apcs.validate(germans, Map.of(germanApc, bomber(gameData)), gameData.getMap()).isPresent(),
        is(true));

    giveTech(germans, "heavyBomber", 0);
    assertThat(
        Apcs.validate(germans, Map.of(germanApc, bomber(gameData)), gameData.getMap()).isPresent(),
        is(false));
  }

  /** A tech by its property name (the test map's techs have display names). */
  private TechAdvance tech(final String property) {
    return gameData.getTechnologyFrontier().getAdvanceByProperty(property);
  }

  private void capture(final Territory t, final Unit apc, final GamePlayer captor) {
    gameData.performChange(ChangeFactory.changeOwner(t, captor));
    gameData.performChange(ChangeFactory.changeOwner(java.util.List.of(apc), captor, t));
  }

  private int count(final Territory t, final UnitType type) {
    return t.getUnitCollection().countMatches(Matches.unitIsOfType(type));
  }

  private void giveTech(final GamePlayer player, final String techName, final int apcBonus) {
    final TechAdvance tech = tech(techName);
    TechAbilityAttachment ability = TechAbilityAttachment.get(tech);
    if (ability == null) {
      ability = new TechAbilityAttachment(Constants.TECH_ABILITY_ATTACHMENT_NAME, tech, gameData);
      tech.addAttachment(Constants.TECH_ABILITY_ATTACHMENT_NAME, ability);
    }
    set(ability, "apcLimitBonus", String.valueOf(apcBonus));
    TechTracker.addAdvance(player, newDelegateBridge(player), tech);
  }

  private PlayerAttachmentSetter playerAttachment(final GamePlayer player) {
    PlayerAttachment attachment = PlayerAttachment.get(player);
    if (attachment == null) {
      attachment = new PlayerAttachment(Constants.PLAYER_ATTACHMENT_NAME, player, gameData);
      player.addAttachment(Constants.PLAYER_ATTACHMENT_NAME, attachment);
    }
    return new PlayerAttachmentSetter(attachment);
  }

  private record PlayerAttachmentSetter(PlayerAttachment attachment) {
    void getPropertyOrThrowUnchecked(final String property, final String value) {
      set(attachment, property, value);
    }
  }

  private void apc(final UnitType type, final String property, final String value) {
    set(
        ApcAttachment.get(type)
            .orElseGet(
                () -> {
                  final ApcAttachment a =
                      new ApcAttachment(ApcAttachment.ATTACHMENT_NAME, type, gameData);
                  type.addAttachment(ApcAttachment.ATTACHMENT_NAME, a);
                  return a;
                }),
        property,
        value);
  }

  private static void set(
      final DefaultAttachment attachment, final String property, final String value) {
    try {
      attachment.getPropertyOrThrow(property).setValue(value);
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
