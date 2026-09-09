package com.lapczynski.demotarget.fault;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Clamps requested fault magnitudes to the ceilings compiled into {@link FaultType}.
 *
 * <p>Clamping happens once, here, before an {@link ActiveFault} is constructed. Everything
 * downstream can then treat the stored parameters as already safe, rather than each consumer
 * remembering to bound the value again — the pattern where one forgetful caller becomes the
 * incident.
 *
 * <p>The dispatch is a switch <em>expression</em> with no {@code default}, so the compiler requires
 * every {@link FaultType} to be handled. Adding a fault type breaks the build here until its
 * ceilings are defined. A {@code default} branch would have been more convenient and strictly
 * worse: a new fault type would silently inherit no clamping at all.
 *
 * <p>Out-of-range values are clamped rather than rejected, and the clamped value is what gets
 * stored and displayed. An operator asking for 60 seconds of latency sees that they got 5, instead
 * of an error that tempts them to look for a way around it.
 */
final class FaultParameters {

  private FaultParameters() {}

  static Map<String, String> clamp(FaultType type, Map<String, String> requested) {
    Map<String, String> safe = new LinkedHashMap<>(requested == null ? Map.of() : requested);

    return switch (type) {
      case ERROR_RATE -> {
        safe.put("rate", String.valueOf(clampDouble(safe.get("rate"), 0.0, 1.0, 0.5)));
        yield Map.copyOf(safe);
      }

      case LATENCY -> {
        safe.put(
            "millis",
            String.valueOf(
                clampInt(safe.get("millis"), 0, (int) FaultType.MAX_LATENCY.toMillis(), 500)));
        yield Map.copyOf(safe);
      }

      case DOWNSTREAM_TIMEOUT -> {
        safe.put(
            "millis",
            String.valueOf(
                clampInt(safe.get("millis"), 0, (int) FaultType.MAX_LATENCY.toMillis(), 2000)));
        safe.putIfAbsent("dependency", "payments-api");
        yield Map.copyOf(safe);
      }

      case DB_POOL_PRESSURE -> {
        // The real ceiling also depends on the live pool size and is applied again by the holder,
        // which is the only component that knows it. This bound stops an absurd request from ever
        // reaching that code.
        safe.put("connections", String.valueOf(clampInt(safe.get("connections"), 1, 50, 5)));
        yield Map.copyOf(safe);
      }

      case ENDPOINT_FAILURE -> {
        safe.putIfAbsent("path", "/api/payments");
        safe.put("status", String.valueOf(clampInt(safe.get("status"), 400, 599, 500)));
        yield Map.copyOf(safe);
      }

      case CPU_PRESSURE -> {
        safe.put(
            "threads",
            String.valueOf(clampInt(safe.get("threads"), 1, FaultType.maxCpuThreads(), 1)));
        yield Map.copyOf(safe);
      }

      case UNHEALTHY_READINESS -> {
        safe.putIfAbsent("state", "DOWN");
        yield Map.copyOf(safe);
      }

      case BAD_VERSION -> {
        safe.putIfAbsent("version", "2.4.0-bad");
        yield Map.copyOf(safe);
      }
    };
  }

  private static double clampDouble(String raw, double min, double max, double fallback) {
    double value = fallback;
    if (raw != null) {
      try {
        value = Double.parseDouble(raw.trim());
      } catch (NumberFormatException e) {
        value = fallback;
      }
    }
    if (Double.isNaN(value)) {
      value = fallback;
    }
    return Math.clamp(value, min, max);
  }

  private static int clampInt(String raw, int min, int max, int fallback) {
    int value = fallback;
    if (raw != null) {
      try {
        value = Integer.parseInt(raw.trim());
      } catch (NumberFormatException e) {
        value = fallback;
      }
    }
    return Math.clamp(value, min, max);
  }
}
