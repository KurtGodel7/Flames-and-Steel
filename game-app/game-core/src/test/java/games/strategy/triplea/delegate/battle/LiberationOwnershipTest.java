package games.strategy.triplea.delegate.battle;

import static games.strategy.triplea.delegate.GameDataTestUtil.british;
import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.GameDataTestUtil.russians;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.PlayerAttachment;
import games.strategy.triplea.delegate.OriginalOwnerTracker;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Flames and Steel: the playerAttachment option "liberatedTerritoryGoesTo". */
class LiberationOwnershipTest {
  private final GameData gameData = TestMapGameData.REVISED.getGameData();
  private final GamePlayer russians = russians(gameData);
  private final GamePlayer germans = germans(gameData);
  private final GamePlayer british = british(gameData);
  private final Territory moscow = territory("Russia", gameData);
  private final Territory karelia = territory("Karelia S.S.R.", gameData);
  private final Territory archangel = territory("Archangel", gameData);

  @BeforeEach
  void setUp() {
    for (final Territory t : List.of(moscow, karelia, archangel)) {
      removeFrom(t, t.getUnits());
      // normally recorded by InitializationDelegate at game start
      gameData.performChange(OriginalOwnerTracker.addOriginalOwnerChange(t, russians));
    }
  }

  @Test
  void engineDefaultGivesTerritoryToLiberatorWhileCapitalIsLost() {
    captureByGermans(moscow, karelia);

    liberateByBritish(karelia);

    assertThat(karelia.getOwner(), is(british));
  }

  @Test
  void originalOwnerModeReturnsTerritoryEvenWhileCapitalIsLost() {
    setLiberationMode(PlayerAttachment.LIBERATED_TO_ORIGINAL_OWNER);
    captureByGermans(moscow, karelia);

    liberateByBritish(karelia);

    assertThat(karelia.getOwner(), is(russians));
  }

  @Test
  void liberatorModeKeepsTerritoryEvenWhileCapitalIsHeld() {
    setLiberationMode(PlayerAttachment.LIBERATED_TO_LIBERATOR);
    captureByGermans(karelia);

    liberateByBritish(karelia);

    assertThat(karelia.getOwner(), is(british));
  }

  @Test
  void liberatorModeKeepsCapitalAndHandsNothingBack() {
    setLiberationMode(PlayerAttachment.LIBERATED_TO_LIBERATOR);
    captureByGermans(moscow, karelia);
    liberateByBritish(karelia);

    liberateByBritish(moscow);

    assertThat(moscow.getOwner(), is(british));
    assertThat(karelia.getOwner(), is(british));
  }

  @Test
  void defaultModeLiberatedCapitalHandsBackAlliedHeldTerritory() {
    captureByGermans(moscow, karelia);
    liberateByBritish(karelia);

    liberateByBritish(moscow);

    assertThat(moscow.getOwner(), is(russians));
    assertThat(karelia.getOwner(), is(russians));
  }

  private void setLiberationMode(final String mode) {
    PlayerAttachment attachment = PlayerAttachment.get(russians);
    if (attachment == null) {
      attachment = new PlayerAttachment(Constants.PLAYER_ATTACHMENT_NAME, russians, gameData);
      russians.addAttachment(Constants.PLAYER_ATTACHMENT_NAME, attachment);
    }
    gameData.performChange(
        ChangeFactory.attachmentPropertyChange(attachment, mode, "liberatedTerritoryGoesTo"));
  }

  private void captureByGermans(final Territory... territories) {
    for (final Territory t : territories) {
      gameData.performChange(ChangeFactory.changeOwner(t, germans));
    }
  }

  private void liberateByBritish(final Territory t) {
    new BattleTracker().takeOver(t, british, newDelegateBridge(british), null, null);
  }
}
