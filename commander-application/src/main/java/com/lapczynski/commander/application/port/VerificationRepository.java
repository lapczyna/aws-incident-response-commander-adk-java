package com.lapczynski.commander.application.port;

import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.verification.RecoveryVerification;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable storage for whether a remediation actually worked.
 *
 * <p>Stored with the measurements it was judged from, not just the verdict, so a human reading the
 * postmortem can disagree with the conclusion without re-running anything.
 */
public interface VerificationRepository {

  /**
   * @param executedActionId the attempt this verification judges, absent when nothing was executed
   */
  void save(RecoveryVerification verification, Optional<UUID> executedActionId);

  Optional<RecoveryVerification> findByIncident(IncidentId incidentId);
}
