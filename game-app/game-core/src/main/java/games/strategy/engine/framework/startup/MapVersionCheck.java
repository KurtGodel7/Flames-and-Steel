package games.strategy.engine.framework.startup;

import games.strategy.engine.data.GameData;
import games.strategy.engine.framework.map.file.system.loader.InstalledMapsListing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;

/**
 * Flames and Steel (request 68): a networked game needs the same map on every computer. The game's
 * rules travel from the host, but each computer draws the map from its own copy, so a different
 * draft would draw wrong or fail. The host writes its map version into the game it sends; a client
 * compares it with its own copy before the game opens.
 */
@Slf4j
public final class MapVersionCheck {
  /** Game property holding the host's installed map version (the XML's info version). */
  public static final String HOST_MAP_VERSION = "FS Host Map Version";

  private static final Pattern INFO_VERSION =
      Pattern.compile("<info\\b[^>]*\\bversion\\s*=\\s*[\"']([^\"']+)[\"']");

  private MapVersionCheck() {}

  /** The version in the installed game XML for this game name, if any. */
  public static Optional<String> installedVersion(final String gameName) {
    try {
      final Optional<Path> xml =
          InstalledMapsListing.parseMapFiles().findGameXmlPathByGameName(gameName);
      if (xml.isEmpty()) {
        return Optional.empty();
      }
      return versionIn(Files.readString(xml.get()));
    } catch (final Exception e) {
      log.info("Could not read the installed map version of " + gameName, e);
      return Optional.empty();
    }
  }

  /** The info version written in a game XML's text, if any. */
  static Optional<String> versionIn(final String xml) {
    final Matcher m = INFO_VERSION.matcher(xml);
    return m.find() ? Optional.of(m.group(1)) : Optional.empty();
  }

  /** On the host, before the game goes to the clients: record the host's map version. */
  public static void stampHostVersion(final GameData data) {
    installedVersion(data.getGameName())
        .ifPresent(v -> data.getProperties().set(HOST_MAP_VERSION, v));
  }

  /** On a client: why this computer's map doesn't match the host's, or empty. */
  public static Optional<String> problem(final GameData data) {
    final String host = data.getProperties().get(HOST_MAP_VERSION, "");
    if (host.isBlank()) {
      return Optional.empty(); // host older than request 68: nothing to compare
    }
    return problem(host, installedVersion(data.getGameName()).orElse(null));
  }

  static Optional<String> problem(final String host, final @Nullable String mine) {
    if (host.equals(mine)) {
      return Optional.empty();
    }
    return Optional.of(
        "Map version mismatch: the host has "
            + "map version "
            + host
            + ", but this computer has "
            + (mine == null ? "no copy of the map" : "version " + mine)
            + ".\nEveryone in a game must use the same release. Download the release the host is"
            + " using, then join again.");
  }
}
