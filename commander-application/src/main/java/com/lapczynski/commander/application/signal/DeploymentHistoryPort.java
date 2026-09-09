package com.lapczynski.commander.application.signal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads the application's own version history.
 *
 * <p>Separate from {@link EcsPort#deployments} because "which version of the code is running" and
 * "which ECS deployment is primary" are different questions, and a rollback needs both: the target
 * version, and the task definition that carried it.
 */
public interface DeploymentHistoryPort {

  int MAX_VERSIONS = 20;

  /**
   * Versions deployed to a service, newest first.
   *
   * @throws SignalSourceException if the history cannot be read
   */
  List<DeployedVersion> history(String serviceName, int limit);

  /** The last version deployed before {@code current} that was marked healthy. */
  default Optional<DeployedVersion> lastKnownGood(String serviceName) {
    return history(serviceName, MAX_VERSIONS).stream()
        .filter(version -> !version.current())
        .filter(DeployedVersion::healthy)
        .findFirst();
  }

  record DeployedVersion(
      String version,
      String taskDefinition,
      String deployedBy,
      boolean current,
      boolean healthy,
      Instant deployedAt) {

    public DeployedVersion {
      Objects.requireNonNull(version, "version must not be null");
      Objects.requireNonNull(taskDefinition, "taskDefinition must not be null");
      Objects.requireNonNull(deployedAt, "deployedAt must not be null");
    }
  }
}
