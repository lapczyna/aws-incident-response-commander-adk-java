-- =====================================================================================
-- V2: durable storage for ADK sessions and events.
--
-- Java ADK ships InMemorySessionService, VertexAiSessionService and an out-of-tree
-- Firestore implementation. None of them fits a project whose store is PostgreSQL and
-- whose cloud is AWS, so PostgresSessionService implements BaseSessionService against
-- these tables. See ADR-0004.
--
-- The table shapes mirror ADK's own state model rather than inventing one: session
-- state, app-scoped state and user-scoped state are separate, because ADK's
-- EventActions.stateDelta routes keys by an `app:` / `user:` / `temp:` prefix and the
-- three have different lifetimes. Flattening them into one table would mean app state
-- vanishing when a session is deleted.
-- =====================================================================================

CREATE TABLE adk_sessions (
    app_name         TEXT        NOT NULL,
    user_id          TEXT        NOT NULL,
    session_id       TEXT        NOT NULL,
    -- Session-scoped state only. Keys carrying `app:` or `user:` prefixes are routed to
    -- the tables below; `temp:` keys are never persisted at all, by ADK's contract.
    state            JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_update_time TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (app_name, user_id, session_id)
);

COMMENT ON TABLE adk_sessions IS
    'ADK conversation sessions. An incident awaiting approval survives a restart because this '
    'row plus its events are enough to reconstruct the exact invocation that was waiting.';

-- Query: list a user's sessions for an app, most recently active first.
CREATE INDEX adk_sessions_by_user ON adk_sessions (app_name, user_id, last_update_time DESC);

-- -------------------------------------------------------------------------------------
-- Events: append-only, strictly ordered
-- -------------------------------------------------------------------------------------
CREATE TABLE adk_events (
    app_name    TEXT        NOT NULL,
    user_id     TEXT        NOT NULL,
    session_id  TEXT        NOT NULL,
    -- Monotonic per session. ADK events can share a millisecond, and replaying them in the
    -- wrong order would reconstruct a different conversation, so ordering never relies on
    -- the timestamp alone.
    sequence    BIGINT      NOT NULL,
    event_id    TEXT        NOT NULL,
    author      TEXT        NOT NULL,
    invocation_id TEXT,
    -- The whole ADK Event, serialised via its own Jackson mapping (Event extends
    -- JsonBaseModel). Stored verbatim rather than decomposed into columns: the Event schema
    -- belongs to ADK and will change across versions, and a partial projection would lose
    -- exactly the fields resumption depends on.
    payload     JSONB       NOT NULL,
    timestamp   TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (app_name, user_id, session_id, sequence),
    FOREIGN KEY (app_name, user_id, session_id)
        REFERENCES adk_sessions (app_name, user_id, session_id) ON DELETE CASCADE
);

COMMENT ON COLUMN adk_events.payload IS
    'The serialised ADK Event. Queryable fields are duplicated into columns above for indexing; '
    'the payload remains the source of truth for reconstruction.';

-- Query: replay a session's events in order (the hot path for resumption).
CREATE INDEX adk_events_by_session ON adk_events (app_name, user_id, session_id, sequence);

-- Query: find the events of one invocation, for tracing a single agent run.
CREATE INDEX adk_events_by_invocation ON adk_events (invocation_id)
    WHERE invocation_id IS NOT NULL;

-- -------------------------------------------------------------------------------------
-- Scoped state, outliving any single session
-- -------------------------------------------------------------------------------------
CREATE TABLE adk_app_state (
    app_name   TEXT        NOT NULL,
    state_key  TEXT        NOT NULL,
    state_value JSONB      NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (app_name, state_key)
);

COMMENT ON TABLE adk_app_state IS
    'State written with an `app:` prefix. Deliberately not cascaded from adk_sessions: it is '
    'shared across every session of an app and must survive any one of them being deleted.';

CREATE TABLE adk_user_state (
    app_name   TEXT        NOT NULL,
    user_id    TEXT        NOT NULL,
    state_key  TEXT        NOT NULL,
    state_value JSONB      NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (app_name, user_id, state_key)
);

COMMENT ON TABLE adk_user_state IS
    'State written with a `user:` prefix. Survives session deletion for the same reason as '
    'app state.';

-- -------------------------------------------------------------------------------------
-- Linking ADK sessions to incidents
-- -------------------------------------------------------------------------------------
ALTER TABLE incidents
    ADD COLUMN adk_session_id TEXT,
    ADD COLUMN adk_app_name   TEXT,
    ADD COLUMN adk_user_id    TEXT;

COMMENT ON COLUMN incidents.adk_session_id IS
    'The ADK session driving this incident. Populated when the investigation starts, and what '
    'a restarted process uses to reattach to an invocation that was awaiting approval.';

-- Query: given a session, find its incident (used when resuming from an approval).
CREATE INDEX incidents_by_adk_session ON incidents (adk_session_id)
    WHERE adk_session_id IS NOT NULL;
