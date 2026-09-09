package com.lapczynski.commander.domain.approval;

import java.util.Objects;

/**
 * A human or system principal that acted.
 *
 * <p>Recorded on every approval decision and audit entry, because "who approved this" is the first
 * question asked after an incident and the audit trail has to answer it without inference.
 */
public record Actor(String id, String displayName, ActorRole role) {

  /** The system itself, used for transitions no human initiated. Can never approve. */
  public static final Actor SYSTEM = new Actor("system", "Incident Commander", ActorRole.VIEWER);

  public Actor {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(displayName, "displayName must not be null");
    Objects.requireNonNull(role, "role must not be null");
    if (id.isBlank()) {
      throw new IllegalArgumentException("actor id must not be blank");
    }
  }

  public boolean isSystem() {
    return SYSTEM.id.equals(id);
  }
}
