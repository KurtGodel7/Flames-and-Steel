package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.armour;
import static games.strategy.triplea.delegate.GameDataTestUtil.british;
import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.GameDataTestUtil.russians;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.PlayerAttachment;
import games.strategy.triplea.xml.TestMapGameData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Flames and Steel: the playerAttachment option "sellsAlliedUnitsAtEndTurn". */
class AlliedUnitSaleTest {
  private final GameData gameData = TestMapGameData.REVISED.getGameData();
  private final GamePlayer russians = russians(gameData);
  private final GamePlayer british = british(gameData);
  private final GamePlayer germans = germans(gameData);
  private final Territory karelia = territory("Karelia S.S.R.", gameData);
  private final Resource pus = gameData.getResourceList().getResourceOrThrow(Constants.PUS);

  @BeforeEach
  void setUp() {
    removeFrom(karelia, karelia.getUnits());
  }

  @Test
  void disabledByDefault() {
    addTo(karelia, infantry(gameData).create(2, british));
    final int before = russians.getResources().getQuantity(pus);

    AlliedUnitSale.sellAlliedUnits(newDelegateBridge(russians), russians);

    assertThat(karelia.getUnitCollection().size(), is(2));
    assertThat(russians.getResources().getQuantity(pus), is(before));
  }

  @Test
  void sellsAlliedUnitsAtTheirPurchaseCost() {
    enableSale();
    addTo(karelia, infantry(gameData).create(2, british));
    addTo(karelia, armour(gameData).create(1, british));
    addTo(karelia, infantry(gameData).create(3, russians));
    final int russianPus = russians.getResources().getQuantity(pus);
    final int britishPus = british.getResources().getQuantity(pus);

    AlliedUnitSale.sellAlliedUnits(newDelegateBridge(russians), russians);

    // revised: infantry 3 PUs, armour 5 PUs
    assertThat(russians.getResources().getQuantity(pus), is(russianPus + 2 * 3 + 5));
    assertThat(british.getResources().getQuantity(pus), is(britishPus));
    assertThat(karelia.getUnitCollection().countMatches(Matches.unitIsOwnedBy(british)), is(0));
    assertThat(karelia.getUnitCollection().countMatches(Matches.unitIsOwnedBy(russians)), is(3));
  }

  @Test
  void ignoresTerritoryNotOwnedBySeller() {
    enableSale();
    gameData.performChange(ChangeFactory.changeOwner(karelia, british));
    addTo(karelia, infantry(gameData).create(2, british));

    AlliedUnitSale.sellAlliedUnits(newDelegateBridge(russians), russians);

    assertThat(karelia.getUnitCollection().countMatches(Matches.unitIsOwnedBy(british)), is(2));
  }

  @Test
  void ignoresEnemyUnits() {
    enableSale();
    addTo(karelia, infantry(gameData).create(2, germans));

    AlliedUnitSale.sellAlliedUnits(newDelegateBridge(russians), russians);

    assertThat(karelia.getUnitCollection().countMatches(Matches.unitIsOwnedBy(germans)), is(2));
  }

  private void enableSale() {
    PlayerAttachment attachment = PlayerAttachment.get(russians);
    if (attachment == null) {
      attachment = new PlayerAttachment(Constants.PLAYER_ATTACHMENT_NAME, russians, gameData);
      russians.addAttachment(Constants.PLAYER_ATTACHMENT_NAME, attachment);
    }
    gameData.performChange(
        ChangeFactory.attachmentPropertyChange(attachment, true, "sellsAlliedUnitsAtEndTurn"));
  }
}
