package com.lapczynski.commander.aws;

import com.lapczynski.commander.application.port.RemediationExecutorPort;
import com.lapczynski.commander.application.signal.AlarmsPort;
import com.lapczynski.commander.application.signal.ChangeHistoryPort;
import com.lapczynski.commander.application.signal.DeploymentHistoryPort;
import com.lapczynski.commander.application.signal.EcsPort;
import com.lapczynski.commander.application.signal.LogsPort;
import com.lapczynski.commander.application.signal.MetricsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.ecs.EcsClient;

/**
 * Publishes the AWS adapters as the six signal ports.
 *
 * <p>Active only under the {@code aws} profile. Without it the simulator satisfies the same ports,
 * which is what lets the whole system run and be tested with no AWS account — and it means that
 * activating this profile is the single, visible act that makes investigations read something real.
 *
 * <p>Every name below is scoped: a log-group prefix, an alarm-name prefix, one cluster. Those are
 * not conveniences. They are the bounds on what an investigation can see, and they match the
 * resource scoping in the read IAM policy, so a misconfiguration here produces an evidence gap
 * rather than a wider read than intended.
 */
@Configuration
@Profile("aws")
public class AwsSignalConfiguration {

  private static final Logger log = LoggerFactory.getLogger(AwsSignalConfiguration.class);

  @Bean
  public MetricsPort cloudWatchMetrics(
      CloudWatchClient cloudWatch,
      @Value("${commander.aws.metric-namespace:AWS/ApplicationELB}") String namespace,
      @Value("${commander.aws.metric-dimension:TargetGroup}") String dimension) {
    log.info("Signal source: CloudWatch metrics, namespace={} dimension={}", namespace, dimension);
    return new CloudWatchMetricsAdapter(cloudWatch, namespace, dimension);
  }

  @Bean
  public LogsPort cloudWatchLogs(
      CloudWatchLogsClient logs,
      @Value("${commander.aws.log-group-prefix:/aws/ecs/commander-}") String prefix) {
    return new CloudWatchLogsAdapter(logs, prefix);
  }

  @Bean
  public AlarmsPort cloudWatchAlarms(
      CloudWatchClient cloudWatch,
      @Value("${commander.aws.alarm-prefix:commander-}") String prefix) {
    return new CloudWatchAlarmsAdapter(cloudWatch, prefix);
  }

  @Bean
  public EcsPort ecs(EcsClient ecs, @Value("${commander.aws.cluster:commander}") String cluster) {
    return new EcsAdapter(ecs, cluster);
  }

  @Bean
  public ChangeHistoryPort cloudTrailChanges(CloudTrailClient cloudTrail) {
    return new CloudTrailAdapter(cloudTrail);
  }

  @Bean
  public DeploymentHistoryPort ecsDeployments(
      EcsClient ecs, @Value("${commander.aws.cluster:commander}") String cluster) {
    return new EcsAdapter(ecs, cluster);
  }

  /**
   * The only object in this deployment that can change anything in AWS.
   *
   * <p>Published unconditionally under this profile rather than behind the actions-enabled flag,
   * because that flag is enforced by the policy engine and re-enforced inside the remediation tool.
   * Gating the bean as well would give a third, quieter answer to a question that already has two
   * loud ones — and would make a misconfiguration look like a missing bean rather than a refusal.
   */
  @Bean
  public RemediationExecutorPort awsRemediationExecutor(
      EcsClient ecs,
      @Value("${commander.aws.cluster:commander}") String cluster,
      @Value("${commander.policy.required-tag.key:Project}") String tagKey,
      @Value("${commander.policy.required-tag.value:aws-incident-response-commander}")
          String tagValue) {
    log.info("Remediation executor: ECS cluster={} requiredTag={}={}", cluster, tagKey, tagValue);
    return new AwsRemediationExecutor(ecs, cluster, tagKey, tagValue);
  }
}
