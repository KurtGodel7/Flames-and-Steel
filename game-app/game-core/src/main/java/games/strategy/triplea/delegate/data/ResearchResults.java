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

  /**
   * One research result: a star gained, or a roll that found nothing to research.
   *
   * @param center research centre number (1-based), or 0 for a bonus roll or automatic research
   * @param source how the category was picked: "primary", "secondary", "random", "automatic", or
   *     the name of the tech that gives a bonus roll
   * @param category the category that gained a star, or null if none did
   * @param tech the tech that gained a star, or null if none did
   * @param stars the tech's stars after this result
   * @param required the stars the tech needs
   */
  public record Breakthrough(
      int center,
      String source,
      @Nullable String category,
      @Nullable String tech,
      int stars,
      int required)
      implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    public boolean gainedStar() {
      return tech != null;
    }

    public boolean unlocked() {
      return tech != null && stars >= required;
    }
  }

  private final @Nullable String error;

  /** One line per star gained, for display. */
  @Getter private final List<String> breakthroughs;

  /** The same results, for drawing on the research screen. May be empty in old saves. */
  private final @Nullable List<Breakthrough> details;

  private ResearchResults(
      final @Nullable String error,
      final List<String> breakthroughs,
      final List<Breakthrough> details) {
    this.error = error;
    this.breakthroughs = breakthroughs;
    this.details = details;
  }

  public static ResearchResults error(final String error) {
    return new ResearchResults(error, List.of(), List.of());
  }

  public static ResearchResults success(
      final List<String> breakthroughs, final List<Breakthrough> details) {
    return new ResearchResults(null, List.copyOf(breakthroughs), List.copyOf(details));
  }

  public Optional<String> getError() {
    return Optional.ofNullable(error);
  }

  public List<Breakthrough> getDetails() {
    return details == null ? List.of() : details;
  }
}
