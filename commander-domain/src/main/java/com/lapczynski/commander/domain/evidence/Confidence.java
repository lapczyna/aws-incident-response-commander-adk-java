package com.lapczynski.commander.domain.evidence;

/**
 * A bounded confidence score.
 *
 * <p>Constrained to [0.0, 1.0] at construction so that a model returning 4.7, or a negative number,
 * fails immediately at the boundary rather than propagating into a risk calculation.
 */
public record Confidence(double value) implements Comparable<Confidence> {

  public static final Confidence ZERO = new Confidence(0.0);
  public static final Confidence CERTAIN = new Confidence(1.0);

  public Confidence {
    if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
      throw new IllegalArgumentException("confidence must be within [0.0, 1.0], was " + value);
    }
  }

  /** Parses a model-supplied value, clamping instead of throwing. */
  public static Confidence clamped(double raw) {
    if (Double.isNaN(raw)) {
      return ZERO;
    }
    return new Confidence(Math.clamp(raw, 0.0, 1.0));
  }

  public boolean atLeast(Confidence other) {
    return value >= other.value;
  }

  @Override
  public int compareTo(Confidence other) {
    return Double.compare(value, other.value);
  }
}
