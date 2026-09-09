package com.lapczynski.commander.domain.audit;

import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.incident.IncidentId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One entry in the append-only audit log.
 *
 * <p>Append-only is enforced in the database, not merely by convention: the migration revokes
 * UPDATE and DELETE on the audit table from the application role. An audit trail the application
 * can rewrite is not an audit trail, and the one moment it would matter is exactly the moment
 * something has gone wrong.
 *
 * <p>Entries carry no primary-key-ordered assumptions. They are ordered by {@code occurredAt} plus
 * a monotonic sequence assigned on insert, because two events in the same millisecond still have an
 * order and a reader needs to know it.
 *
 * @param details structured context. Kept small and scalar, and <strong>never</strong> raw tool
 *     output or model text: this table is read by humans investigating what happened, and it is
 *     redaction-checked before write.
 */
public record AuditEvent(
    IncidentId incidentId,
    AuditEventType type,
    Actor actor,
    String summary,
    Map<String, String> details,
    Instant occurredAt,
    Optional<String> correlationId) {

  /** Upper bound on a single detail value, to keep the log readable and bounded. */
  public static final int MAX_DETAIL_VALUE_LENGTH = 512;

  public AuditEvent {
    Objects.requireNonNull(incidentId, "incidentId must not be null");
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(actor, "actor must not be null");
    Objects.requireNonNull(summary, "summary must not be null");
    Objects.requireNonNull(details, "details must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    Objects.requireNonNull(correlationId, "correlationId must not be null");
    if (summary.isBlank()) {
      throw new IllegalArgumentException("summary must not be blank");
    }
    details.forEach(
        (key, value) -> {
          Objects.requireNonNull(key, "detail key must not be null");
          Objects.requireNonNull(value, "detail value must not be null for key " + key);
          if (value.length() > MAX_DETAIL_VALUE_LENGTH) {
            throw new IllegalArgumentException(
                "audit detail '%s' is %d characters, exceeding the %d limit; summarise it rather "
                        .formatted(key, value.length(), MAX_DETAIL_VALUE_LENGTH)
                    + "than storing raw output in the audit log");
          }
        });
    details = Map.copyOf(new TreeMap<>(details));
  }

  public static AuditEvent of(
      IncidentId incidentId,
      AuditEventType type,
      Actor actor,
      String summary,
      Map<String, String> details,
      Instant now) {
    return new AuditEvent(incidentId, type, actor, summary, details, now, Optional.empty());
  }

  public AuditEvent withCorrelationId(String id) {
    return new AuditEvent(
        incidentId, type, actor, summary, details, occurredAt, Optional.ofNullable(id));
  }

  public boolean isSecurityRelevant() {
    return type.isSecurityRelevant();
  }
}
