package com.lapczynski.commander.application.signal;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A bounded time range for a signal query.
 *
 * <p>Bounded on purpose: an unbounded lookback against CloudWatch is slow, expensive, and returns
 * more than a model can usefully read. The cap is enforced here so no adapter can forget it.
 */
public record TimeWindow(Instant from, Instant to) {

  /** The longest window any signal source will honour. */
  public static final Duration MAX_LOOKBACK = Duration.ofHours(24);

  public TimeWindow {
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(to, "to must not be null");
    if (!to.isAfter(from)) {
      throw new IllegalArgumentException("window end must be after its start");
    }
    if (Duration.between(from, to).compareTo(MAX_LOOKBACK) > 0) {
      throw new IllegalArgumentException(
          "window of %s exceeds the %s maximum lookback"
              .formatted(Duration.between(from, to), MAX_LOOKBACK));
    }
  }

  /** The window ending now and extending back by {@code lookback}. */
  public static TimeWindow endingAt(Instant end, Duration lookback) {
    return new TimeWindow(end.minus(lookback), end);
  }

  public Duration duration() {
    return Duration.between(from, to);
  }

  public boolean contains(Instant instant) {
    return !instant.isBefore(from) && !instant.isAfter(to);
  }
}
