package com.lapczynski.demotarget.fault;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One injected fault, with the expiry that will end it.
 *
 * <p>{@code expiresAt} is mandatory and is never {@code null}. There is no "until I turn it off"
 * mode and no way to construct a fault without one: a demo fault that outlives the demo is the
 * failure mode this whole subsystem is designed to prevent, and making it unrepresentable is
 * cheaper than remembering to clean up.
 *
 * @param parameters magnitude settings, already clamped by {@link FaultType} before construction.
 *     Stored for display so an operator can see exactly what is active.
 */
public record ActiveFault(
    FaultType type,
    Map<String, String> parameters,
    Instant activatedAt,
    Instant expiresAt,
    String activatedBy) {

  public ActiveFault {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(activatedAt, "activatedAt must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    Objects.requireNonNull(activatedBy, "activatedBy must not be null");
    parameters = Map.copyOf(new TreeMap<>(parameters));

    if (!expiresAt.isAfter(activatedAt)) {
      throw new IllegalArgumentException("expiresAt must be after activatedAt");
    }
    Duration requested = Duration.between(activatedAt, expiresAt);
    if (requested.compareTo(type.maxDuration()) > 0) {
      throw new IllegalArgumentException(
          "%s may not stay active for %s; the ceiling is %s"
              .formatted(type, requested, type.maxDuration()));
    }
  }

  /** Creates a fault expiring after {@code duration}, clamped to the type's ceiling. */
  public static ActiveFault starting(
      FaultType type,
      Map<String, String> parameters,
      Duration duration,
      String activatedBy,
      Instant now) {
    Duration clamped = type.clampDuration(duration);
    return new ActiveFault(type, parameters, now, now.plus(clamped), activatedBy);
  }

  public boolean hasExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }

  public Duration remaining(Instant now) {
    Duration left = Duration.between(now, expiresAt);
    return left.isNegative() ? Duration.ZERO : left;
  }

  /** A parameter as a double, falling back when absent or unparseable. */
  public double doubleParam(String key, double fallback) {
    String raw = parameters.get(key);
    if (raw == null) {
      return fallback;
    }
    try {
      return Double.parseDouble(raw);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /** A parameter as an int, falling back when absent or unparseable. */
  public int intParam(String key, int fallback) {
    String raw = parameters.get(key);
    if (raw == null) {
      return fallback;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  public String stringParam(String key, String fallback) {
    return parameters.getOrDefault(key, fallback);
  }
}
