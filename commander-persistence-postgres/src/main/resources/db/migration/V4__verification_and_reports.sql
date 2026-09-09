-- ---------------------------------------------------------------------------------------
-- V4: make verification say what it measured, not just whether it liked the answer.
--
-- V1 modelled a verification as `recovered BOOLEAN`. That was wrong in a specific and
-- dangerous way: it has no representation for "the metric could not be read". A boolean
-- forces that case into either true or false, and whichever is chosen, the report lies -
-- either by claiming recovery that was never observed, or by blaming a fix that may well
-- have worked. The domain answers with four values for that reason, and the table now
-- matches.
--
-- The measurements are stored alongside the verdict so a human reading the postmortem can
-- disagree with the conclusion without re-running anything.
-- ---------------------------------------------------------------------------------------

ALTER TABLE verification_results
    ADD COLUMN outcome            TEXT,
    ADD COLUMN metric_name        TEXT,
    ADD COLUMN before_value       DOUBLE PRECISION,
    ADD COLUMN after_value        DOUBLE PRECISION,
    ADD COLUMN recovery_threshold DOUBLE PRECISION;

-- No rows exist yet in any environment (nothing wrote this table before this migration),
-- but backfilling from the boolean keeps the migration correct if one ever did.
UPDATE verification_results
   SET outcome = CASE WHEN recovered THEN 'RECOVERED' ELSE 'NOT_RECOVERED' END,
       metric_name = COALESCE(metric_name, 'unknown'),
       before_value = COALESCE(before_value, 'NaN'::double precision),
       after_value = COALESCE(after_value, 'NaN'::double precision),
       recovery_threshold = COALESCE(recovery_threshold, 'NaN'::double precision)
 WHERE outcome IS NULL;

ALTER TABLE verification_results
    ALTER COLUMN outcome            SET NOT NULL,
    ALTER COLUMN metric_name        SET NOT NULL,
    ALTER COLUMN before_value       SET NOT NULL,
    ALTER COLUMN after_value        SET NOT NULL,
    ALTER COLUMN recovery_threshold SET NOT NULL;

ALTER TABLE verification_results
    DROP COLUMN recovered;

ALTER TABLE verification_results
    ADD CONSTRAINT verification_outcome_valid CHECK (outcome IN (
        'RECOVERED', 'NOT_RECOVERED', 'PARTIALLY_RECOVERED', 'INDETERMINATE'));

COMMENT ON COLUMN verification_results.outcome IS
    'Four-valued on purpose. INDETERMINATE means the metric could not be read, which is '
    'neither success nor failure, and collapsing it into either would make the incident '
    'report state something that was never observed.';

COMMENT ON COLUMN verification_results.after_value IS
    'NaN when the metric could not be measured. Stored rather than left null so the '
    'distinction between "measured as zero" and "not measured" survives into the report.';

-- The executed action becomes optional. An incident can reach verification without an
-- execution row - a dry run that was never confirmed, or an investigation that resolved
-- without acting - and a NOT NULL reference would force a fake execution to be invented.
ALTER TABLE verification_results
    ALTER COLUMN executed_action_id DROP NOT NULL;

-- One verification per incident. A second would raise the question of which one the
-- incident status reflects, and there is no good answer to that.
CREATE UNIQUE INDEX verification_one_per_incident ON verification_results (incident_id);

-- ---------------------------------------------------------------------------------------
-- Evidence ordering
--
-- The report numbers citations by collection order, so that order has to be stable across
-- reads. `collected_at` alone is not: two observations recorded in the same millisecond
-- would sort arbitrarily, and the numbering in a regenerated report would differ from the
-- one a human already read.
-- ---------------------------------------------------------------------------------------
ALTER TABLE evidence
    ADD COLUMN sequence BIGINT GENERATED ALWAYS AS IDENTITY;

CREATE INDEX evidence_by_incident_sequence ON evidence (incident_id, sequence);

COMMENT ON COLUMN evidence.sequence IS
    'Insertion order, used to number report citations stably. Timestamps are not sufficient: '
    'ties would renumber a regenerated report against one a human has already read.';

-- ---------------------------------------------------------------------------------------
-- The approval reference becomes optional.
--
-- Not a relaxation of the rule that actions require approval - that is enforced by the
-- policy gate, by ADK confirmation, and by the tool re-checking policy before it acts.
-- This is about what happens when something has ALREADY changed infrastructure and the
-- approval row cannot be resolved afterwards. A NOT NULL constraint there means the insert
-- fails and the record of a real change is lost, which is the worst available outcome: the
-- change happened either way, and now nothing knows about it.
--
-- An execution with no approval id is a loud anomaly that a query can find. A missing row
-- is silence.
-- ---------------------------------------------------------------------------------------
ALTER TABLE executed_actions
    ALTER COLUMN approval_id DROP NOT NULL;

COMMENT ON COLUMN executed_actions.approval_id IS
    'Nullable so that a real change is never unrecorded because its approval could not be '
    'resolved. Null here is an anomaly to investigate, not a permitted workflow: nothing '
    'reaches execution without passing the policy gate and an explicit human confirmation.';

CREATE INDEX executed_actions_without_approval
    ON executed_actions (incident_id) WHERE approval_id IS NULL;
