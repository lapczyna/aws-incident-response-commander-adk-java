package com.lapczynski.commander.application.port;

import com.lapczynski.commander.domain.evidence.Evidence;
import com.lapczynski.commander.domain.evidence.EvidenceGap;
import com.lapczynski.commander.domain.incident.IncidentId;
import java.util.List;

/**
 * Durable storage for what an investigation observed, and for what it could not observe.
 *
 * <p>Both, and stored separately. "The logs showed nothing" and "the logs could not be read"
 * support completely different conclusions, and a single table with a nullable content column would
 * lose the distinction the moment someone wrote a convenient query.
 *
 * <p>This exists because the incident report assembles its citations from stored rows. Evidence
 * that lived only in agent session state could be summarised in a report but never checked against
 * it, so "every conclusion references stored evidence" would be a claim rather than a property.
 */
public interface EvidenceRepository {

  /** Persists one observation. Returns nothing: the id is assigned by the caller. */
  void save(Evidence evidence);

  /** Persists a failure to observe. */
  void saveGap(EvidenceGap gap);

  /** Everything observed for one incident, in collection order. */
  List<Evidence> findByIncident(IncidentId incidentId);

  /** Everything that could not be observed for one incident, in the order it was attempted. */
  List<EvidenceGap> findGapsByIncident(IncidentId incidentId);
}
