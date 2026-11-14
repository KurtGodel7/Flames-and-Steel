package org.triplea.game.server.aitest;

import games.strategy.engine.random.IRandomSource;
import java.util.SplittableRandom;

/** Request 69: the game's dice from a fixed seed, so a test game can be told apart and rerun. */
final class SeededRandomSource implements IRandomSource {
  private final SplittableRandom random;

  SeededRandomSource(final long seed) {
    random = new SplittableRandom(seed);
  }

  @Override
  public synchronized int getRandom(final int max, final String annotation) {
    return random.nextInt(max);
  }

  @Override
  public synchronized int[] getRandom(final int max, final int count, final String annotation) {
    final int[] numbers = new int[count];
    for (int i = 0; i < count; i++) {
      numbers[i] = random.nextInt(max);
    }
    return numbers;
  }
}
