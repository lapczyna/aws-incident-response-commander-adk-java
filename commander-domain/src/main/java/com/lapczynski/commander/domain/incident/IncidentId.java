package com.lapczynski.commander.domain.incident;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of an incident.
 *
 * <p>A wrapper rather than a bare {@link UUID} so that an incident id cannot be passed where an
 * evidence id or approval id is expected. Correlation identifiers are load-bearing here: they tie
 * evidence to hypotheses to approvals to executed actions, and mixing them up would corrupt the
 * audit trail.
 */
public record IncidentId(UUID value) {

  public IncidentId {
    Objects.requireNonNull(value, "incident id must not be null");
  }

  public static IncidentId newId() {
    return new IncidentId(UUID.randomUUID());
  }

  public static IncidentId of(String value) {
    return new IncidentId(UUID.fromString(value));
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
