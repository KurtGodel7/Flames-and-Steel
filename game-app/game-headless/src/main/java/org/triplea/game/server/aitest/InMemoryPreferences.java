package org.triplea.game.server.aitest;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;

/**
 * Request 69: settings kept in memory, so an AI test run never reads or changes the player's own
 * TripleA settings.
 */
final class InMemoryPreferences extends AbstractPreferences {
  private final Map<String, String> values = new HashMap<>();
  private final Map<String, InMemoryPreferences> children = new HashMap<>();

  InMemoryPreferences() {
    this(null, "");
  }

  private InMemoryPreferences(final AbstractPreferences parent, final String name) {
    super(parent, name);
  }

  @Override
  protected void putSpi(final String key, final String value) {
    values.put(key, value);
  }

  @Override
  protected String getSpi(final String key) {
    return values.get(key);
  }

  @Override
  protected void removeSpi(final String key) {
    values.remove(key);
  }

  @Override
  protected void removeNodeSpi() {
    children.clear();
    values.clear();
  }

  @Override
  protected String[] keysSpi() {
    return values.keySet().toArray(String[]::new);
  }

  @Override
  protected String[] childrenNamesSpi() {
    return children.keySet().toArray(String[]::new);
  }

  @Override
  protected AbstractPreferences childSpi(final String name) {
    return children.computeIfAbsent(name, n -> new InMemoryPreferences(this, n));
  }

  @Override
  protected void syncSpi() {}

  @Override
  protected void flushSpi() {}
}
