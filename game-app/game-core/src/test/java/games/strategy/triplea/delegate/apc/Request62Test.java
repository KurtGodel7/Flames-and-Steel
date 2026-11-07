package games.strategy.triplea.delegate.apc;

import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.framework.GameDataManager;
import games.strategy.triplea.attachments.PlayerAttachment;
import games.strategy.triplea.delegate.GenericTechAdvance;
import games.strategy.triplea.delegate.TechAdvance;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Request 62: the player chooses the sea zone an SPC's ships go to; the choice is kept. */
class Request62Test {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private static final String KATTEGAT = "Sea Zone 75 Kattegat";
  private static final String HELGOLAND = "Sea Zone 76 Helgoland Bight";
  private GameData data;
  private GamePlayer germans;
  private Territory hamburg;
  private Unit spc;
  private IDelegateBridge bridge;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = games.strategy.triplea.delegate.air.BasedMap.load();
    germans = data.getPlayerList().getPlayerId("Germans");
    hamburg = territory("Hamburg");
    spc = type("apc").create(1, germans).get(0);
    data.performChange(ChangeFactory.addUnits(hamburg, List.of(spc)));
    grant("TypeXXIUboat");
    grant("Panther");
    bridge = newDelegateBridge(germans);
  }

  private Territory territory(final String name) {
    return data.getMap().getTerritoryOrThrow(name);
  }

  private UnitType type(final String name) {
    return data.getUnitTypeList().getUnitTypeOrThrow(name);
  }

  private void grant(final String tech) {
    final TechAdvance advance = data.getTechnologyFrontier().getAdvanceByName(tech);
    data.performChange(
        advance instanceof GenericTechAdvance g && g.getAdvance() == null
            ? ChangeFactory.genericTechChange(
                germans.getTechAttachment(), true, advance.getProperty())
            : ChangeFactory.attachmentPropertyChange(
                germans.getTechAttachment(), "true", advance.getProperty()));
    data.getTechTracker().clearCache();
  }

  private int uboats(final String zone) {
    return territory(zone)
        .getUnitCollection()
        .countMatches(u -> u.getType().getName().equals("typeXXI") && u.isOwnedBy(germans));
  }

  /** Two SPC Output steps: Type XXI comes one every 2 turns. */
  private void twoOutputs() {
    Apcs.produce(bridge, germans);
    Apcs.produce(bridge, germans);
  }

  @Test
  void hamburgOffersBothSeaZones() {
    assertThat(
        Apcs.seaZonesNextTo(hamburg, data.getMap()).stream().map(Territory::getName).toList(),
        is(List.of(KATTEGAT, HELGOLAND)));
    // nothing chosen yet: the first by name, as before
    assertThat(
        Apcs.shipsGoTo(spc, hamburg, germans, data.getMap()).map(Territory::getName),
        is(Optional.of(KATTEGAT)));
  }

  @Test
  void theChosenSeaZoneGetsTheShipsAndIsKeptNextTurn() {
    final int before = uboats(HELGOLAND);
    final int kattegatBefore = uboats(KATTEGAT);
    Apcs.setProduction(
        bridge, germans, Map.of(spc, type("typeXXI")), Map.of(spc, territory(HELGOLAND)));
    twoOutputs();
    assertThat(uboats(HELGOLAND), is(before + 1));
    // next turn the dialog sends the type only: the stored zone stays
    Apcs.setProduction(bridge, germans, Map.of(spc, type("typeXXI")));
    assertThat(Apcs.getSeaZone(spc, germans).map(Territory::getName), is(Optional.of(HELGOLAND)));
    twoOutputs();
    assertThat(uboats(HELGOLAND), is(before + 2));
    assertThat(uboats(KATTEGAT), is(kattegatBefore));
  }

  @Test
  void anSpcWithOneSeaZoneSendsShipsThere() {
    final Territory oneSea =
        data.getMap().getTerritories().stream()
            .filter(t -> !t.isWater() && t.isOwnedBy(germans))
            .filter(t -> Apcs.seaZonesNextTo(t, data.getMap()).size() == 1)
            .findFirst()
            .orElseThrow();
    final Unit other = type("apc").create(1, germans).get(0);
    data.performChange(ChangeFactory.addUnits(oneSea, List.of(other)));
    final Territory zone = Apcs.seaZonesNextTo(oneSea, data.getMap()).get(0);
    final long before =
        zone.getUnitCollection().countMatches(u -> u.getType().getName().equals("typeXXI"));
    Apcs.setProduction(bridge, germans, Map.of(other, type("typeXXI")), Map.of(other, zone));
    twoOutputs();
    assertThat(
        zone.getUnitCollection().countMatches(u -> u.getType().getName().equals("typeXXI")),
        is((int) before + 1));
  }

  @Test
  void switchingToTanksClearsTheStoredSeaZone() {
    Apcs.setProduction(
        bridge, germans, Map.of(spc, type("typeXXI")), Map.of(spc, territory(HELGOLAND)));
    Apcs.setProduction(bridge, germans, Map.of(spc, type("panther")), Map.of());
    assertThat(Apcs.getSeaZone(spc, germans), is(Optional.empty()));
    assertThat(
        PlayerAttachment.get(germans).getApcSeaZone().containsKey(spc.getId().toString()),
        is(false));
  }

  @Test
  void aSeaZoneNotNextToTheSpcIsRefused() {
    assertThat(
        Apcs.validateSeaZones(
                germans, Map.of(spc, territory("Sea Zone 86 Baltic Sea - South")), data.getMap())
            .isPresent(),
        is(
            Apcs.seaZonesNextTo(hamburg, data.getMap()).stream()
                .noneMatch(t -> t.getName().equals("Sea Zone 86 Baltic Sea - South"))));
    assertThat(
        Apcs.validateSeaZones(germans, Map.of(spc, territory(HELGOLAND)), data.getMap()),
        is(Optional.empty()));
  }

  @Test
  void theChoiceSurvivesSaveAndReload() throws Exception {
    Apcs.setProduction(
        bridge, germans, Map.of(spc, type("typeXXI")), Map.of(spc, territory(HELGOLAND)));
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    GameDataManager.saveGame(out, data);
    final GameData loaded =
        GameDataManager.loadGame(new ByteArrayInputStream(out.toByteArray())).orElseThrow();
    final GamePlayer g = loaded.getPlayerList().getPlayerId("Germans");
    assertThat(PlayerAttachment.get(g).getApcSeaZone().get(spc.getId().toString()), is(HELGOLAND));
  }
}
