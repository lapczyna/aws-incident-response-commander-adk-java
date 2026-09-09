package com.lapczynski.commander.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lapczynski.commander.application.signal.AlarmsPort;
import com.lapczynski.commander.application.signal.EcsPort;
import com.lapczynski.commander.application.signal.LogsPort;
import com.lapczynski.commander.application.signal.MetricsPort;
import com.lapczynski.commander.application.signal.SignalSourceException;
import com.lapczynski.commander.application.signal.TimeWindow;
import com.lapczynski.commander.domain.evidence.EvidenceGap;
import com.lapczynski.commander.domain.remediation.ActionType;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import com.lapczynski.commander.domain.remediation.ResourceRef;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsRequest;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsResponse;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;
import software.amazon.awssdk.services.cloudwatch.model.StateValue;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.StopTaskRequest;
import software.amazon.awssdk.services.ecs.model.Tag;
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest;

/**
 * The AWS adapters, against mocked SDK clients.
 *
 * <p>Mocks rather than WireMock: the SDK builds typed responses, so a mock exercises the mapping
 * and failure handling that this code actually owns, without also testing Amazon's HTTP
 * serialisation. These tests never reach the network and never require credentials — the whole
 * suite is tagged {@code aws-live} for anything that would.
 *
 * <p>The emphasis is on the guarantees rather than the plumbing: failures become the right kind of
 * evidence gap, results stay bounded, and nothing acts on an untagged resource.
 */
class AwsAdapterTest {

  private static final TimeWindow WINDOW =
      TimeWindow.endingAt(Instant.parse("2026-09-09T12:00:00Z"), Duration.ofHours(1));

  @Nested
  @DisplayName("failures become the right kind of evidence gap")
  class FailureTranslation {

    private final CloudWatchClient cloudWatch = mock(CloudWatchClient.class);
    private final CloudWatchMetricsAdapter adapter =
        new CloudWatchMetricsAdapter(cloudWatch, "AWS/ApplicationELB", "TargetGroup");

    private MetricsPort.MetricQuery query() {
      return new MetricsPort.MetricQuery(
          "checkout", "CPUUtilization", WINDOW, Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("a timeout is a TIMEOUT gap, not a generic error")
    void timeoutMapsToTimeout() {
      when(cloudWatch.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
          .thenThrow(ApiCallTimeoutException.builder().message("too slow").build());

      assertThatThrownBy(() -> adapter.query(query()))
          .isInstanceOf(SignalSourceException.class)
          .extracting(e -> ((SignalSourceException) e).reason())
          .isEqualTo(EvidenceGap.Reason.TIMEOUT);
    }

    @Test
    @DisplayName("access denied is called out as a permissions problem, not an incident symptom")
    void accessDeniedIsDistinct() {
      when(cloudWatch.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
          .thenThrow(
              AwsServiceException.builder()
                  .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build())
                  .statusCode(403)
                  .build());

      assertThatThrownBy(() -> adapter.query(query()))
          .isInstanceOf(SignalSourceException.class)
          .satisfies(
              e -> {
                assertThat(((SignalSourceException) e).reason())
                    .isEqualTo(EvidenceGap.Reason.ACCESS_DENIED);
                assertThat(e.getMessage())
                    .as(
                        "an investigation that cannot tell a missing IAM statement from a broken "
                            + "service will report the wrong incident")
                    .contains("permissions problem");
              });
    }

    @Test
    @DisplayName("throttling is an upstream error")
    void throttlingMapsToUpstream() {
      when(cloudWatch.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
          .thenThrow(
              AwsServiceException.builder()
                  .awsErrorDetails(AwsErrorDetails.builder().errorCode("Throttling").build())
                  .statusCode(429)
                  .build());

      assertThatThrownBy(() -> adapter.query(query()))
          .extracting(e -> ((SignalSourceException) e).reason())
          .isEqualTo(EvidenceGap.Reason.UPSTREAM_ERROR);
    }

    @Test
    @DisplayName("a connection failure is an upstream error")
    void clientExceptionMapsToUpstream() {
      when(cloudWatch.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
          .thenThrow(SdkClientException.builder().message("connection refused").build());

      assertThatThrownBy(() -> adapter.query(query()))
          .extracting(e -> ((SignalSourceException) e).reason())
          .isEqualTo(EvidenceGap.Reason.UPSTREAM_ERROR);
    }

    @Test
    @DisplayName("AWS message text does not leak into the gap")
    void awsMessageIsNotPassedThrough() {
      when(cloudWatch.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
          .thenThrow(
              AwsServiceException.builder()
                  .awsErrorDetails(
                      AwsErrorDetails.builder()
                          .errorCode("ValidationError")
                          .errorMessage(
                              "User arn:aws:sts::123456789012:assumed-role/secret-role is not "
                                  + "authorized")
                          .build())
                  .statusCode(400)
                  .build());

      assertThatThrownBy(() -> adapter.query(query()))
          .as(
              "AWS error text carries ARNs and account ids that would travel into a prompt and "
                  + "an incident report")
          .hasMessageNotContaining("assumed-role")
          .hasMessageNotContaining("123456789012");
    }
  }

  @Nested
  @DisplayName("metrics")
  class Metrics {

    private final CloudWatchClient cloudWatch = mock(CloudWatchClient.class);
    private final CloudWatchMetricsAdapter adapter =
        new CloudWatchMetricsAdapter(cloudWatch, "AWS/ApplicationELB", "TargetGroup");

    @Test
    @DisplayName("datapoints are returned in chronological order whatever CloudWatch returns")
    void datapointsAreSorted() {
      Instant base = Instant.parse("2026-09-09T11:00:00Z");
      when(cloudWatch.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
          .thenReturn(
              GetMetricStatisticsResponse.builder()
                  .datapoints(
                      // Deliberately out of order, as CloudWatch genuinely returns them.
                      Datapoint.builder().timestamp(base.plusSeconds(120)).average(3.0).build(),
                      Datapoint.builder().timestamp(base).average(1.0).build(),
                      Datapoint.builder().timestamp(base.plusSeconds(60)).average(2.0).build())
                  .build());

      MetricsPort.MetricSeries series =
          adapter.query(
              new MetricsPort.MetricQuery(
                  "checkout", "CPUUtilization", WINDOW, Duration.ofMinutes(1)));

      assertThat(series.points())
          .as(
              "the tool detects a level change by comparing the window's start with its end; "
                  + "unordered input makes that comparison meaningless without failing")
          .extracting(MetricsPort.MetricPoint::value)
          .containsExactly(1.0, 2.0, 3.0);
    }

    @Test
    @DisplayName("an empty response is a series with no data, not an error")
    void emptyResponseIsNoData() {
      when(cloudWatch.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
          .thenReturn(GetMetricStatisticsResponse.builder().build());

      assertThat(
              adapter
                  .query(
                      new MetricsPort.MetricQuery(
                          "checkout", "CPUUtilization", WINDOW, Duration.ofMinutes(1)))
                  .isEmpty())
          .as("'the source had nothing' and 'the source failed' support different conclusions")
          .isTrue();
    }
  }

  @Nested
  @DisplayName("logs")
  class Logs {

    private final CloudWatchLogsClient logs = mock(CloudWatchLogsClient.class);
    private final CloudWatchLogsAdapter adapter =
        new CloudWatchLogsAdapter(logs, "/aws/ecs/commander-demo/");

    private FilteredLogEvent event(String message, long offsetSeconds) {
      return FilteredLogEvent.builder()
          .message(message)
          .timestamp(
              Instant.parse("2026-09-09T11:30:00Z").plusSeconds(offsetSeconds).toEpochMilli())
          .logStreamName("stream-1")
          .build();
    }

    @Test
    @DisplayName("results are bounded and truncation is reported")
    void resultsAreBoundedAndTruncationReported() {
      List<FilteredLogEvent> many =
          java.util.stream.IntStream.range(0, 30)
              .mapToObj(i -> event("ERROR connection timed out #" + i, i))
              .toList();

      when(logs.filterLogEvents(any(FilterLogEventsRequest.class)))
          .thenReturn(FilterLogEventsResponse.builder().events(many).build());

      LogsPort.LogQueryResult result =
          adapter.query(new LogsPort.LogQuery("checkout", "timed out", WINDOW, 5));

      assertThat(result.entries()).hasSize(5);
      assertThat(result.truncated())
          .as("a conclusion drawn from a partial view must be able to say it was partial")
          .isTrue();
      assertThat(result.totalMatched()).isEqualTo(30);
    }

    @Test
    @DisplayName("the pattern is applied as a regex, matching the simulator's contract")
    void patternIsARegex() {
      when(logs.filterLogEvents(any(FilterLogEventsRequest.class)))
          .thenReturn(
              FilterLogEventsResponse.builder()
                  .events(
                      event("ERROR java.lang.OutOfMemoryError: Java heap space", 0),
                      event("INFO processed 42 requests", 1))
                  .build());

      LogsPort.LogQueryResult result =
          adapter.query(new LogsPort.LogQuery("checkout", "OutOfMemory|Timeout", WINDOW, 10));

      assertThat(result.entries())
          .as(
              "CloudWatch's filter syntax is not a regex; applying it locally keeps one contract "
                  + "across AWS and simulator modes")
          .hasSize(1);
      assertThat(result.entries().getFirst().level()).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("a malformed pattern is a bad request, not an evidence gap")
    void malformedPatternIsArgumentError() {
      assertThatThrownBy(
              () -> adapter.query(new LogsPort.LogQuery("checkout", "[unclosed", WINDOW, 10)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("alarms")
  class Alarms {

    private final CloudWatchClient cloudWatch = mock(CloudWatchClient.class);
    private final CloudWatchAlarmsAdapter adapter =
        new CloudWatchAlarmsAdapter(cloudWatch, "commander-");

    @Test
    @DisplayName("INSUFFICIENT_DATA is returned, not filtered out as if it were healthy")
    void insufficientDataIsPreserved() {
      when(cloudWatch.describeAlarms(any(DescribeAlarmsRequest.class)))
          .thenReturn(
              DescribeAlarmsResponse.builder()
                  .metricAlarms(
                      MetricAlarm.builder()
                          .alarmName("commander-checkout-5xx")
                          .stateValue(StateValue.INSUFFICIENT_DATA)
                          .stateReason("no data")
                          .metricName("HTTPCode_Target_5XX_Count")
                          .threshold(10.0)
                          .stateUpdatedTimestamp(Instant.parse("2026-09-09T11:00:00Z"))
                          .build())
                  .build());

      List<AlarmsPort.AlarmState> alarms = adapter.activeAlarms("checkout");

      assertThat(alarms).hasSize(1);
      assertThat(alarms.getFirst().hasNoData())
          .as("filtering these out would present an alarm's silence as reassurance")
          .isTrue();
      assertThat(alarms.getFirst().isFiring()).isFalse();
    }
  }

  @Nested
  @DisplayName("the executor refuses to touch anything untagged")
  class ExecutorGuards {

    private static final String ARN =
        "arn:aws:ecs:eu-west-1:123456789012:service/commander-demo/checkout";

    private ProposedAction action(ActionType type, Map<String, String> args) {
      return new ProposedAction(
          type,
          new ResourceRef(ARN, "123456789012", "eu-west-1", "demo", "ecs:service"),
          args,
          "test action");
    }

    private DescribeServicesResponse serviceWithTags(Tag... tags) {
      return DescribeServicesResponse.builder()
          .services(Service.builder().serviceArn(ARN).tags(tags).build())
          .build();
    }

    @Test
    @DisplayName("an untagged resource is refused even though every earlier check passed")
    void untaggedResourceIsRefused() {
      EcsClient ecs = mock(EcsClient.class);
      when(ecs.describeServices(any(DescribeServicesRequest.class)))
          .thenReturn(
              serviceWithTags(Tag.builder().key("Project").value("something-else").build()));

      AwsRemediationExecutor executor =
          new AwsRemediationExecutor(
              ecs, "commander-demo", "Project", "aws-incident-response-commander");

      assertThatThrownBy(() -> executor.execute(action(ActionType.RESTART_ECS_TASK, Map.of())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("not demo infrastructure");

      verify(ecs, never()).stopTask(any(StopTaskRequest.class));
      verify(ecs, never()).updateService(any(UpdateServiceRequest.class));
    }

    @Test
    @DisplayName("a resource with no tags at all is refused")
    void untaggedEntirelyIsRefused() {
      EcsClient ecs = mock(EcsClient.class);
      when(ecs.describeServices(any(DescribeServicesRequest.class))).thenReturn(serviceWithTags());

      AwsRemediationExecutor executor =
          new AwsRemediationExecutor(
              ecs, "commander-demo", "Project", "aws-incident-response-commander");

      assertThatThrownBy(
              () ->
                  executor.execute(
                      action(ActionType.SCALE_ECS_SERVICE, Map.of("desiredCount", "2"))))
          .isInstanceOf(IllegalStateException.class);

      verify(ecs, never()).updateService(any(UpdateServiceRequest.class));
    }

    @Test
    @DisplayName("a rollback with no target task definition is refused rather than guessed at")
    void rollbackWithoutTargetIsRefused() {
      EcsClient ecs = mock(EcsClient.class);
      when(ecs.describeServices(any(DescribeServicesRequest.class)))
          .thenReturn(
              serviceWithTags(
                  Tag.builder().key("Project").value("aws-incident-response-commander").build()));

      AwsRemediationExecutor executor =
          new AwsRemediationExecutor(
              ecs, "commander-demo", "Project", "aws-incident-response-commander");

      assertThatThrownBy(() -> executor.execute(action(ActionType.ROLLBACK_DEPLOYMENT, Map.of())))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no taskDefinition");

      verify(ecs, never()).updateService(any(UpdateServiceRequest.class));
    }

    @Test
    @DisplayName("demo-fault actions are refused here rather than silently doing nothing")
    void demoFaultActionsAreRefused() {
      EcsClient ecs = mock(EcsClient.class);
      when(ecs.describeServices(any(DescribeServicesRequest.class)))
          .thenReturn(
              serviceWithTags(
                  Tag.builder().key("Project").value("aws-incident-response-commander").build()));

      AwsRemediationExecutor executor =
          new AwsRemediationExecutor(
              ecs, "commander-demo", "Project", "aws-incident-response-commander");

      assertThatThrownBy(() -> executor.execute(action(ActionType.DEACTIVATE_DEMO_FAULT, Map.of())))
          .as("a silent no-op would be reported to an operator as a successful remediation")
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("demo fault API");
    }
  }

  @Nested
  @DisplayName("client configuration is bounded")
  class ClientBounds {

    @Test
    @DisplayName("timeouts and retries are set, and short enough to fit an investigation")
    void boundsAreConfigured() {
      assertThat(AwsClientConfiguration.attemptTimeout())
          .as("a source that cannot answer quickly is better reported as a gap than waited on")
          .isLessThanOrEqualTo(Duration.ofSeconds(10));
      assertThat(AwsClientConfiguration.callTimeout())
          .isLessThanOrEqualTo(Duration.ofSeconds(30))
          .isGreaterThan(AwsClientConfiguration.attemptTimeout());
      assertThat(AwsClientConfiguration.maxRetries())
          .as(
              "retrying an AccessDenied three times turns a clear permissions problem into a "
                  + "slow one")
          .isLessThanOrEqualTo(2);
    }
  }

  @Nested
  @DisplayName("ECS")
  class Ecs {

    @Test
    @DisplayName("a service short of its desired count reports as degraded")
    void degradedServiceIsVisible() {
      EcsClient ecs = mock(EcsClient.class);
      when(ecs.describeServices(any(DescribeServicesRequest.class)))
          .thenReturn(
              DescribeServicesResponse.builder()
                  .services(
                      Service.builder()
                          .serviceArn("arn:aws:ecs:eu-west-1:123456789012:service/demo/checkout")
                          .desiredCount(3)
                          .runningCount(1)
                          .pendingCount(2)
                          .build())
                  .build());
      when(ecs.listTasks(any(software.amazon.awssdk.services.ecs.model.ListTasksRequest.class)))
          .thenReturn(
              software.amazon.awssdk.services.ecs.model.ListTasksResponse.builder().build());

      EcsPort.ServiceState state = new EcsAdapter(ecs, "commander-demo").serviceState("checkout");

      assertThat(state.isDegraded()).isTrue();
      assertThat(state.runningCount()).isEqualTo(1);
      assertThat(state.tasks())
          .as(
              "no running tasks is itself a strong finding, reported as an empty list rather than "
                  + "an error")
          .isEmpty();
    }

    @Test
    @DisplayName("a missing service is NO_DATA with an explanation, not a silent empty result")
    void missingServiceIsReported() {
      EcsClient ecs = mock(EcsClient.class);
      when(ecs.describeServices(any(DescribeServicesRequest.class)))
          .thenReturn(DescribeServicesResponse.builder().build());

      assertThatThrownBy(() -> new EcsAdapter(ecs, "commander-demo").serviceState("checkout"))
          .isInstanceOf(SignalSourceException.class)
          .hasMessageContaining("not found");
    }
  }
}
