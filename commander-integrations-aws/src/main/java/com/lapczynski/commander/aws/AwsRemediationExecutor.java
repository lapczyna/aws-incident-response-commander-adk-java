package com.lapczynski.commander.aws;

import com.lapczynski.commander.domain.remediation.ActionType;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import com.lapczynski.commander.domain.remediation.ResourceRef;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse;
import software.amazon.awssdk.services.ecs.model.ListTasksRequest;
import software.amazon.awssdk.services.ecs.model.StopTaskRequest;
import software.amazon.awssdk.services.ecs.model.Tag;
import software.amazon.awssdk.services.ecs.model.UpdateServiceRequest;

/**
 * The only code in this project that can change anything in AWS.
 *
 * <p>It is reached only from inside {@code RemediationTool}, which has already re-run the policy
 * engine and claimed the action's fingerprint. This class therefore does not re-implement policy;
 * it implements the small number of operations policy can authorise, and it verifies the one thing
 * the policy engine cannot: <strong>that the resource really carries the demo tag</strong>.
 *
 * <p>That check has to happen here because it is the only point with live AWS state. The policy
 * engine is given tags as an argument; if those tags came from anywhere untrustworthy, the check
 * would be theatre. Reading them from ECS immediately before acting closes that gap — and it is
 * done as a separate call rather than trusting whatever the caller passed in.
 *
 * <p>Every method is small and does exactly one AWS operation. There is no generic "call ECS" path,
 * because a generic path is one refactor away from being a general-purpose AWS capability.
 */
public class AwsRemediationExecutor {

  private static final Logger log = LoggerFactory.getLogger(AwsRemediationExecutor.class);

  private final EcsClient ecs;
  private final String cluster;
  private final String requiredTagKey;
  private final String requiredTagValue;

  public AwsRemediationExecutor(
      EcsClient ecs, String cluster, String requiredTagKey, String requiredTagValue) {
    this.ecs = ecs;
    this.cluster = cluster;
    this.requiredTagKey = requiredTagKey;
    this.requiredTagValue = requiredTagValue;
  }

  /**
   * Performs an approved action.
   *
   * @return a short description of what was done, for the incident record
   * @throws IllegalStateException if the target does not carry the required tag, or the action type
   *     has no executor
   */
  public String execute(ProposedAction action) {
    verifyTagOnLiveResource(action.target());

    return switch (action.type()) {
      case RESTART_ECS_TASK -> restartTasks(action.target());
      case SCALE_ECS_SERVICE -> scale(action);
      case ROLLBACK_DEPLOYMENT -> rollback(action);
      case RUN_HEALTH_CHECK -> healthCheck(action.target());

      // Both belong to the demo target service, which is reached over HTTP rather than through the
      // AWS SDK. Failing loudly beats a silent no-op that a report would describe as success.
      case DEACTIVATE_DEMO_FAULT, RESTORE_SAFE_CONFIGURATION ->
          throw new IllegalStateException(
              "%s is performed against the demo target service, not through AWS; it must be routed "
                      .formatted(action.type())
                  + "to the demo fault API rather than to this executor");
    };
  }

  /**
   * Confirms the live resource carries the demo tag.
   *
   * <p>The last line of defence before AWS is changed, and the only check in the system that reads
   * its input from AWS itself rather than being handed it. A resource that has been retagged, or
   * that never carried the tag, is refused here even though every earlier check passed.
   */
  private void verifyTagOnLiveResource(ResourceRef target) {
    DescribeServicesResponse response =
        ecs.describeServices(
            DescribeServicesRequest.builder()
                .cluster(cluster)
                .services(target.arn())
                .include(software.amazon.awssdk.services.ecs.model.ServiceField.TAGS)
                .build());

    if (response.services().isEmpty()) {
      throw new IllegalStateException(
          "refusing to act on %s: the service does not exist".formatted(target.arn()));
    }

    List<Tag> tags = response.services().getFirst().tags();
    boolean tagged =
        tags != null
            && tags.stream()
                .anyMatch(
                    tag ->
                        requiredTagKey.equals(tag.key()) && requiredTagValue.equals(tag.value()));

    if (!tagged) {
      log.error(
          "Refusing to act on untagged resource: arn={} requiredTag={}={}",
          target.arn(),
          requiredTagKey,
          requiredTagValue);
      throw new IllegalStateException(
          "refusing to act on %s: it does not carry %s=%s, so it is not demo infrastructure"
              .formatted(target.arn(), requiredTagKey, requiredTagValue));
    }
  }

  /**
   * Replaces running tasks by stopping them.
   *
   * <p>ECS starts replacements automatically to restore the desired count, so stopping is the whole
   * operation. Tasks are stopped one at a time and the desired count is left untouched, which means
   * the service is never taken below capacity deliberately.
   */
  private String restartTasks(ResourceRef target) {
    List<String> taskArns =
        ecs.listTasks(
                ListTasksRequest.builder()
                    .cluster(cluster)
                    .serviceName(target.arn())
                    .maxResults(10)
                    .build())
            .taskArns();

    if (taskArns.isEmpty()) {
      return "No running tasks were found to restart.";
    }

    // One task only. Restarting every task at once is an outage, not a remediation, and the
    // replacement for the first is running before anyone would ask for a second.
    String taskArn = taskArns.getFirst();
    ecs.stopTask(
        StopTaskRequest.builder()
            .cluster(cluster)
            .task(taskArn)
            .reason("Incident Commander: approved restart")
            .build());

    log.info("Stopped task for replacement: taskArn={}", taskArn);
    return "Stopped task %s; ECS will start a replacement to restore the desired count."
        .formatted(taskArn);
  }

  private String scale(ProposedAction action) {
    String desired = action.arguments().get("desiredCount");
    if (desired == null) {
      throw new IllegalStateException("scale action carried no desiredCount argument");
    }

    // Parsed rather than trusted, even though the policy engine has already bounds-checked it.
    // This is the last place the value is read before it reaches AWS.
    int desiredCount;
    try {
      desiredCount = Integer.parseInt(desired.trim());
    } catch (NumberFormatException e) {
      throw new IllegalStateException("desiredCount is not an integer: " + desired, e);
    }

    ecs.updateService(
        UpdateServiceRequest.builder()
            .cluster(cluster)
            .service(action.target().arn())
            .desiredCount(desiredCount)
            .build());

    log.info("Scaled service: arn={} desiredCount={}", action.target().arn(), desiredCount);
    return "Set desired count of %s to %d.".formatted(action.target().arn(), desiredCount);
  }

  private String rollback(ProposedAction action) {
    String taskDefinition = action.arguments().get("taskDefinition");
    if (taskDefinition == null || taskDefinition.isBlank()) {
      throw new IllegalStateException(
          "rollback action carried no taskDefinition argument, so there is no target to roll back "
              + "to");
    }

    ecs.updateService(
        UpdateServiceRequest.builder()
            .cluster(cluster)
            .service(action.target().arn())
            .taskDefinition(taskDefinition)
            // Forces a new deployment even if the task definition is unchanged, so a rollback to
            // the currently-running revision still replaces the tasks rather than silently doing
            // nothing and being reported as success.
            .forceNewDeployment(true)
            .build());

    log.info(
        "Rolled back service: arn={} taskDefinition={}", action.target().arn(), taskDefinition);
    return "Rolled %s back to task definition %s.".formatted(action.target().arn(), taskDefinition);
  }

  /** Reads current service state. Read-only, and the only action that changes nothing. */
  private String healthCheck(ResourceRef target) {
    DescribeServicesResponse response =
        ecs.describeServices(
            DescribeServicesRequest.builder().cluster(cluster).services(target.arn()).build());

    if (response.services().isEmpty()) {
      return "Service %s was not found.".formatted(target.arn());
    }

    var service = response.services().getFirst();
    return "Service %s: %d/%d tasks running, %d pending."
        .formatted(
            target.arn(), service.runningCount(), service.desiredCount(), service.pendingCount());
  }

  /** The action types this executor can perform. Used by configuration to narrow the allowlist. */
  public static Map<ActionType, Boolean> supportedActions() {
    return Map.of(
        ActionType.RESTART_ECS_TASK, true,
        ActionType.SCALE_ECS_SERVICE, true,
        ActionType.ROLLBACK_DEPLOYMENT, true,
        ActionType.RUN_HEALTH_CHECK, true,
        ActionType.DEACTIVATE_DEMO_FAULT, false,
        ActionType.RESTORE_SAFE_CONFIGURATION, false);
  }
}
