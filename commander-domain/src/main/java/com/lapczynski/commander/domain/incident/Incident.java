package com.lapczynski.commander.domain.incident;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The incident aggregate.
 *
 * <p>Immutable: every lifecycle change returns a new instance with an incremented {@link #version}.
 * That version is not decoration. It is the optimistic-locking token, and it participates in the
 * approval fingerprint, so any material change to an incident invalidates every approval
 * outstanding against it (see ADR-0007). Mutating an incident in place would quietly destroy that
 * guarantee.
 *
 * @param version optimistic-locking version, incremented on every transition. A persistence layer
 *     that writes a row whose stored version differs has detected a concurrent modification and
 *     must reject the write.
 */
public record Incident(
    IncidentId id,
    String title,
    ServiceRef affectedService,
    Severity severity,
    IncidentStatus status,
    long version,
    Instant receivedAt,
    Instant updatedAt,
    Optional<String> summary,
    Optional<String> closingNote) {

  public Incident {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(title, "title must not be null");
    Objects.requireNonNull(affectedService, "affectedService must not be null");
    Objects.requireNonNull(severity, "severity must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(receivedAt, "receivedAt must not be null");
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    Objects.requireNonNull(summary, "summary must not be null");
    Objects.requireNonNull(closingNote, "closingNote must not be null");
    if (title.isBlank()) {
      throw new IllegalArgumentException("title must not be blank");
    }
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative, was " + version);
    }
    if (updatedAt.isBefore(receivedAt)) {
      throw new IllegalArgumentException("updatedAt must not precede receivedAt");
    }
  }

  /** Opens a new incident in {@link IncidentStatus#RECEIVED} at version 0. */
  public static Incident open(
      IncidentId id, String title, ServiceRef affectedService, Severity severity, Instant now) {
    return new Incident(
        id,
        title,
        affectedService,
        severity,
        IncidentStatus.RECEIVED,
        0L,
        now,
        now,
        Optional.empty(),
        Optional.empty());
  }

  /**
   * Moves to {@code target}, or throws if the state machine forbids it.
   *
   * @throws IllegalTransitionException if the transition is not permitted
   */
  public Incident transitionTo(IncidentStatus target, Instant now) {
    Objects.requireNonNull(target, "target status must not be null");
    Objects.requireNonNull(now, "now must not be null");
    if (!status.canTransitionTo(target)) {
      throw new IllegalTransitionException(id, status, target);
    }
    return new Incident(
        id,
        title,
        affectedService,
        severity,
        target,
        version + 1,
        receivedAt,
        now,
        summary,
        closingNote);
  }

  /**
   * Moves to a terminal status while recording why.
   *
   * @throws IllegalArgumentException if {@code target} is not terminal
   */
  public Incident close(IncidentStatus target, String note, Instant now) {
    Objects.requireNonNull(note, "closing note must not be null");
    if (!target.isTerminal()) {
      throw new IllegalArgumentException(target + " is not a terminal status");
    }
    Incident moved = transitionTo(target, now);
    return new Incident(
        moved.id,
        moved.title,
        moved.affectedService,
        moved.severity,
        moved.status,
        moved.version,
        moved.receivedAt,
        moved.updatedAt,
        moved.summary,
        Optional.of(note));
  }

  /**
   * Attaches or replaces the investigation summary.
   *
   * <p>This bumps the version like any other change, which deliberately invalidates outstanding
   * approvals: if the narrative of the incident changed, a human who approved against the old one
   * did not approve this.
   */
  public Incident withSummary(String newSummary, Instant now) {
    Objects.requireNonNull(newSummary, "summary must not be null");
    return new Incident(
        id,
        title,
        affectedService,
        severity,
        status,
        version + 1,
        receivedAt,
        now,
        Optional.of(newSummary),
        closingNote);
  }

  public boolean isTerminal() {
    return status.isTerminal();
  }
}
