package com.lapczynski.commander.application.port;

import com.lapczynski.commander.domain.audit.AuditEvent;
import com.lapczynski.commander.domain.incident.IncidentId;
import java.util.List;

/**
 * The append-only audit trail.
 *
 * <p>Deliberately offers no update or delete operation. The absence is the point: there is no API
 * through which the application could rewrite history, and the database enforces the same rule
 * independently.
 */
public interface AuditLog {

  /** Appends an entry. Never fails silently; an audit write that cannot happen must surface. */
  void append(AuditEvent event);

  /** The full trail for one incident, in insertion order. */
  List<AuditEvent> findByIncident(IncidentId incidentId);

  /** Security-relevant entries across all incidents, newest first. */
  List<AuditEvent> findSecurityRelevant(int limit);
}
