package games.strategy.triplea.ui;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.triplea.ui.panels.map.MapPanel;
import games.strategy.triplea.formatter.PlayerNames;
import javax.annotation.Nullable;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * Flames and Steel (request 16): what the Actions tab shows during a step that has no panel of its
 * own: steps no nation plays (dogfights, strikes), steps played in their own window (research, APC
 * production) and other players' steps. It replaces the last step's panel, whose buttons must not
 * stay on screen. Request 34: a step played in a window that can be closed while the step is open
 * gets one button to reopen it.
 */
public class StepInfoPanel extends ActionPanel {
  private static final long serialVersionUID = 1L;
  private final JLabel detail = createIndentedLabel();
  // request 34: e.g. "Open research window"; null when the step has no window to reopen
  private @Nullable JButton button;

  StepInfoPanel(final GameData data, final MapPanel map) {
    super(data, map);
  }

  /** "Germans · Research" with a line below, e.g. "Choose in the research window." */
  void setStep(final @Nullable GamePlayer player, final String step, final String detailText) {
    setStep(player, step, detailText, null, null);
  }

  /**
   * As {@link #setStep(GamePlayer, String, String)}, with a button under the line (request 34),
   * e.g. "Open research window", when both are given.
   */
  void setStep(
      final @Nullable GamePlayer player,
      final String step,
      final String detailText,
      final @Nullable String buttonText,
      final @Nullable Runnable action) {
    SwingUtilities.invokeLater(
        () -> {
          removeAll();
          // request 34: display names ("America Atlantic · Research")
          actionLabel.setText(
              (player == null || player.isNull() ? "" : PlayerNames.display(player) + " · ")
                  + step);
          detail.setText(PlayerNames.inText(detailText, getData()));
          actionLabel.setAlignmentX(LEFT_ALIGNMENT);
          detail.setAlignmentX(LEFT_ALIGNMENT);
          add(actionLabel);
          add(detail);
          button = null;
          if (buttonText != null && action != null) {
            button = new JButton(buttonText);
            button.addActionListener(e -> action.run());
            final javax.swing.JPanel buttons = createButtonsPanel(button);
            buttons.setAlignmentX(LEFT_ALIGNMENT);
            add(buttons);
          }
          refresh.run();
        });
  }

  /** The button shown, if any, for tests. */
  @Nullable
  JButton getButton() {
    return button;
  }

  /** The text shown, for tests. */
  String getText() {
    return actionLabel.getText() + " / " + detail.getText();
  }

  @Override
  public void performDone() {
    // nothing to finish: the step ends by itself
  }

  @Override
  public String toString() {
    return "StepInfoPanel";
  }
}
