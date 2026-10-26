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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.DefaultAttachment;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.player.Player;
import games.strategy.triplea.Constants;
import games.strategy.triplea.attachments.AirUnitAttachment;
import games.strategy.triplea.delegate.AirCombatDelegate;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.delegate.data.CasualtyDetails;
import games.strategy.triplea.delegate.data.CasualtyList;
import games.strategy.triplea.xml.TestMapGameData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flames and Steel air combat on the Revised test map, with aircraft in the air over their targets
 * (request 38): a hidden "Air - Airborne" territory is added to the map. Every
 * remainder die rolls 0 (a hit), and casualties are the offered defaults. Unit values used: fighter
 * dogfight 3, bomber attack 4 and bombing damage 5.
 */
class AirCombatDelegateTest {
  private final GameData gameData = TestMapGameData.REVISED.getGameData();
  private final GamePlayer british = british(gameData);
  private final GamePlayer germans = germans(gameData);
  private final Territory front = airborneTerritory(gameData);
  private final Territory naval = front;
  private final Territory bombGermany = front;
  private final Territory karelia = territory("Karelia S.S.R.", gameData);
  private final Territory seaZone = territory("5 Sea Zone", gameData);
  private final Territory germany = territory("Germany", gameData);
  private UnitType fighter;
  private UnitType bomber;
  private AirCombatDelegate delegate;
  // the remainder die every roll gets: 0 always hits, 5 always misses
  private int die = 0;
  // the choices of the coming air battles, by nation
  private final Map<GamePlayer, List<TargetAssignment>> bombers = new LinkedHashMap<>();
  private final Map<GamePlayer, List<TargetAssignment>> fighters = new LinkedHashMap<>();

  /** The map's hidden airborne territory, added for the test. */
  private static Territory airborneTerritory(final GameData data) {
    final Territory existing = data.getMap().getTerritoryOrNull(AirBases.DEFAULT_AIRBORNE);
    if (existing != null) {
      return existing;
    }
    final Territory t = new Territory(AirBases.DEFAULT_AIRBORNE, false, data);
    data.getMap().addTerritory(t);
    return t;
  }

  @BeforeEach
  void setUp() {
    gameData.getProperties().set(Constants.STICKY_HITS, true);
    for (final Territory t : List.of(front, karelia, seaZone)) {
      removeFrom(t, t.getUnits());
    }
    removeFrom(germany, germany.getUnitCollection().getMatches(Matches.unitIsAaForAnything()));
    fighter = fighter(gameData);
    bomber = bomber(gameData);
    air(fighter, "fighter", "true");
    air(fighter, "dogfight", "3");
    air(bomber, "dogfight", "1");
    air(bomber, "bombingDamage", "5");
    delegate = new AirCombatDelegate();
    delegate.initialize("airCombat", "Air combat");
    gameData
        .getSequence()
        .addStep(new GameStep("airStrikes", "airStrikes", null, delegate, gameData, new Properties()));
    // request 30: the older tests send more than 4 aircraft to one place
    gameData.getProperties().set(AirCover.CAP_BOMBERS, 99);
    gameData.getProperties().set(AirCover.CAP_FIGHTERS, 99);
  }

  @Test
  void lowLuckRollsOnlyTheRemainder() {
    final IDelegateBridge bridge = bridge(germans);
    when(bridge.getRandom(anyInt(), any(), any(), anyString())).thenReturn(0, 3);

    assertThat(AirCombat.lowLuck(bridge, germans, 12, "").hits(), is(2));
    assertThat(AirCombat.lowLuck(bridge, germans, 7, "").hits(), is(2)); // 1 + die 0 < 1
    assertThat(AirCombat.lowLuck(bridge, germans, 8, "").hits(), is(1)); // 1 + die 3 >= 2
  }

  // ---- request 27: dogfight (fighters only), interception, aborts, AA on aborted bombers

  @Test
  void bombersAreNeverDogfightCasualties() {
    noInterception();
    addTo(front, fighter.create(2, british)); // 6: one hit a round
    addTo(front, fighter.create(1, germans)); // 3: one hit a round
    addTo(front, bomber.create(3, germans));
    addTo(karelia, infantry(gameData).create(3, russians(gameData)));

    targets(germans, new TargetAssignment(front, bomber, 3, land(karelia)));
    cover(germans, new TargetAssignment(front, fighter, 1, over(karelia)));
    cover(british, new TargetAssignment(front, fighter, 2, over(karelia)));
    runStep("airStrikes", germans);

    // round 1: the German fighter falls (never a bomber), one British fighter falls; round 2:
    // the Germans have no fighters left, so the dogfight ends
    assertThat(count(front, germans, fighter), is(0));
    assertThat(count(front, germans, bomber), is(3));
    assertThat(count(front, british, fighter), is(1));
  }

  @Test
  void theDogfightRoundsAreAGameProperty() {
    noInterception();
    addTo(front, fighter.create(2, british));
    addTo(front, fighter.create(2, germans));

    gameData.getProperties().set(AirCombat.DOGFIGHT_ROUNDS, 1);
    dogfightOverKarelia(2);
    assertThat(count(front, germans, fighter), is(1));
    assertThat(count(front, british, fighter), is(1));

    gameData.getProperties().set(AirCombat.DOGFIGHT_ROUNDS, 2);
    addTo(front, fighter.create(1, british));
    addTo(front, fighter.create(1, germans));
    dogfightOverKarelia(2);
    assertThat(count(front, germans, fighter), is(0));
    assertThat(count(front, british, fighter), is(0));
  }

  @Test
  void everySurvivingFighterInterceptsDamagedOrNot() {
    die = 5; // remainder dice miss: hits are exactly value / 6
    hitPoints(fighter, 2);
    air(fighter, "interception", "2");
    air(bomber, "airDefenseRolls", "0");
    final List<Unit> fighters = fighter.create(3, british);
    fighters.get(0).setHits(1); // damaged: still intercepts
    addTo(front, fighters);
    addTo(front, bomber.create(6, germans));
    addTo(karelia, infantry(gameData).create(9, russians(gameData)));

    targets(germans, new TargetAssignment(front, bomber, 6, land(karelia)));
    cover(british, new TargetAssignment(front, fighter, 3, over(karelia)));
    runStep("airStrikes", germans);

    // 3 x 2 = 6: one hit (two fighters alone would make 4: none); no share of the force
    assertThat(count(front, germans, bomber), is(5));
  }

  @Test
  void bombersFireBackAtInterceptors() {
    die = 5;
    air(fighter, "interception", "1"); // 1: no hit
    air(bomber, "airDefense", "1"); // 6 x 1 = 6: one hit
    addTo(front, fighter.create(1, british));
    addTo(front, bomber.create(6, germans));
    addTo(karelia, infantry(gameData).create(9, russians(gameData)));

    targets(germans, new TargetAssignment(front, bomber, 6, land(karelia)));
    cover(british, new TargetAssignment(front, fighter, 1, over(karelia)));
    runStep("airStrikes", germans);

    assertThat(count(front, british, fighter), is(0));
    assertThat(count(front, germans, bomber), is(6));
  }

  @Test
  void aSideWithoutBombersDrawsNoInterceptionFire() {
    air(bomber, "airDefense", "6");
    addTo(front, fighter.create(2, british)); // 6: one hit
    addTo(front, fighter.create(1, germans)); // 3: one hit
    gameData.getProperties().set(AirCombat.DOGFIGHT_ROUNDS, 1);

    cover(germans, new TargetAssignment(front, fighter, 1, over(karelia)));
    cover(british, new TargetAssignment(front, fighter, 2, over(karelia)));
    runStep("airStrikes", germans);

    // the dogfight only: the British have no bombers, the Germans none to intercept
    assertThat(count(front, germans, fighter), is(0));
    assertThat(count(front, british, fighter), is(1));
  }

  @Test
  void aDamagedBomberAbortsAndHealsAfterTheAirPhase() {
    hitPoints(bomber, 2);
    air(bomber, "airDefenseRolls", "0");
    addTo(front, fighter.create(1, british)); // interception 3: one hit
    final List<Unit> bombers = bomber.create(1, germans);
    addTo(front, bombers);
    addTo(karelia, infantry(gameData).create(3, russians(gameData)));

    targets(germans, new TargetAssignment(front, bomber, 1, land(karelia)));
    cover(british, new TargetAssignment(front, fighter, 1, over(karelia)));
    runStep("airStrikes", germans);

    assertThat(count(karelia, russians(gameData)), is(3)); // aborted: no strike
    assertThat(count(front, germans, bomber), is(1));
    assertThat(bombers.get(0).getHits(), is(0)); // healed at the end of the air phase
  }

  @Test
  void aBomberDamagedByAaAbortsItsLandStrike() {
    hitPoints(bomber, 2);
    addTo(front, bomber.create(1, germans));
    addTo(karelia, infantry(gameData).create(3, russians(gameData)));
    addTo(karelia, aaGun(gameData).create(1, russians(gameData)));

    strike(germans, new TargetAssignment(front, bomber, 1, land(karelia)));

    assertThat(count(front, germans, bomber), is(1)); // damaged, not destroyed
    assertThat(count(karelia, russians(gameData)), is(4)); // and it made no strike
  }

  @Test
  void aBomberDamagedByAaAbortsItsBombingRun() {
    hitPoints(bomber, 2);
    final int startPus = germans.getResources().getQuantity(Constants.PUS);
    addTo(bombGermany, bomber.create(2, british));
    addTo(germany, aaGun(gameData).create(1, germans)); // one hit

    strike(
        british,
        new TargetAssignment(bombGermany, bomber, 2, new Target(germany, TargetKind.STRATEGIC)));

    // one bomber damaged by AA and aborted: only the other bombs, for 5
    assertThat(germans.getResources().getQuantity(Constants.PUS), is(startPus - 5));
  }

  @Test
  void interceptorsInABombingZoneTurnBombersBack() {
    hitPoints(bomber, 2);
    air(bomber, "airDefenseRolls", "0");
    final int startPus = germans.getResources().getQuantity(Constants.PUS);
    addTo(bombGermany, bomber.create(2, british));
    addTo(bombGermany, fighter.create(1, germans)); // interception 3: one hit

    targets(
        british,
        new TargetAssignment(bombGermany, bomber, 2, new Target(germany, TargetKind.STRATEGIC)));
    cover(germans, new TargetAssignment(bombGermany, fighter, 1, over(germany)));
    runStep("airStrikes", british);

    assertThat(germans.getResources().getQuantity(Constants.PUS), is(startPus - 5));
    assertThat(count(bombGermany, british, bomber), is(2));
  }

  @Test
  void aaHitsTheBomberAbortedInInterceptionFirst() {
    hitPoints(bomber, 2);
    air(bomber, "airDefenseRolls", "0");
    air(fighter, "interception", "1"); // one hit: one bomber damaged and aborted
    addTo(front, fighter.create(1, british));
    addTo(front, bomber.create(2, germans));
    addTo(karelia, infantry(gameData).create(3, russians(gameData)));
    addTo(karelia, aaGun(gameData).create(1, russians(gameData))); // one hit

    targets(germans, new TargetAssignment(front, bomber, 2, land(karelia)));
    cover(british, new TargetAssignment(front, fighter, 1, over(karelia)));
    runStep("airStrikes", germans);

    // the AA hit finished the aborted bomber; the undamaged one struck (4: one hit)
    assertThat(count(front, germans, bomber), is(1));
    assertThat(count(karelia, russians(gameData)), is(3)); // 3 infantry - 1, plus the AA gun
  }

  @Test
  void aaHitsOnlyTheBombersAssignedToItsTarget() {
    hitPoints(bomber, 2);
    air(fighter, "interceptionRolls", "0"); // an air battle, but no interception hits
    final Territory archangel = territory("Archangel", gameData);
    removeFrom(archangel, archangel.getUnits());
    addTo(front, fighter.create(1, british));
    final List<Unit> bombers = bomber.create(2, germans);
    // damaged earlier; the first strike takes undamaged bombers first, so it goes to Archangel
    bombers.get(1).setHits(1);
    addTo(front, bombers);
    addTo(karelia, infantry(gameData).create(3, russians(gameData)));
    addTo(karelia, aaGun(gameData).create(1, russians(gameData)));
    addTo(archangel, infantry(gameData).create(3, russians(gameData)));

    strike(
        germans,
        new TargetAssignment(front, bomber, 1, land(karelia)),
        new TargetAssignment(front, bomber, 1, land(archangel)));

    // the Karelia AA hit its own undamaged bomber, which aborted; the damaged bomber sent to
    // Archangel was out of that AA's reach: both survive, neither strikes
    assertThat(count(front, germans, bomber), is(2));
    assertThat(count(karelia, russians(gameData)), is(4));
    assertThat(count(archangel, russians(gameData)), is(3));
  }

  @Test
  void aBomberWithNoTargetFacesNoAa() {
    addTo(front, bomber.create(1, germans));
    addTo(karelia, aaGun(gameData).create(1, russians(gameData)));

    runStep("airStrikes", germans);

    assertThat(count(front, germans, bomber), is(1));
  }

  @Test
  void stickyDamageCarriesFromTheDogfightIntoInterception() {
    gameData.getProperties().set(AirCombat.DOGFIGHT_ROUNDS, 1);
    hitPoints(fighter, 2);
    hitPoints(bomber, 5);
    addTo(front, fighter.create(2, germans));
    addTo(front, fighter.create(1, british)); // dogfight 3: one German fighter damaged
    addTo(front, bomber.create(1, british)); // fires back at 1: one hit
    addTo(karelia, infantry(gameData).create(1, germans));

    targets(british, new TargetAssignment(front, bomber, 1, land(karelia)));
    cover(germans, new TargetAssignment(front, fighter, 2, over(karelia)));
    cover(british, new TargetAssignment(front, fighter, 1, over(karelia)));
    runStep("airStrikes", germans);

    // the return-fire hit had to go to the fighter damaged in the dogfight: it is destroyed
    assertThat(count(front, germans, fighter), is(1));
  }

  // ---- request 30: one air battle per territory

  @Test
  void fightersOverOneTerritoryDoNotTouchBombersOverAnother() {
    hitPoints(bomber, 2);
    air(bomber, "airDefenseRolls", "0");
    final Territory archangel = territory("Archangel", gameData);
    removeFrom(archangel, archangel.getUnits());
    addTo(archangel, infantry(gameData).create(1, russians(gameData)));
    addTo(front, fighter.create(2, british)); // interception 6: would damage a bomber
    final List<Unit> bombers = bomber.create(2, germans);
    addTo(front, bombers);
    addTo(karelia, infantry(gameData).create(3, russians(gameData)));

    targets(germans, new TargetAssignment(front, bomber, 2, land(karelia)));
    cover(british, new TargetAssignment(front, fighter, 2, over(archangel)));
    runStep("airStrikes", germans);

    // no interception over Karelia: both bombers strike (8: two hits)
    assertThat(count(karelia, russians(gameData)), is(1));
    assertThat(count(front, germans, bomber), is(2));
  }

  @Test
  void unplacedFightersTakeNoPart() {
    hitPoints(bomber, 2);
    air(bomber, "airDefenseRolls", "0");
    addTo(front, fighter.create(2, british)); // in the front, but placed nowhere
    addTo(front, bomber.create(1, germans));
    addTo(karelia, infantry(gameData).create(3, russians(gameData)));

    targets(germans, new TargetAssignment(front, bomber, 1, land(karelia)));
    runStep("airStrikes", germans);

    assertThat(count(karelia, russians(gameData)), is(2)); // the bomber struck
  }

  @Test
  void aFifthBomberOverALandTerritoryIsRefused() {
    gameData.getProperties().set(AirCover.CAP_BOMBERS, 4);
    addTo(front, bomber.create(5, germans));
    addTo(karelia, infantry(gameData).create(1, russians(gameData)));

    assertThat(
        AirCover.capProblem(
                germans,
                List.of(new TargetAssignment(front, bomber, 5, land(karelia))),
                Map.of(),
                Map.of())
            .isPresent(),
        is(true));
    assertThat(
        AirCover.capProblem(
                germans,
                List.of(new TargetAssignment(front, bomber, 4, land(karelia))),
                Map.of(),
                Map.of())
            .isPresent(),
        is(false));
  }

  // ---- request 39: two rounds at sea

  @Test
  void aSubmarineStrikeHasTwoRounds() {
    air(bomber, "canAttackSubs", "true");
    addTo(naval, bomber.create(1, germans)); // 4: the remainder die hits once a round
    addTo(seaZone, submarine(gameData).create(2, british));

    strike(germans, new TargetAssignment(naval, bomber, 1, new Target(seaZone, TargetKind.SUBS)));

    assertThat(count(seaZone, british), is(0));
  }

  @Test
  void aShipNeverHitNeverFiresBack() {
    die = 5; // every remainder die misses: 4 makes no hit
    addTo(naval, bomber.create(1, germans));
    addTo(seaZone, destroyer().create(1, british));
    final IDelegateBridge bridge = bridge(germans);

    targets(germans, new TargetAssignment(naval, bomber, 1, new Target(seaZone, TargetKind.SURFACE)));
    runStep("airStrikes", bridge);

    assertThat(count(seaZone, british), is(1));
    assertThat(count(naval, germans), is(1));
    verify(bridge, never()).getRandom(anyInt(), any(), any(), contains("fire back"));
  }

  @Test
  void aBomberDamagedByReturnFireMakesNoSecondRoundAttack() {
    hitPoints(bomber, 2);
    addTo(naval, bomber.create(1, germans)); // 4: one hit a round
    addTo(seaZone, destroyer().create(2, british));

    strike(
        germans, new TargetAssignment(naval, bomber, 1, new Target(seaZone, TargetKind.SURFACE)));

    // round 1 sinks a destroyer, which fires back (3: one hit) and damages the bomber: it aborts,
    // so round 2 never comes and the other destroyer is left
    assertThat(count(seaZone, british), is(1));
    assertThat(count(naval, germans), is(1));
  }

  @Test
  void aShipHitInBothRoundsFiresTwiceAndTheAbortedBomberTakesTheFirstHit() {
    hitPoints(bomber, 2);
    hitPoints(battleship(gameData), 3);
    addTo(naval, bomber.create(2, germans)); // 8: two hits in round 1
    addTo(seaZone, battleship(gameData).create(1, british));
    final IDelegateBridge bridge = bridge(germans);

    targets(germans, new TargetAssignment(naval, bomber, 2, new Target(seaZone, TargetKind.SURFACE)));
    runStep("airStrikes", bridge);

    // round 1: the battleship takes 2 of 3 and fires back (4: one hit), one bomber damaged and
    // aborted; round 2: the other sinks it, it fires back again and the damaged bomber is hit first
    assertThat(count(seaZone, british), is(0));
    assertThat(count(naval, germans), is(1));
    verify(bridge, atLeastOnce()).getRandom(anyInt(), any(), any(), contains("fire back, round 1"));
    verify(bridge, atLeastOnce()).getRandom(anyInt(), any(), any(), contains("fire back, round 2"));
  }

  private UnitType destroyer() {
    return gameData.getUnitTypeList().getUnitTypeOrThrow("destroyer");
  }

  private void noInterception() {
    air(fighter, "interceptionRolls", "0");
    air(bomber, "airDefenseRolls", "0");
  }

  private void hitPoints(final UnitType type, final int hitPoints) {
    set(type.getUnitAttachment(), "hitPoints", String.valueOf(hitPoints));
  }

  private static int count(final Territory t, final GamePlayer owner, final UnitType type) {
    return t.getUnitCollection()
        .countMatches(Matches.unitIsOwnedBy(owner).and(Matches.unitIsOfType(type)));
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
  void strikeOnNeutralGarrisonNeverAsksTheNeutralPlayer() {
    // as in the real game, nobody plays the neutral player: any call to it fails
    // (request 8 bug 1: this ended the game with "Exception on remote")
    final GamePlayer neutral = gameData.getPlayerList().getNullPlayer();
    karelia.setOwner(neutral);
    addTo(front, bomber.create(3, germans)); // 3 x 4 = 12: two hits
    addTo(karelia, infantry(gameData).create(3, neutral));
    final IDelegateBridge bridge = bridge(germans);
    final Player nobody =
        mock(
            Player.class,
            invocation -> {
              throw new RuntimeException("Exception on remote");
            });
    when(bridge.getRemotePlayer(neutral)).thenReturn(nobody);

    targets(germans, new TargetAssignment(front, bomber, 3, land(karelia)));
    runStep("airStrikes", bridge);

    assertThat(count(karelia, neutral), is(1));
  }

  @Test
  void aFailingStrikeIsSkippedAndTheAirPhaseGoesOn() {
    addTo(front, bomber.create(2, germans)); // 2 x 4 = 8: needs a die for the remainder
    addTo(karelia, infantry(gameData).create(3, russians(gameData)));
    final IDelegateBridge bridge = bridge(germans);
    when(bridge.getRandom(anyInt(), any(), any(), anyString()))
        .thenThrow(new IllegalStateException("broken strike"));

    targets(germans, new TargetAssignment(front, bomber, 2, land(karelia)));
    runStep("airStrikes", bridge); // must not throw

    assertThat(count(karelia, russians(gameData)), is(3));
    assertThat(count(front, germans), is(2));
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
  void aTypesLegalTargetsAreWhatThePickerAccepts() {
    // request 8 bug 2: a zone holding only submarines has a target, but not for a plain bomber
    addTo(seaZone, submarine(gameData).create(1, british));
    final Target subs = new Target(seaZone, TargetKind.SUBS);

    assertThat(AirCombat.targetsIn(germans, seaZone), is(List.of(subs)));
    assertThat(AirCombat.mayAttack(bomber, TargetKind.SUBS), is(false));

    air(bomber, "canAttackSubs", "true");
    assertThat(AirCombat.mayAttack(bomber, TargetKind.SUBS), is(true));
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
    assertThat(AirCombat.mayAttack(fighter, TargetKind.LAND), is(false));
    assertThat(AirCombat.mayAttack(fighter, TargetKind.COVER), is(true));
    assertThat(AirCombat.mayAttack(bomber, TargetKind.COVER), is(false));
  }

  /** Both sides put all their fighters over Karelia, and the air phase is fought. */
  private void dogfightOverKarelia(final int each) {
    cover(germans, new TargetAssignment(front, fighter, each, over(karelia)));
    cover(british, new TargetAssignment(front, fighter, each, over(karelia)));
    runStep("airStrikes", germans);
  }

  /** The bombers take off over their targets (as the bomber stage does). */
  private void targets(final GamePlayer attacker, final TargetAssignment... assignments) {
    for (final TargetAssignment a : assignments) {
      fly(attacker, a);
      bombers.computeIfAbsent(attacker, k -> new java.util.ArrayList<>()).add(a);
    }
  }

  /** The fighters take off over the places they cover (as the fighter stage does). */
  private void cover(final GamePlayer player, final TargetAssignment... assignments) {
    for (final TargetAssignment a : assignments) {
      fly(player, a);
      fighters.computeIfAbsent(player, k -> new java.util.ArrayList<>()).add(a);
    }
  }

  /** That many of the nation's aircraft of the type in the air, not yet over a place, go over it. */
  private void fly(final GamePlayer player, final TargetAssignment a) {
    final List<Unit> units =
        front.getUnitCollection().stream()
            .filter(
                u ->
                    u.isOwnedBy(player)
                        && u.getType().equals(a.unitType())
                        && u.getFlightOver() == null)
            .sorted(java.util.Comparator.comparingInt(Unit::getHits))
            .limit(a.count())
            .toList();
    for (final Unit u : units) {
      gameData.performChange(
          ChangeFactory.unitPropertyChange(u, a.target().territory(), Unit.PropertyName.FLIGHT_OVER));
    }
  }

  private static Target over(final Territory t) {
    return AirCover.over(t);
  }

  private void strike(final GamePlayer attacker, final TargetAssignment... assignments) {
    targets(attacker, assignments);
    runStep("airStrikes", attacker);
  }

  private void runStep(final String step, final GamePlayer player) {
    runStep(step, bridge(player));
  }

  private void runStep(final String step, final IDelegateBridge bridge) {
    advanceToStep(bridge, step);
    delegate.setDelegateBridgeAndPlayer(bridge);
    delegate.setStageBombers(bombers);
    delegate.setStageCover(fighters);
    bombers.clear();
    fighters.clear();
    delegate.start();
    delegate.end();
    // the next battle starts with nobody over anything
    for (final Unit u : front.getUnits()) {
      gameData.performChange(ChangeFactory.unitPropertyChange(u, null, Unit.PropertyName.FLIGHT_OVER));
    }
  }

  /** A bridge whose remainder dice always roll 0 and whose players take the offered casualties. */
  private IDelegateBridge bridge(final GamePlayer player) {
    final IDelegateBridge bridge = newDelegateBridge(player);
    when(bridge.getRandom(anyInt(), any(), any(), anyString())).thenReturn(die);
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
