package org.triplea.config.product;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.triplea.config.ResourcePropertyReader;

/**
 * Provides access to the product configuration. The product configuration applies to all components
 * of the TripleA application suite (e.g. game client, lobby server, etc.).
 *
 * <p>Flames and Steel (request 68): a jar built by the fork carries its build number (as in the
 * jar's file name) in {@code META-INF/triplea/fork-version.txt}; that wins when present.
 */
public final class ProductVersionReader {
  private static String currentVersion;

  public static String getCurrentVersion() {
    if (currentVersion == null) {
      currentVersion = forkVersion();
    }
    if (currentVersion == null) {
      var resourcePropertyReader =
          new ResourcePropertyReader("META-INF/triplea/product.properties");
      currentVersion = resourcePropertyReader.readProperty("version");
    }
    return currentVersion;
  }

  private static String forkVersion() {
    try (InputStream in =
        ProductVersionReader.class
            .getClassLoader()
            .getResourceAsStream("META-INF/triplea/fork-version.txt")) {
      if (in == null) {
        return null;
      }
      final String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
      return text.isEmpty() ? null : text;
    } catch (final IOException e) {
      return null;
    }
  }
}
