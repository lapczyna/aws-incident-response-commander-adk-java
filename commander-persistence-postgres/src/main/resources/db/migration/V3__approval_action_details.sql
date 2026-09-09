-- =====================================================================================
-- V3: carry the proposed action inline on the approval request.
--
-- V1 modelled approval_requests as pointing at a remediation_proposals row. That is the
-- right shape once the planner persists proposals (Phase 8), but it is not the shape the
-- approval workflow needs today, and it is not the shape the safety argument needs at all.
--
-- The approval must record exactly what a human authorised, independently of any row that
-- might be updated later. The fingerprint is computed over these fields, so if they lived
-- only in another table, a change there would silently alter the meaning of an approval
-- already granted. Storing them here makes the approval self-contained: what the approver
-- saw is what is stored, and nothing else can move it.
-- =====================================================================================

ALTER TABLE approval_requests
    ALTER COLUMN proposal_id DROP NOT NULL,
    ADD COLUMN action_type          TEXT,
    ADD COLUMN target_arn           TEXT,
    ADD COLUMN target_account_id    TEXT,
    ADD COLUMN target_region        TEXT,
    ADD COLUMN target_environment   TEXT,
    ADD COLUMN target_resource_type TEXT,
    ADD COLUMN arguments            JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN human_description    TEXT;

COMMENT ON COLUMN approval_requests.action_type IS
    'The action as authorised. Held here rather than only on the proposal row so that an '
    'approval is self-contained: the fingerprint is computed over these fields, and a later '
    'edit elsewhere must not be able to change what a granted approval means.';

-- Every approval must describe a real action. Enforced as a table constraint rather than
-- per-column NOT NULL so the existing rows-free table can adopt it in one step.
ALTER TABLE approval_requests
    ADD CONSTRAINT approval_action_is_complete CHECK (
        action_type IS NOT NULL
        AND target_arn IS NOT NULL
        AND target_account_id IS NOT NULL
        AND target_region IS NOT NULL
        AND target_environment IS NOT NULL
        AND human_description IS NOT NULL),
    ADD CONSTRAINT approval_action_type_valid CHECK (action_type IN (
        'DEACTIVATE_DEMO_FAULT', 'RESTORE_SAFE_CONFIGURATION', 'RUN_HEALTH_CHECK',
        'RESTART_ECS_TASK', 'SCALE_ECS_SERVICE', 'ROLLBACK_DEPLOYMENT'));

-- Query: resuming after a restart looks an approval up by the fingerprint the executor holds.
CREATE INDEX approval_requests_by_fingerprint ON approval_requests (fingerprint);

-- Query: the ADK function call this approval will resume.
CREATE INDEX approval_requests_by_function_call
    ON approval_requests (adk_function_call_id)
    WHERE adk_function_call_id IS NOT NULL;
