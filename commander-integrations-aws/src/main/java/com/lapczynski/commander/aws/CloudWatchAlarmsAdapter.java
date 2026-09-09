package com.lapczynski.commander.aws;

import com.lapczynski.commander.application.signal.AlarmsPort;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsRequest;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;

/**
 * Reads alarm state from CloudWatch.
 *
 * <p>Returns alarms in every state, not only firing ones. {@code INSUFFICIENT_DATA} is the reason:
 * an alarm with no data supports no conclusion in either direction, and filtering it out would
 * present its absence as reassurance. The port exposes {@code hasNoData()} so the distinction
 * survives all the way into the prompt.
 */
public class CloudWatchAlarmsAdapter implements AlarmsPort {

  private static final Logger log = LoggerFactory.getLogger(CloudWatchAlarmsAdapter.class);

  private final CloudWatchClient cloudWatch;
  private final String alarmNamePrefix;

  public CloudWatchAlarmsAdapter(CloudWatchClient cloudWatch, String alarmNamePrefix) {
    this.cloudWatch = cloudWatch;
    this.alarmNamePrefix = alarmNamePrefix;
  }

  @Override
  public List<AlarmState> activeAlarms(String serviceName) {
    DescribeAlarmsRequest request =
        DescribeAlarmsRequest.builder()
            .alarmNamePrefix(alarmNamePrefix + serviceName)
            .maxRecords(AlarmsPort.MAX_ALARMS)
            .build();

    DescribeAlarmsResponse response;
    try {
      response = cloudWatch.describeAlarms(request);
    } catch (Exception e) {
      log.warn("CloudWatch alarm query failed: service={} error={}", serviceName, e.toString());
      throw AwsFailures.translate("CloudWatch alarms", e);
    }

    List<AlarmState> alarms = new ArrayList<>();
    for (MetricAlarm alarm : response.metricAlarms()) {
      alarms.add(
          new AlarmState(
              alarm.alarmName(),
              alarm.stateValueAsString(),
              alarm.stateReason() == null ? "" : alarm.stateReason(),
              alarm.metricName() == null ? "" : alarm.metricName(),
              alarm.threshold() == null ? 0.0 : alarm.threshold(),
              alarm.stateUpdatedTimestamp()));
    }
    return alarms;
  }
}
