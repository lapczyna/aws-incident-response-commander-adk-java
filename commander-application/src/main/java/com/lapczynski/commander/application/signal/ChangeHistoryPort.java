package com.lapczynski.commander.application.signal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Reads recent infrastructure changes, from CloudTrail in AWS mode.
 *
 * <p>Change events carry user-supplied fields, so this port's output is <strong>untrusted</strong>
 * in the same way logs are. A deployment description is an excellent place to hide an instruction
 * aimed at a model.
 */
public interface ChangeHistoryPort {

  int MAX_EVENTS = 50;

  /**
   * Changes affecting a service, newest first.
   *
   * @throws SignalSourceException if the change history cannot be read
   */
  List<ChangeEvent> recentChanges(String serviceName, TimeWindow window, int limit);

  record ChangeEvent(
      String eventId,
      String eventName,
      String eventSource,
      String performedBy,
      String resourceArn,
      String detail,
      Instant occurredAt) {

    public ChangeEvent {
      Objects.requireNonNull(eventId, "eventId must not be null");
      Objects.requireNonNull(eventName, "eventName must not be null");
      Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    /** Whether this change altered what is running, as opposed to reading or tagging. */
    public boolean isDeployment() {
      return eventName.contains("UpdateService")
          || eventName.contains("RegisterTaskDefinition")
          || eventName.contains("CreateService");
    }
  }
}
