package games.strategy.triplea.delegate.air;

import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.display.IDisplay;
import games.strategy.engine.player.Player;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.Die;
import games.strategy.triplea.delegate.Die.DieType;
import games.strategy.triplea.delegate.battle.IBattle.BattleType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.triplea.java.Interruptibles;

/**
 * Flames and Steel: shows one air battle (a dogfight or a strike) in the standard battle window:
 * units of both sides, the steps, dice, casualties chosen in the battle window, and the result.
 */
public final class AirBattleView {
  private final IDelegateBridge bridge;
  private GamePlayer attacker;
  private GamePlayer defender;
  private final UUID id = UUID.randomUUID();

  private AirBattleView(final IDelegateBridge bridge) {
    this.bridge = bridge;
  }

  /**
   * Opens the battle window for the players of both sides.
   *
   * @param steps the battle's steps, in order; dice and casualties name the step they belong to
   */
  public static AirBattleView show(
      final IDelegateBridge bridge,
      final Territory location,
      final String title,
      final Collection<Unit> attackingUnits,
      final Collection<Unit> defendingUnits,
      final GamePlayer attacker,
      final GamePlayer defender,
      final List<String> steps) {
    final AirBattleView view = new AirBattleView(bridge);
    view.attacker = attacker;
    view.defender = defender;
    final IDisplay display = bridge.getDisplayChannelBroadcaster();
    display.showBattle(
        view.id,
        location,
        title,
        new ArrayList<>(attackingUnits),
        new ArrayList<>(defendingUnits),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        attacker,
        defender,
        false,
        BattleType.AIR_BATTLE,
        List.of());
    display.listBattleSteps(view.id, steps);
    return view;
  }

  public UUID getId() {
    return id;
  }

  /**
   * Moves the window to a step and shows the roll there. Returns the dice as the window shows them.
   */
  public DiceRoll showDice(final AirCombat.Roll roll, final GamePlayer roller, final String step) {
    final int sides = bridge.getData().getDiceSides();
    final int remainder = roll.power() % sides;
    final List<Die> dice = new ArrayList<>();
    for (final int die : roll.dice()) {
      dice.add(new Die(die, remainder, die < remainder ? DieType.HIT : DieType.MISS));
    }
    final DiceRoll diceRoll =
        new DiceRoll(dice, roll.hits(), roll.power() / (double) sides, roller.getName());
    bridge.getDisplayChannelBroadcaster().gotoBattleStep(id, step);
    bridge.getDisplayChannelBroadcaster().notifyDice(diceRoll, step);
    return diceRoll;
  }

  /** Moves the window to a step without dice (e.g. "Transports lost"). */
  public void gotoStep(final String step) {
    bridge.getDisplayChannelBroadcaster().gotoBattleStep(id, step);
  }

  /**
   * Shows the casualties in the window, then lets both sides look before going on (the hit side
   * confirms when its casualties were chosen automatically, as in stock battles).
   */
  public void showCasualties(
      final String step,
      final DiceRoll dice,
      final GamePlayer hitPlayer,
      final GamePlayer firingPlayer,
      final Collection<Unit> killed,
      final Collection<Unit> damaged,
      final boolean autoCalculated) {
    bridge
        .getDisplayChannelBroadcaster()
        .casualtyNotification(
            id, step, dice, hitPlayer, new ArrayList<>(killed), new ArrayList<>(damaged), Map.of());
    if (autoCalculated || hitPlayer.isAi()) {
      bridge.getRemotePlayer(hitPlayer).confirmOwnCasualties(id, "Press space to continue");
    }
    if (firingPlayer.equals(hitPlayer)) {
      return;
    }
    final Player firing = bridge.getRemotePlayer(firingPlayer);
    final Thread t =
        new Thread(
            () -> {
              try {
                firing.confirmEnemyCasualties(id, "Press space to continue", hitPlayer);
              } catch (final Exception e) {
                // the window may already be gone
              }
            },
            "air battle continue waiter");
    t.start();
    bridge.leaveDelegateExecution();
    Interruptibles.join(t);
    bridge.enterDelegateExecution();
  }

  /** Ends the battle with a message, e.g. "Germans lose 6 PUs". */
  public void end(final String message) {
    bridge.getDisplayChannelBroadcaster().battleEnd(id, message);
    // wait until the players have seen the result, as a stock battle does before the next one
    bridge
        .getRemotePlayer(attacker)
        .confirmOwnCasualties(id, message + ": press space to continue");
    if (!defender.equals(attacker)) {
      final Player other = bridge.getRemotePlayer(defender);
      final Thread t =
          new Thread(
              () -> {
                try {
                  other.confirmEnemyCasualties(id, message + ": press space to continue", attacker);
                } catch (final Exception e) {
                  // the window may already be gone
                }
              },
              "air battle end waiter");
      t.start();
      bridge.leaveDelegateExecution();
      Interruptibles.join(t);
      bridge.enterDelegateExecution();
    }
  }
}
