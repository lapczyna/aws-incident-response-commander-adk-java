package com.lapczynski.commander.application.signal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Reads service metrics. Implemented by CloudWatch in AWS mode and by the simulator locally.
 *
 * <p>Metrics are numeric aggregates, so unlike logs they carry no attacker-influenced free text.
 * That is why {@code EvidenceSource.CLOUDWATCH_METRICS} is treated as trusted content.
 */
public interface MetricsPort {

  /** Upper bound on datapoints returned, so a narrow period cannot flood a prompt. */
  int MAX_DATAPOINTS = 500;

  /**
   * @throws SignalSourceException if the metric cannot be read
   */
  MetricSeries query(MetricQuery query);

  /** A request for one metric over one window. */
  record MetricQuery(String serviceName, String metricName, TimeWindow window, Duration period) {
    public MetricQuery {
      Objects.requireNonNull(serviceName, "serviceName must not be null");
      Objects.requireNonNull(metricName, "metricName must not be null");
      Objects.requireNonNull(window, "window must not be null");
      Objects.requireNonNull(period, "period must not be null");
      if (period.isZero() || period.isNegative()) {
        throw new IllegalArgumentException("period must be positive");
      }
      long expected = window.duration().dividedBy(period);
      if (expected > MAX_DATAPOINTS) {
        throw new IllegalArgumentException(
            "a %s window at %s resolution would yield %d datapoints, above the %d limit; widen "
                    .formatted(window.duration(), period, expected, MAX_DATAPOINTS)
                + "the period or narrow the window");
      }
    }
  }

  /** The answer: an ordered series, plus whether the source had data at all. */
  record MetricSeries(String metricName, String unit, List<MetricPoint> points) {
    public MetricSeries {
      Objects.requireNonNull(metricName, "metricName must not be null");
      Objects.requireNonNull(unit, "unit must not be null");
      points = List.copyOf(points);
      if (points.size() > MAX_DATAPOINTS) {
        throw new IllegalArgumentException("series exceeds " + MAX_DATAPOINTS + " datapoints");
      }
    }

    /** Whether the source returned nothing. Distinct from a failure to read. */
    public boolean isEmpty() {
      return points.isEmpty();
    }

    public double average() {
      return points.stream().mapToDouble(MetricPoint::value).average().orElse(Double.NaN);
    }

    public double max() {
      return points.stream().mapToDouble(MetricPoint::value).max().orElse(Double.NaN);
    }
  }

  record MetricPoint(Instant timestamp, double value) {
    public MetricPoint {
      Objects.requireNonNull(timestamp, "timestamp must not be null");
    }
  }
}
