package com.lapczynski.commander.persistence;

import com.lapczynski.commander.application.port.IdempotencyStore;
import com.lapczynski.commander.domain.approval.ActionFingerprint;
import com.lapczynski.commander.domain.incident.IncidentId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exactly-once execution enforced by the database.
 *
 * <p>{@link #claim} is an {@code INSERT ... ON CONFLICT DO NOTHING} against a primary key. Whether
 * a caller may act is therefore decided by PostgreSQL, not by application logic: two concurrent
 * requests on two instances both attempt the insert, exactly one affects a row, and the loser is
 * told to stand down.
 *
 * <p>A read-then-write approach — "is this fingerprint present? no? then insert and act" — would
 * leave a window between the check and the insert in which both callers see nothing and both
 * proceed. That window is precisely a duplicate rollback.
 */
@Repository
public class JdbcIdempotencyStore implements IdempotencyStore {

  private final JdbcClient jdbc;

  public JdbcIdempotencyStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Runs in its own transaction. The claim must be durable the instant it is made, even if the
   * surrounding transaction later rolls back: a caller that crashed mid-action must not silently
   * release its claim and allow a retry to execute the same change twice. Recovery from a stranded
   * {@code IN_PROGRESS} claim is a deliberate operator decision, not an automatic one.
   */
  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claim(ActionFingerprint fingerprint, IncidentId incidentId) {
    int inserted =
        jdbc.sql(
                """
                INSERT INTO idempotency_keys (fingerprint, incident_id, first_seen_at, outcome)
                VALUES (:fingerprint, :incidentId, :now, 'IN_PROGRESS')
                ON CONFLICT (fingerprint) DO NOTHING
                """)
            .param("fingerprint", fingerprint.hex())
            .param("incidentId", incidentId.value())
            .param("now", OffsetDateTime.now(ZoneOffset.UTC))
            .update();

    return inserted == 1;
  }

  @Override
  @Transactional
  public void complete(ActionFingerprint fingerprint, Outcome outcome, String result) {
    int updated =
        jdbc.sql(
                """
                UPDATE idempotency_keys
                   SET outcome = :outcome,
                       result = CAST(:result AS jsonb)
                 WHERE fingerprint = :fingerprint
                   AND outcome = 'IN_PROGRESS'
                """)
            .param("outcome", outcome.name())
            .param("result", result)
            .param("fingerprint", fingerprint.hex())
            .update();

    if (updated == 0) {
      throw new IllegalStateException(
          "no in-progress claim for fingerprint %s; completing an execution that was never "
                  .formatted(fingerprint.abbreviated())
              + "claimed would corrupt the exactly-once guarantee");
    }
  }

  @Override
  public Optional<Record> find(ActionFingerprint fingerprint) {
    return jdbc.sql(
            "SELECT fingerprint, outcome, result::text AS result FROM idempotency_keys "
                + "WHERE fingerprint = :fingerprint")
        .param("fingerprint", fingerprint.hex())
        .query(
            (rs, rowNum) ->
                new Record(
                    ActionFingerprint.fromHex(rs.getString("fingerprint").trim()),
                    Outcome.valueOf(rs.getString("outcome")),
                    rs.getString("result")))
        .optional();
  }
}
