package games.strategy.engine.framework.ui;

import games.strategy.engine.framework.RollingAutoSave;
import games.strategy.engine.framework.startup.ui.panels.main.game.selector.GameFileSelector;
import java.nio.file.Path;
import java.util.Optional;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Flames and Steel (request 36): at start-up, if the last game did not close normally, offer to
 * resume it from the newest autosave.
 */
public final class AutoSaveRecovery {
  private AutoSaveRecovery() {}

  /** Shows the offer if the last game was interrupted; asks once per interruption. */
  public static void offer() {
    if (!RollingAutoSave.wasInterrupted()) {
      return;
    }
    RollingAutoSave.markStopped();
    final Optional<Path> latest = RollingAutoSave.latest();
    if (latest.isEmpty()) {
      return;
    }
    SwingUtilities.invokeLater(() -> ask(latest.get()));
  }

  /** "Your last game did not close normally. Resume from ... (5 minutes ago)?" */
  public static String question(final Path save, final long now) {
    return "Your last game did not close normally. Resume from the latest autosave: "
        + RollingAutoSave.describe(save)
        + " ("
        + ago(now - RollingAutoSave.modified(save))
        + ")?";
  }

  static String ago(final long millis) {
    final long minutes = Math.max(0, millis / 60_000);
    if (minutes < 1) {
      return "less than a minute ago";
    }
    if (minutes < 120) {
      return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
    }
    final long hours = minutes / 60;
    return hours < 48 ? hours + " hours ago" : hours / 24 + " days ago";
  }

  private static void ask(final Path save) {
    final String[] options = {"Resume", "Choose another save", "No"};
    final int choice =
        JOptionPane.showOptionDialog(
            null,
            question(save, System.currentTimeMillis()),
            "Resume game?",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[0]);
    if (choice == 0) {
      MainFrame.loadSaveFile(save);
    } else if (choice == 1) {
      GameFileSelector.selectAutoSave(null).ifPresent(MainFrame::loadSaveFile);
    }
  }
}
