package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.artillery;
import static games.strategy.triplea.delegate.GameDataTestUtil.assertMoveError;
import static games.strategy.triplea.delegate.GameDataTestUtil.british;
import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static games.strategy.triplea.delegate.GameDataTestUtil.move;
import static games.strategy.triplea.delegate.GameDataTestUtil.moveDelegate;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.GameDataTestUtil.russians;
import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.DefaultAttachment;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.MunitionsAttachment;
import games.strategy.triplea.attachments.PlayerAttachment;
import games.strategy.triplea.delegate.battle.BattleTracker;
import games.strategy.triplea.settings.AbstractClientSettingTestCase;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel lend-lease munitions on the Revised test map. Artillery stands in for the
 * munitions unit (worth 1 PU). The British and Soviet relationship keeps British land units off
 * Soviet land, as SovietCooperation does.
 */
class MunitionsTest extends AbstractClientSettingTestCase {
  private final GameData gameData = TestMapGameData.REVISED.getGameData();
  private final GamePlayer british = british(gameData);
  private final GamePlayer russians = russians(gameData);
  private final GamePlayer germans = germans(gameData);
  private UnitType munitions;
  private Territory britishLand;
  private Territory sovietLand;

  @BeforeEach
  void setUp() {
    munitions = artillery(gameData);
    set(attachment(munitions), "munitionsValue", "1");
    set(
        gameData
            .getRelationshipTracker()
            .getRelationshipType(british, russians)
            .getRelationshipTypeAttachment(),
        "canMoveLandUnitsOverOwnedLand",
        "false");
    // a British land territory next to Soviet land
    for (final Territory t : gameData.getMap().getTerritoriesOwnedBy(british)) {
      if (t.isWater()) {
        continue;
      }
      final var soviet =
          gameData.getMap().getNeighbors(t).stream()
              .filter(n -> !n.isWater() && n.isOwnedBy(russians))
              .findFirst();
      if (soviet.isPresent()) {
        britishLand = t;
        sovietLand = soviet.get();
        break;
      }
    }
    removeFrom(britishLand, britishLand.getUnits());
    removeFrom(sovietLand, sovietLand.getUnits());
  }

  @Test
  void munitionsMayEnterSovietLandButOtherBritishUnitsMayNot() {
    final List<Unit> crates = munitions.create(2, british);
    final List<Unit> soldiers = infantry(gameData).create(1, british);
    addTo(britishLand, crates);
    addTo(britishLand, soldiers);
    final IDelegateBridge bridge = newDelegateBridge(british);
    advanceToStep(bridge, "NonCombatMove");
    moveDelegate(gameData).setDelegateBridgeAndPlayer(bridge);
    moveDelegate(gameData).start();
    final Route route = new Route(britishLand, sovietLand);

    assertMoveError(soldiers, route);
    move(crates, route);

    assertThat(
        sovietLand.getUnitCollection().countMatches(MunitionsAttachment::isMunitions), is(2));
  }

  @Test
  void munitionsOnSovietLandBecomeSovietPus() {
    playerOption(russians, "convertsMunitions", "true");
    addTo(sovietLand, munitions.create(3, british));
    final int before = russians.getResources().getQuantity(Constants.PUS);
    // the stand-in (artillery) also stands elsewhere on Soviet land on this map
    final int onSovietLand =
        gameData.getMap().getTerritoriesOwnedBy(russians).stream()
            .filter(t -> !t.isWater())
            .mapToInt(t -> t.getUnitCollection().countMatches(MunitionsAttachment::isMunitions))
            .sum();

    Munitions.convert(newDelegateBridge(russians), russians);

    assertThat(onSovietLand >= 3, is(true));
    assertThat(russians.getResources().getQuantity(Constants.PUS), is(before + onSovietLand));
    assertThat(
        sovietLand.getUnitCollection().countMatches(MunitionsAttachment::isMunitions), is(0));
  }

  @Test
  void saleOfAlliedUnitsLeavesMunitionsAlone() {
    playerOption(russians, "sellsAlliedUnitsAtEndTurn", "true");
    addTo(sovietLand, munitions.create(3, british));

    AlliedUnitSale.sellAlliedUnits(newDelegateBridge(russians), russians);

    assertThat(
        sovietLand.getUnitCollection().countMatches(MunitionsAttachment::isMunitions), is(3));
  }

  @Test
  void capturedMunitionsBecomeTheCaptorsPus() {
    addTo(britishLand, munitions.create(2, british));
    final int before = germans.getResources().getQuantity(Constants.PUS);

    new BattleTracker().takeOver(britishLand, germans, newDelegateBridge(germans), null, null);

    assertThat(germans.getResources().getQuantity(Constants.PUS), is(before + 2));
    assertThat(
        britishLand.getUnitCollection().countMatches(MunitionsAttachment::isMunitions), is(0));
  }

  private void playerOption(final GamePlayer player, final String option, final String value) {
    PlayerAttachment attachment = PlayerAttachment.get(player);
    if (attachment == null) {
      attachment = new PlayerAttachment(Constants.PLAYER_ATTACHMENT_NAME, player, gameData);
      player.addAttachment(Constants.PLAYER_ATTACHMENT_NAME, attachment);
    }
    set(attachment, option, value);
  }

  private MunitionsAttachment attachment(final UnitType type) {
    return MunitionsAttachment.get(type)
        .orElseGet(
            () -> {
              final MunitionsAttachment a =
                  new MunitionsAttachment(MunitionsAttachment.ATTACHMENT_NAME, type, gameData);
              type.addAttachment(MunitionsAttachment.ATTACHMENT_NAME, a);
              return a;
            });
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
