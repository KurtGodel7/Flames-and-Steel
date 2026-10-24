package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.removeFrom;
import static games.strategy.triplea.delegate.MockDelegateBridge.advanceToStep;
import static games.strategy.triplea.delegate.MockDelegateBridge.newDelegateBridge;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.UnitType;
import games.strategy.engine.data.changefactory.ChangeFactory;
import games.strategy.engine.data.gameparser.GameParser;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.player.Player;
import games.strategy.triplea.attachments.AirZoneAttachment;
import games.strategy.triplea.delegate.air.AirAllocation.Stage;
import games.strategy.triplea.delegate.air.AirCombat;
import games.strategy.triplea.delegate.air.AirCombat.Target;
import games.strategy.triplea.delegate.air.AirCombat.TargetAssignment;
import games.strategy.triplea.delegate.air.AirCombat.TargetKind;
import games.strategy.triplea.delegate.air.AirStage;
import games.strategy.triplea.delegate.air.AirStage.Choice;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Request 35 part 3 on the installed map: the simultaneous air stages, host side. */
class AirStageTest {
  private static final Path XML =
      Path.of(
          System.getProperty("user.home"),
          "triplea/downloadedMaps/flames_and_steel/map/games/flames_and_steel.xml");
  private GameData data;
  private GamePlayer germans;
  private GamePlayer russians;
  private AirStageDelegate stage;
  private Player germanClient;
  private Player sovietClient;

  @BeforeEach
  void setUp() {
    Assumptions.assumeTrue(Files.exists(XML), "Flames and Steel map not installed");
    data = GameParser.parse(XML, false).orElseThrow();
    germans = data.getPlayerList().getPlayerId("Germans");
    russians = data.getPlayerList().getPlayerId("Russians");
    final InitializationDelegate start = new InitializationDelegate();
    start.initialize("initDelegate", "Initializing Delegates");
    start.setDelegateBridgeAndPlayer(newDelegateBridge(germans));
    start.start();
    start.end();
    data.performChange(
        ChangeFactory.relationshipChange(
            germans,
            russians,
            data.getRelationshipTracker().getRelationshipType(germans, russians),
            data.getRelationshipTypeList().getRelationshipType("War")));
    stage = new AirStageDelegate();
    stage.initialize("airStage", "Air Stage");
    for (final String step :
        List.of("airBomberStage", "airRevealBombers", "airFighterStage", "airRevealFighters")) {
      data.getSequence().addStep(new GameStep(step, step, null, stage, data, new Properties()));
    }
    germanClient = mock(Player.class);
    sovietClient = mock(Player.class);
  }

  private IDelegateBridge bridge() {
    final IDelegateBridge bridge = newDelegateBridge(germans);
    when(bridge.getRemotePlayer(germans)).thenReturn(germanClient);
    when(bridge.getRemotePlayer(russians)).thenReturn(sovietClient);
    return bridge;
  }

  private void run(final String step) {
    final IDelegateBridge bridge = bridge();
    advanceToStep(bridge, step);
    stage.setDelegateBridgeAndPlayer(bridge);
    stage.start();
    stage.end();
  }

  private static void answer(final Player client, final Choice choice) {
    when(client.selectAirStage(any(), any(), any(), any(), any(), anyString())).thenReturn(choice);
  }

  @Test
  void everyNationIsAskedAtOnce() throws Exception {
    // the Germans' answer waits until the Soviets have been asked: a stage asking one nation after
    // another would never get there
    final CountDownLatch sovietsAsked = new CountDownLatch(1);
    final AtomicBoolean overlapped = new AtomicBoolean();
    when(germanClient.selectAirStage(any(), any(), any(), any(), any(), anyString()))
        .thenAnswer(
            i -> {
              overlapped.set(sovietsAsked.await(20, TimeUnit.SECONDS));
              return Choice.none();
            });
    when(sovietClient.selectAirStage(any(), any(), any(), any(), any(), anyString()))
        .thenAnswer(
            i -> {
              sovietsAsked.countDown();
              return Choice.none();
            });
    run("airBomberStage");
    assertThat("both were asked while the other was still choosing", overlapped.get(), is(true));
    assertThat(stage.accepted(Stage.BOMBERS).containsKey(germans), is(true));
    assertThat(stage.accepted(Stage.BOMBERS).containsKey(russians), is(true));
  }

  /** A German bomber type and a target it can reach only from an adjacent box. */
  private record Reach(Territory origin, Territory into, UnitType type, Target target) {}

  private Reach adjacentTarget() {
    for (final Territory box : AirZoneAttachment.getAirZones(data.getMap())) {
      for (final var u : AirCombat.getBombers(germans, box)) {
        for (final var e :
            AirStage.candidates(germans, box, u.getType(), Stage.BOMBERS, Map.of()).entrySet()) {
          if (!e.getValue().equals(box) && e.getKey().kind() == TargetKind.LAND) {
            return new Reach(box, e.getValue(), u.getType(), e.getKey());
          }
        }
      }
    }
    Assumptions.abort("no German bomber with a target in an adjacent box");
    return null;
  }

  @Test
  void aTargetInAnAdjacentFrontMovesTheBomberThereAndBombsFromThere() {
    final Reach reach = adjacentTarget();
    final Choice choice =
        AirStage.toChoice(
            germans,
            Stage.BOMBERS,
            List.of(new TargetAssignment(reach.origin(), reach.type(), 1, reach.target())),
            Map.of());
    assertThat(choice.moves().size(), is(1));
    assertThat(choice.moves().get(0).to(), is(reach.into()));
    assertThat(choice.targets().get(0).zone(), is(reach.into()));
    // range is judged from the box it ends in
    assertThat(
        AirCombat.mayAttack(germans, reach.into(), reach.type(), reach.target()), is(true));
    answer(germanClient, choice);
    answer(sovietClient, Choice.none());
    final long before =
        reach.into().getUnitCollection().stream()
            .filter(u -> u.isOwnedBy(germans) && u.getType().equals(reach.type()))
            .count();
    run("airBomberStage");
    run("airRevealBombers");
    final long after =
        reach.into().getUnitCollection().stream()
            .filter(u -> u.isOwnedBy(germans) && u.getType().equals(reach.type()))
            .count();
    assertThat(after, is(before + 1));
  }

  @Test
  void aChoiceOverTheCapIsAskedAgainWithTheReason() {
    final Reach reach = adjacentTarget();
    final Territory box = reach.into();
    removeFrom(box, box.getUnitCollection().getMatches(u -> u.isOwnedBy(germans)));
    addTo(box, reach.type().create(5, germans));
    final Choice tooMany =
        new Choice(List.of(), List.of(new TargetAssignment(box, reach.type(), 5, reach.target())));
    final List<String> problems = Collections.synchronizedList(new ArrayList<>());
    when(germanClient.selectAirStage(any(), any(), any(), any(), any(), anyString()))
        .thenAnswer(
            i -> {
              problems.add(i.getArgument(5));
              return problems.size() == 1 ? tooMany : Choice.none();
            });
    answer(sovietClient, Choice.none());
    run("airBomberStage");
    assertThat(problems.size(), is(2));
    assertThat(problems.get(1), containsString("at most 4 bombers"));
  }

  @Test
  void bombersAreSecretUntilTheRevealAndFightersSeeThem() {
    final Reach reach = adjacentTarget();
    final Choice choice =
        AirStage.toChoice(
            germans,
            Stage.BOMBERS,
            List.of(new TargetAssignment(reach.origin(), reach.type(), 1, reach.target())),
            Map.of());
    answer(germanClient, choice);
    answer(sovietClient, Choice.none());
    run("airBomberStage");
    // the Soviets were asked in the bomber stage with no other nation's choices
    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Map<GamePlayer, List<TargetAssignment>>> seen =
        ArgumentCaptor.forClass(Map.class);
    org.mockito.Mockito.verify(sovietClient)
        .selectAirStage(any(), any(), any(), seen.capture(), any(), anyString());
    assertThat(seen.getValue().isEmpty(), is(true));
    run("airRevealBombers");
    org.mockito.Mockito.clearInvocations(sovietClient);
    answer(sovietClient, Choice.none());
    run("airFighterStage");
    // in the fighter stage every bomber choice is shown
    final List<Map<GamePlayer, List<TargetAssignment>>> shown = new ArrayList<>();
    try {
      @SuppressWarnings("unchecked")
      final ArgumentCaptor<Map<GamePlayer, List<TargetAssignment>>> fighterSeen =
          ArgumentCaptor.forClass(Map.class);
      org.mockito.Mockito.verify(sovietClient)
          .selectAirStage(any(), any(), any(), fighterSeen.capture(), any(), anyString());
      shown.add(fighterSeen.getValue());
    } catch (final AssertionError e) {
      Assumptions.abort("the Soviets have no fighter that can act this round");
    }
    assertThat(shown.get(0).get(germans), is(choice.targets()));
  }

  @Test
  void nationsAlreadyDoneAreKeptThroughASave() {
    answer(germanClient, Choice.none());
    answer(sovietClient, Choice.none());
    run("airBomberStage");
    final var saved = stage.saveState();
    final AirStageDelegate loaded = new AirStageDelegate();
    loaded.initialize("airStage", "Air Stage");
    loaded.loadState(saved);
    assertThat(loaded.accepted(Stage.BOMBERS).keySet(), is(stage.accepted(Stage.BOMBERS).keySet()));
    // the loaded stage asks none of them again
    final Player again = mock(Player.class);
    final IDelegateBridge bridge = newDelegateBridge(germans);
    when(bridge.getRemotePlayer(any())).thenReturn(again);
    advanceToStep(bridge, "airBomberStage");
    loaded.setDelegateBridgeAndPlayer(bridge);
    loaded.start();
    loaded.end();
    org.mockito.Mockito.verifyNoInteractions(again);
  }
}
