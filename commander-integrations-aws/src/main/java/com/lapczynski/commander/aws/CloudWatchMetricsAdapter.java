package com.lapczynski.commander.aws;

import com.lapczynski.commander.application.signal.MetricsPort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

/**
 * Reads metrics from CloudWatch.
 *
 * <p>Satisfies exactly the port the simulator implements, so the investigation code above it is
 * identical in both modes and neither can drift into behaviour the other cannot reproduce.
 *
 * <p>Uses {@code GetMetricStatistics} rather than {@code GetMetricData}. The latter is more capable
 * and is the right choice for a dashboard; here a single metric over a single window is all that is
 * ever asked for, and the simpler call has a simpler failure surface and no query-language layer
 * between the request and the answer.
 */
public class CloudWatchMetricsAdapter implements MetricsPort {

  private static final Logger log = LoggerFactory.getLogger(CloudWatchMetricsAdapter.class);

  /** Metrics whose useful statistic is a percentile rather than an average. */
  private static final String P99 = "p99";

  private final CloudWatchClient cloudWatch;
  private final String namespace;
  private final String dimensionName;

  /**
   * @param namespace the CloudWatch namespace, for example {@code AWS/ApplicationELB}
   * @param dimensionName the dimension identifying a service, for example {@code TargetGroup}
   */
  public CloudWatchMetricsAdapter(
      CloudWatchClient cloudWatch, String namespace, String dimensionName) {
    this.cloudWatch = cloudWatch;
    this.namespace = namespace;
    this.dimensionName = dimensionName;
  }

  @Override
  public MetricSeries query(MetricQuery query) {
    // The port's constructor already rejected a window that would exceed MAX_DATAPOINTS, so the
    // response cannot be unbounded regardless of what CloudWatch returns.
    boolean percentile = query.metricName().toLowerCase(java.util.Locale.ROOT).contains("p99");

    GetMetricStatisticsRequest.Builder request =
        GetMetricStatisticsRequest.builder()
            .namespace(namespace)
            .metricName(stripStatisticSuffix(query.metricName()))
            .dimensions(Dimension.builder().name(dimensionName).value(query.serviceName()).build())
            .startTime(query.window().from())
            .endTime(query.window().to())
            .period((int) query.period().toSeconds());

    if (percentile) {
      request.extendedStatistics(P99);
    } else {
      request.statistics(Statistic.AVERAGE);
    }

    GetMetricStatisticsResponse response;
    try {
      response = cloudWatch.getMetricStatistics(request.build());
    } catch (Exception e) {
      log.warn(
          "CloudWatch metric query failed: metric={} error={}", query.metricName(), e.toString());
      throw AwsFailures.translate("CloudWatch metrics", e);
    }

    List<MetricPoint> points = new ArrayList<>();
    for (Datapoint datapoint : response.datapoints()) {
      Double value = percentile ? datapoint.extendedStatistics().get(P99) : datapoint.average();
      if (value != null) {
        points.add(new MetricPoint(datapoint.timestamp(), value));
      }
    }

    // CloudWatch returns datapoints unordered. Sorting here rather than leaving it to the caller
    // matters: the tool detects a level change by comparing the start of the window with the end,
    // and unordered input would make that comparison meaningless without failing.
    points.sort(Comparator.comparing(MetricPoint::timestamp));

    if (points.size() > MetricsPort.MAX_DATAPOINTS) {
      points = points.subList(points.size() - MetricsPort.MAX_DATAPOINTS, points.size());
    }

    // unitAsString() is null when CloudWatch reports no unit for the metric, which is common for
    // custom metrics. Defaulting here rather than propagating: a missing unit is a cosmetic gap in
    // a report, and an NPE in the middle of an investigation is not.
    String unit =
        response.datapoints().isEmpty() ? "None" : response.datapoints().getFirst().unitAsString();

    return new MetricSeries(query.metricName(), unit == null ? "None" : unit, points);
  }

  /**
   * Removes a trailing statistic hint from a metric name.
   *
   * <p>The port's vocabulary uses names like {@code TargetResponseTimeP99}, which reads clearly in
   * a prompt. CloudWatch wants the metric {@code TargetResponseTime} with the statistic supplied
   * separately, so the translation happens here rather than leaking CloudWatch's split into the
   * agent's vocabulary.
   */
  private static String stripStatisticSuffix(String metricName) {
    if (metricName.endsWith("P99")) {
      return metricName.substring(0, metricName.length() - 3);
    }
    return metricName;
  }
}
