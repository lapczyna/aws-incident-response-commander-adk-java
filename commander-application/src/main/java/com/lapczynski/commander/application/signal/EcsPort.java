package com.lapczynski.commander.application.signal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Reads ECS service, task and deployment state. */
public interface EcsPort {

  int MAX_TASKS = 50;
  int MAX_DEPLOYMENTS = 20;

  /**
   * @throws SignalSourceException if service state cannot be read
   */
  ServiceState serviceState(String serviceName);

  /**
   * Deployment history, newest first.
   *
   * @throws SignalSourceException if the history cannot be read
   */
  List<Deployment> deployments(String serviceName, int limit);

  record ServiceState(
      String serviceName,
      String serviceArn,
      int desiredCount,
      int runningCount,
      int pendingCount,
      List<Task> tasks) {

    public ServiceState {
      Objects.requireNonNull(serviceName, "serviceName must not be null");
      Objects.requireNonNull(serviceArn, "serviceArn must not be null");
      tasks = List.copyOf(tasks);
      if (tasks.size() > MAX_TASKS) {
        throw new IllegalArgumentException("task list exceeds " + MAX_TASKS);
      }
    }

    /** Whether the service is short of its desired capacity. */
    public boolean isDegraded() {
      return runningCount < desiredCount;
    }
  }

  /**
   * @param stoppedReason why the task stopped, when it did. Often the single most informative
   *     signal in an ECS incident, so it is a first-class field rather than buried in a map.
   */
  record Task(
      String taskArn,
      String lastStatus,
      String healthStatus,
      String taskDefinition,
      Instant startedAt,
      String stoppedReason,
      int restartCount) {

    public Task {
      Objects.requireNonNull(taskArn, "taskArn must not be null");
      Objects.requireNonNull(lastStatus, "lastStatus must not be null");
      Objects.requireNonNull(taskDefinition, "taskDefinition must not be null");
    }

    public boolean isHealthy() {
      return "RUNNING".equals(lastStatus) && !"UNHEALTHY".equals(healthStatus);
    }
  }

  record Deployment(
      String id,
      String status,
      String taskDefinition,
      int desiredCount,
      int runningCount,
      int failedTasks,
      Instant createdAt,
      Instant updatedAt) {

    public Deployment {
      Objects.requireNonNull(id, "id must not be null");
      Objects.requireNonNull(status, "status must not be null");
      Objects.requireNonNull(taskDefinition, "taskDefinition must not be null");
      Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public boolean isPrimary() {
      return "PRIMARY".equals(status);
    }

    public boolean hasFailures() {
      return failedTasks > 0;
    }
  }
}
