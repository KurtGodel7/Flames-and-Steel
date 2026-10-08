package games.strategy.triplea.delegate.data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import lombok.Getter;

/** Flames and Steel: outcome of a research step, or why the chosen focus was refused. */
public class ResearchResults implements Serializable {
  @Serial private static final long serialVersionUID = 4319237498323420712L;

  private final @Nullable String error;

  /** One line per star gained, for display. */
  @Getter private final List<String> breakthroughs;

  private ResearchResults(final @Nullable String error, final List<String> breakthroughs) {
    this.error = error;
    this.breakthroughs = breakthroughs;
  }

  public static ResearchResults error(final String error) {
    return new ResearchResults(error, List.of());
  }

  public static ResearchResults success(final List<String> breakthroughs) {
    return new ResearchResults(null, List.copyOf(breakthroughs));
  }

  public Optional<String> getError() {
    return Optional.ofNullable(error);
  }
}
