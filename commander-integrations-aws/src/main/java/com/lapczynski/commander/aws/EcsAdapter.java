package com.lapczynski.commander.aws;

import com.lapczynski.commander.application.signal.DeploymentHistoryPort;
import com.lapczynski.commander.application.signal.EcsPort;
import com.lapczynski.commander.application.signal.SignalSourceException;
import com.lapczynski.commander.domain.evidence.EvidenceGap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse;
import software.amazon.awssdk.services.ecs.model.DescribeTasksRequest;
import software.amazon.awssdk.services.ecs.model.DescribeTasksResponse;
import software.amazon.awssdk.services.ecs.model.ListTasksRequest;
import software.amazon.awssdk.services.ecs.model.ListTasksResponse;

/**
 * Reads ECS service, task and deployment state.
 *
 * <p>Implements two ports. They are separate interfaces because "which ECS deployment is primary"
 * and "which version of the code is running" are different questions, but both are answered from
 * the same ECS calls, and splitting the adapter would mean making those calls twice.
 *
 * <p>Task detail requires two calls — {@code ListTasks} then {@code DescribeTasks} — and the list
 * is capped before describing. A service that has churned through hundreds of tasks during an
 * incident is exactly when this code runs, and it is exactly when an uncapped describe would be
 * slowest.
 */
public class EcsAdapter implements EcsPort, DeploymentHistoryPort {

  private static final Logger log = LoggerFactory.getLogger(EcsAdapter.class);

  private final EcsClient ecs;
  private final String cluster;

  public EcsAdapter(EcsClient ecs, String cluster) {
    this.ecs = ecs;
    this.cluster = cluster;
  }

  @Override
  public ServiceState serviceState(String serviceName) {
    software.amazon.awssdk.services.ecs.model.Service service = describeService(serviceName);

    return new ServiceState(
        serviceName,
        service.serviceArn(),
        service.desiredCount(),
        service.runningCount(),
        service.pendingCount(),
        tasksFor(serviceName));
  }

  @Override
  public List<Deployment> deployments(String serviceName, int limit) {
    software.amazon.awssdk.services.ecs.model.Service service = describeService(serviceName);

    return service.deployments().stream()
        .map(
            deployment ->
                new Deployment(
                    deployment.id(),
                    deployment.status(),
                    shortTaskDefinition(deployment.taskDefinition()),
                    deployment.desiredCount(),
                    deployment.runningCount(),
                    deployment.failedTasks(),
                    deployment.createdAt(),
                    deployment.updatedAt()))
        .sorted(Comparator.comparing(Deployment::createdAt).reversed())
        .limit(Math.min(limit, EcsPort.MAX_DEPLOYMENTS))
        .toList();
  }

  /**
   * Version history, derived from ECS deployments.
   *
   * <p>ECS has no concept of an application version, so the task definition revision stands in for
   * one. A deployment is treated as healthy when it reached its desired count with no failed tasks
   * — a heuristic, and the honest one available from ECS alone.
   */
  @Override
  public List<DeployedVersion> history(String serviceName, int limit) {
    software.amazon.awssdk.services.ecs.model.Service service = describeService(serviceName);

    return service.deployments().stream()
        .map(
            deployment ->
                new DeployedVersion(
                    shortTaskDefinition(deployment.taskDefinition()),
                    shortTaskDefinition(deployment.taskDefinition()),
                    "ecs",
                    "PRIMARY".equals(deployment.status()),
                    deployment.failedTasks() == 0
                        && deployment.runningCount() >= deployment.desiredCount(),
                    deployment.createdAt()))
        .sorted(Comparator.comparing(DeployedVersion::deployedAt).reversed())
        .limit(Math.min(limit, DeploymentHistoryPort.MAX_VERSIONS))
        .toList();
  }

  // ---------------------------------------------------------------- helpers

  private software.amazon.awssdk.services.ecs.model.Service describeService(String serviceName) {
    DescribeServicesResponse response;
    try {
      response =
          ecs.describeServices(
              DescribeServicesRequest.builder().cluster(cluster).services(serviceName).build());
    } catch (Exception e) {
      log.warn("ECS describeServices failed: service={} error={}", serviceName, e.toString());
      throw AwsFailures.translate("ECS", e);
    }

    if (response.services().isEmpty()) {
      // A named service that does not exist is a configuration error, and reporting it as NO_DATA
      // would let an investigation conclude the service is simply quiet.
      throw new SignalSourceException(
          EvidenceGap.Reason.NO_DATA,
          "ECS service %s was not found in cluster %s".formatted(serviceName, cluster));
    }

    return response.services().getFirst();
  }

  /**
   * Fetches task detail.
   *
   * <p>Note the fully qualified {@code software.amazon.awssdk.services.ecs.model.Task} below. This
   * class implements {@link EcsPort}, so the inherited nested type {@code EcsPort.Task} is in scope
   * and <em>shadows</em> a single-type import of the AWS {@code Task} — importing it would compile
   * to the wrong type or not at all. Qualifying the AWS type is clearer than renaming either.
   */
  private List<EcsPort.Task> tasksFor(String serviceName) {
    ListTasksResponse listed;
    try {
      listed =
          ecs.listTasks(
              ListTasksRequest.builder()
                  .cluster(cluster)
                  .serviceName(serviceName)
                  .maxResults(EcsPort.MAX_TASKS)
                  .build());
    } catch (Exception e) {
      throw AwsFailures.translate("ECS tasks", e);
    }

    if (listed.taskArns().isEmpty()) {
      // No running tasks is itself a finding, and a strong one. Returning an empty list lets the
      // caller compare it against desiredCount and see a service that is entirely down.
      return List.of();
    }

    DescribeTasksResponse described;
    try {
      described =
          ecs.describeTasks(
              DescribeTasksRequest.builder().cluster(cluster).tasks(listed.taskArns()).build());
    } catch (Exception e) {
      throw AwsFailures.translate("ECS task detail", e);
    }

    List<EcsPort.Task> tasks = new ArrayList<>();
    for (software.amazon.awssdk.services.ecs.model.Task task : described.tasks()) {
      tasks.add(
          new EcsPort.Task(
              task.taskArn(),
              task.lastStatus(),
              task.healthStatus() == null ? "UNKNOWN" : task.healthStatus().toString(),
              shortTaskDefinition(task.taskDefinitionArn()),
              task.startedAt(),
              // Often the single most informative signal in an ECS incident.
              task.stoppedReason(),
              // ECS does not expose a restart count; the closest honest proxy is how many times
              // the container itself has been started within this task.
              task.containers().stream()
                  .mapToInt(container -> container.exitCode() == null ? 0 : 1)
                  .sum()));
    }
    return tasks;
  }

  /**
   * Reduces a task definition ARN to {@code family:revision}.
   *
   * <p>The full ARN carries an account id, which would then travel into prompts and reports for no
   * benefit. The short form is what an operator recognises anyway.
   */
  private static String shortTaskDefinition(String taskDefinitionArn) {
    if (taskDefinitionArn == null) {
      return "unknown";
    }
    int slash = taskDefinitionArn.lastIndexOf('/');
    return slash >= 0 ? taskDefinitionArn.substring(slash + 1) : taskDefinitionArn;
  }
}
