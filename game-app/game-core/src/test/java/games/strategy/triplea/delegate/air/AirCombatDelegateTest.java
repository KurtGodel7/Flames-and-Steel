package games.strategy.triplea.delegate.air;

import static games.strategy.triplea.delegate.GameDataTestUtil.aaGun;
import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.battleship;
import static games.strategy.triplea.delegate.GameDataTestUtil.bomber;
import static games.strategy.triplea.delegate.GameDataTestUtil.british;
import static games.strategy.triplea.delegate.GameDataTestUtil.fighter;
import static games.strategy.triplea.delegate.GameDataTestUtil.germans;
import static games.strategy.triplea.delegate.GameDataTestUtil.infantry;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.GameDataTestUtil.russians;
import static games.strategy.triplea.delegate.GameDataTestUtil.submarine;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static games.strategy.triplea.delegate.GameDataTestUtil.transport;
import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.DefaultAttachment;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.delegate.AirCombatDelegate;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.data.CasualtyList;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel air combat on the Revised test map. Sea zones stand in for zone boxes. Every
 * remainder die rolls 0 (a hit), and casualties are the offered defaults. Unit values used: fighter
 * dogfight 3, bomber attack 4 and bombing damage 5.
 */
class AirCombatDelegateTest {
  private final GameData gameData = TestMapGameData.REVISED.getGameData();
  private final GamePlayer british = british(gameData);
  private final GamePlayer germans = germans(gameData);
  private final Territory front = territory("1 Sea Zone", gameData);
  private final Territory naval = territory("2 Sea Zone", gameData);
  private final Territory bombGermany = territory("3 Sea Zone", gameData);
  private final Territory karelia = territory("Karelia S.S.R.", gameData);
  private final Territory seaZone = territory("5 Sea Zone", gameData);
  private final Territory germany = territory("Germany", gameData);
  private UnitType fighter;
  private UnitType bomber;
  private AirCombatDelegate delegate;

  @BeforeEach
  void setUp() {
    gameData.getProperties().set(Constants.STICKY_HITS, true);
    for (final Territory t : List.of(front, naval, bombGermany, karelia, seaZone)) {
      removeFrom(t, t.getUnits());
    }
    removeFrom(germany, germany.getUnitCollection().getMatches(Matches.unitIsAaForAnything()));
    zone(front, "zoneType", "land");
    zone(front, "coveredTerritories", karelia.getName());
    zone(naval, "zoneType", "coastal");
    zone(naval, "coveredTerritories", seaZone.getName());
    zone(bombGermany, "zoneType", "bomb");
    zone(bombGermany, "bombedPlayer", germans.getName());
    fighter = fighter(gameData);
    bomber = bomber(gameData);
    air(fighter, "fighter", "true");
    air(fighter, "dogfight", "3");
    air(bomber, "dogfight", "1");
    air(bomber, "bombingDamage", "5");
    delegate = new AirCombatDelegate();
    delegate.initialize("airCombat", "Air combat");
    for (final String step :
        List.of("airDogfights", "germanAirTargets", "britishAirTargets", "airStrikes")) {
      gameData
          .getSequence()
          .addStep(
              new GameStep(
                  step,
                  step,
                  step.startsWith("british") ? british : germans,
                  delegate,
                  gameData,
                  new Properties()));
    }
  }

  @Test
  void lowLuckRollsOnlyTheRemainder() {
    final IDelegateBridge bridge = bridge(germans);
    when(bridge.getRandom(anyInt(), any(), any(), anyString())).thenReturn(0, 3);

    assertThat(AirCombat.lowLuck(bridge, germans, 12, "").hits(), is(2));
    assertThat(AirCombat.lowLuck(bridge, germans, 7, "").hits(), is(2)); // 1 + die 0 < 1
    assertThat(AirCombat.lowLuck(bridge, germans, 8, "").hits(), is(1)); // 1 + die 3 >= 2
  }

  @Test
  void dogfightLastsTwoRoundsAndEveryAircraftFires() {
    addTo(front, fighter.create(2, british)); // 2 x 3 = 6: one sure hit
    addTo(front, bomber.create(3, germans)); // 3 x 1 = 3: remainder die 0 hits

    runStep("airDogfights", germans);

    // round 1: one German bomber and one British fighter fall; round 2: the same again
    assertThat(count(front, germans), is(1));
    assertThat(count(front, british), is(0));
  }

  @Test
  void landStrikeHitsLandUnits() {
    addTo(front, bomber.create(3, germans)); // 3 x 4 = 12: two hits
    addTo(karelia, infantry(gameData).create(3, russians(gameData)));

    strike(germans, new TargetAssignment(front, bomber, 3, land(karelia)));

    assertThat(count(karelia, russians(gameData)), is(1));
    assertThat(count(front, germans), is(3));
  }

  @Test
  void hitsLandWithTransportCasualtiesRestricted() {
    // Flames and Steel turns this option on; the casualty selector then counts the hits passed
    // in rather than the dice, so the air phase must pass them (they were lost before)
    gameData.getProperties().set(Constants.TRANSPORT_CASUALTIES_RESTRICTED, true);
    addTo(front, bomber.create(3, germans)); // 3 x 4 = 12: two hits
    addTo(karelia, infantry(gameData).create(3, russians(gameData)));

    strike(germans, new TargetAssignment(front, bomber, 3, land(karelia)));

    assertThat(count(karelia, russians(gameData)), is(1));
    gameData.getProperties().set(Constants.TRANSPORT_CASUALTIES_RESTRICTED, false);
  }

  @Test
  void aaFiresBeforeTheBombers() {
    addTo(front, bomber.create(1, germans));
    addTo(karelia, infantry(gameData).create(3, russians(gameData)));
    addTo(karelia, aaGun(gameData).create(1, russians(gameData)));

    strike(germans, new TargetAssignment(front, bomber, 1, land(karelia)));

    // the AA remainder die hits and the only bomber is gone before it can attack
    assertThat(count(front, germans), is(0));
    assertThat(count(karelia, russians(gameData)), is(4));
  }

  @Test
  void damagedShipsFireBackAtTheirAttackers() {
    addTo(naval, bomber.create(3, germans)); // 12: two hits on the 2-hit-point battleship
    addTo(seaZone, battleship(gameData).create(1, british));

    strike(
        germans, new TargetAssignment(naval, bomber, 3, new Target(seaZone, TargetKind.SURFACE)));

    assertThat(count(seaZone, british), is(0));
    // the sunk battleship fires back with its defense and the remainder die hits
    assertThat(count(naval, germans), is(2));
  }

  @Test
  void transportsAreLostOnceTheirEscortIsSunk() {
    addTo(naval, bomber.create(3, germans)); // 12: two hits sink the battleship
    addTo(seaZone, battleship(gameData).create(1, british));
    addTo(seaZone, transport(gameData).create(2, british));

    strike(
        germans, new TargetAssignment(naval, bomber, 3, new Target(seaZone, TargetKind.SURFACE)));

    assertThat(count(seaZone, british), is(0));
  }

  @Test
  void transportsAreNeverCasualtiesWhileEscorted() {
    addTo(naval, bomber.create(1, germans)); // 4: remainder die hits once
    addTo(seaZone, battleship(gameData).create(1, british));
    addTo(seaZone, transport(gameData).create(2, british));

    strike(
        germans, new TargetAssignment(naval, bomber, 1, new Target(seaZone, TargetKind.SURFACE)));

    // the hit damaged the battleship; both transports are untouched
    assertThat(seaZone.getUnitCollection().countMatches(Matches.unitIsSeaTransport()), is(2));
  }

  @Test
  void onlySubHuntersMayAttackSubmarines() {
    addTo(naval, bomber.create(1, germans));
    addTo(seaZone, submarine(gameData).create(1, british));
    final Target subs = new Target(seaZone, TargetKind.SUBS);

    assertThat(AirCombat.getTargets(germans, naval).contains(subs), is(true));
    assertThat(
        assign("germanAirTargets", germans, new TargetAssignment(naval, bomber, 1, subs)),
        is(notNullValue()));

    air(bomber, "canAttackSubs", "true");
    assertThat(
        assign("germanAirTargets", germans, new TargetAssignment(naval, bomber, 1, subs)),
        is(nullValue()));
  }

  @Test
  void strategicBombingCostsPusUpToTwiceTheTerritoryValue() {
    final int startPus = germans.getResources().getQuantity(Constants.PUS);
    addTo(bombGermany, bomber.create(10, british)); // 10 x 5 = 50 damage

    strike(
        british,
        new TargetAssignment(bombGermany, bomber, 10, new Target(germany, TargetKind.STRATEGIC)));

    final int cap = AirCombat.bombingCap(germany);
    assertThat(germans.getResources().getQuantity(Constants.PUS), is(Math.max(0, startPus - cap)));
  }

  @Test
  void fightersCannotBeSentAgainstTargets() {
    addTo(front, fighter.create(1, germans));
    addTo(karelia, infantry(gameData).create(1, russians(gameData)));

    assertThat(AirCombat.getBombers(germans, front).isEmpty(), is(true));
  }

  private void strike(final GamePlayer attacker, final TargetAssignment... assignments) {
    final String targetStep = attacker.equals(british) ? "britishAirTargets" : "germanAirTargets";
    assertThat(assign(targetStep, attacker, assignments), is(nullValue()));
    runStep("airStrikes", attacker);
  }

  private String assign(
      final String step, final GamePlayer player, final TargetAssignment... assignments) {
    final IDelegateBridge bridge = bridge(player);
    advanceToStep(bridge, step);
    delegate.setDelegateBridgeAndPlayer(bridge);
    delegate.start();
    final String error = delegate.assignTargets(List.of(assignments));
    delegate.end();
    return error;
  }

  private void runStep(final String step, final GamePlayer player) {
    final IDelegateBridge bridge = bridge(player);
    advanceToStep(bridge, step);
    delegate.setDelegateBridgeAndPlayer(bridge);
    delegate.start();
    delegate.end();
  }

  /** A bridge whose remainder dice always roll 0 and whose players take the offered casualties. */
  private IDelegateBridge bridge(final GamePlayer player) {
    final IDelegateBridge bridge = newDelegateBridge(player);
    when(bridge.getRandom(anyInt(), any(), any(), anyString())).thenReturn(0);
    when(bridge
            .getRemotePlayer()
            .selectCasualties(
                any(),
                any(),
                anyInt(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean()))
        .thenAnswer(
            invocation -> new CasualtyDetails(invocation.<CasualtyList>getArgument(10), true));
    return bridge;
  }

  private static Target land(final Territory t) {
    return new Target(t, TargetKind.LAND);
  }

  private static int count(final Territory t, final GamePlayer owner) {
    return t.getUnitCollection().countMatches(Matches.unitIsOwnedBy(owner));
  }

  private void zone(final Territory territory, final String property, final String value) {
    set(
        AirZoneAttachment.get(territory)
            .orElseGet(
                () -> {
                  final AirZoneAttachment a =
                      new AirZoneAttachment(AirZoneAttachment.ATTACHMENT_NAME, territory, gameData);
                  territory.addAttachment(AirZoneAttachment.ATTACHMENT_NAME, a);
                  return a;
                }),
        property,
        value);
  }

  private void air(final UnitType type, final String property, final String value) {
    set(
        AirUnitAttachment.get(type)
            .orElseGet(
                () -> {
                  final AirUnitAttachment a =
                      new AirUnitAttachment(AirUnitAttachment.ATTACHMENT_NAME, type, gameData);
                  type.addAttachment(AirUnitAttachment.ATTACHMENT_NAME, a);
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
