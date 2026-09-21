package com.lapczynski.commander.persistence.adk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.JsonBaseModel;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.State;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.core.Single;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL implementation of ADK's {@link BaseSessionService}.
 *
 * <p>Java ADK ships no JDBC session service (ADR-0004), and durable sessions are not optional here:
 * an incident that pauses for human approval must survive a restart, and the persisted session plus
 * its events are what a restarted process reattaches to.
 *
 * <p><strong>Mirroring ADK's state contract exactly.</strong> {@code EventActions.stateDelta}
 * routes keys by prefix, and the three scopes have genuinely different lifetimes:
 *
 * <ul>
 *   <li>{@code app:} — shared by every session of an app; must outlive any one session
 *   <li>{@code user:} — shared by a user's sessions; must outlive any one session
 *   <li>{@code temp:} — never persisted at all
 *   <li>everything else — session-scoped
 * </ul>
 *
 * Getting this wrong would not fail loudly. It would produce state that quietly differs between the
 * in-memory and durable profiles, so the demo would work and production would not. {@code
 * SessionServiceContractTest} runs the same suite against both implementations for that reason.
 *
 * <p><strong>Blocking JDBC behind an Rx interface.</strong> Every method offloads to a bounded IO
 * scheduler rather than running on the caller's thread. ADK subscribes to these from inside its
 * event stream; doing JDBC work there would block the stream that is meant to be delivering
 * progress.
 */
public class PostgresSessionService implements BaseSessionService {

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final Scheduler ioScheduler;
  private final ObjectMapper objectMapper = JsonBaseModel.getMapper();

  public PostgresSessionService(
      JdbcClient jdbc, TransactionTemplate transactions, Scheduler ioScheduler) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.ioScheduler = ioScheduler;
  }

  // ------------------------------------------------------------------ create

  @Override
  @Deprecated
  public Single<Session> createSession(
      String appName,
      String userId,
      @Nullable ConcurrentMap<String, Object> state,
      @Nullable String sessionId) {
    return createSession(appName, userId, (Map<String, Object>) state, sessionId);
  }

  @Override
  public Single<Session> createSession(
      String appName,
      String userId,
      @Nullable Map<String, Object> state,
      @Nullable String sessionId) {

    String resolvedId =
        Optional.ofNullable(sessionId)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .orElseGet(() -> UUID.randomUUID().toString());

    return Single.fromCallable(
            () ->
                transactions.execute(
                    status -> {
                      Instant now = Instant.now();
                      Map<String, Object> sessionState =
                          state == null ? Map.of() : new LinkedHashMap<>(state);

                      jdbc.sql(
                              """
                              INSERT INTO adk_sessions
                                     (app_name, user_id, session_id, state, last_update_time)
                              VALUES (:appName, :userId, :sessionId, CAST(:state AS jsonb), :now)
                              """)
                          .param("appName", appName)
                          .param("userId", userId)
                          .param("sessionId", resolvedId)
                          .param("state", writeJson(sessionState))
                          .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                          .update();

                      return buildSession(
                          appName, userId, resolvedId, sessionState, List.of(), now);
                    }))
        .subscribeOn(ioScheduler);
  }

  // --------------------------------------------------------------------- get

  @Override
  public Maybe<Session> getSession(
      String appName, String userId, String sessionId, Optional<GetSessionConfig> config) {

    return Maybe.fromCallable(
            () -> {
              Optional<StoredSession> stored = loadSession(appName, userId, sessionId);
              if (stored.isEmpty()) {
                return null;
              }

              List<Event> events = loadEvents(appName, userId, sessionId);
              events = applyConfig(events, config.orElse(null));

              return buildSession(
                  appName,
                  userId,
                  sessionId,
                  stored.get().state(),
                  events,
                  stored.get().lastUpdateTime());
            })
        .subscribeOn(ioScheduler);
  }

  // -------------------------------------------------------------------- list

  @Override
  public Single<ListSessionsResponse> listSessions(String appName, String userId) {
    return Single.fromCallable(
            () -> {
              // Sessions are returned without events, matching ADK's own implementations: a
              // listing that eagerly loaded every event would be unusable once an incident has
              // run for a while.
              List<Session> sessions =
                  jdbc.sql(
                          """
                          SELECT session_id, state, last_update_time
                            FROM adk_sessions
                           WHERE app_name = :appName AND user_id = :userId
                           ORDER BY last_update_time DESC
                          """)
                      .param("appName", appName)
                      .param("userId", userId)
                      .query(
                          (rs, rowNum) ->
                              buildSession(
                                  appName,
                                  userId,
                                  rs.getString("session_id"),
                                  readState(rs.getString("state")),
                                  List.of(),
                                  rs.getObject("last_update_time", OffsetDateTime.class)
                                      .toInstant()))
                      .list();

              return ListSessionsResponse.builder().sessions(sessions).build();
            })
        .subscribeOn(ioScheduler);
  }

  @Override
  public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
    return Single.fromCallable(
            () ->
                ListEventsResponse.builder().events(loadEvents(appName, userId, sessionId)).build())
        .subscribeOn(ioScheduler);
  }

  // ------------------------------------------------------------------ delete

  @Override
  public Completable deleteSession(String appName, String userId, String sessionId) {
    return Completable.fromRunnable(
            () ->
                transactions.executeWithoutResult(
                    status ->
                        // Events cascade. App and user state deliberately do not: they are shared
                        // across sessions and deleting one session must not discard them.
                        jdbc.sql(
                                """
                                DELETE FROM adk_sessions
                                 WHERE app_name = :appName
                                   AND user_id = :userId
                                   AND session_id = :sessionId
                                """)
                            .param("appName", appName)
                            .param("userId", userId)
                            .param("sessionId", sessionId)
                            .update()))
        .subscribeOn(ioScheduler);
  }

  // ------------------------------------------------------------------ append

  /**
   * Persists an event and applies its state delta.
   *
   * <p>Follows {@link BaseSessionService}'s contract precisely: partial events are not persisted,
   * {@code app:} and {@code user:} keys are routed to their own tables with the prefix stripped,
   * {@code temp:} keys are dropped, and {@link State#REMOVED} deletes rather than storing a
   * sentinel.
   *
   * <p><strong>The live session is updated on the calling thread, before anything is
   * scheduled.</strong> ADK reads {@code session.state()} to render the next stage's instruction,
   * and it does so as soon as this method returns — not when the returned {@code Single} completes.
   * Deferring the in-memory mutation onto {@code ioScheduler} along with the database write
   * therefore loses a race the in-memory session service cannot lose, because that one mutates
   * synchronously. The symptom is an evidence key missing from an instruction, which ADK turns into
   * {@code IllegalArgumentException: Context variable not found}, and which appears only when the
   * database is slow enough to lose — so it passes locally and fails under load.
   *
   * <p><strong>The database write is eager too, and the returned {@code Single} is already
   * complete.</strong> ADK treats calling this method as the write having happened: it emits the
   * event, calls here, and lets the agent tree carry on. A {@code Single} that only did its work
   * when subscribed leaves the window that {@code DurablePipelineIntegrationTest} exercises — four
   * specialists running concurrently, the slowest of them still appending its result while the
   * stage that interpolates that result has already begun rendering its instruction. ADK's
   * in-memory service does its work on the calling thread and never loses that race, which is why
   * the same pipeline passes there and fails here.
   *
   * <p>The cost is that this blocks the caller on JDBC. That is the right thread to block: the
   * agents are given an IO scheduler precisely because they do blocking work, and the alternative —
   * a faster method that returns before the session is what it claims to be — is not a trade worth
   * making in the component the approval flow resumes from.
   */
  @Override
  @Transactional
  public Single<Event> appendEvent(Session session, Event event) {
    if (event.partial().orElse(false)) {
      // Streaming fragments. Persisting them would replay a conversation as a stutter of
      // half-formed messages.
      return Single.just(event);
    }

    // Synchronous, on the caller's thread. See the note above: everything downstream reads this
    // object, and it has to be correct before this method returns.
    applyToLiveSession(session, event);

    Instant appendedAt = Instant.ofEpochMilli(event.timestamp());
    session.lastUpdateTime(appendedAt);

    // Eager, not deferred. See the note above.
    transactions.execute(
        status -> {
          String appName = session.appName();
          String userId = session.userId();
          String sessionId = session.id();

          persistScopedState(appName, userId, event);
          persistEvent(appName, userId, sessionId, event);

          jdbc.sql(
                  """
                  UPDATE adk_sessions
                     SET state = CAST(:state AS jsonb), last_update_time = :now
                   WHERE app_name = :appName
                     AND user_id = :userId
                     AND session_id = :sessionId
                  """)
              .param("state", writeJson(sessionScopedState(session.state())))
              .param("now", OffsetDateTime.ofInstant(appendedAt, ZoneOffset.UTC))
              .param("appName", appName)
              .param("userId", userId)
              .param("sessionId", sessionId)
              .update();

          return event;
        });

    return Single.just(event);
  }

  // ----------------------------------------------------------------- helpers

  /**
   * Applies an event's state delta to the live session object.
   *
   * <p>In memory only, and on the caller's thread. {@code temp:} keys are dropped per ADK's
   * contract; {@link State#REMOVED} deletes rather than storing a sentinel; everything else,
   * prefixed or not, is what the next stage will read.
   */
  private void applyToLiveSession(Session session, Event event) {
    EventActions actions = event.actions();
    if (actions == null || actions.stateDelta() == null || actions.stateDelta().isEmpty()) {
      session.events().add(event);
      return;
    }

    actions
        .stateDelta()
        .forEach(
            (key, value) -> {
              if (key.startsWith(State.TEMP_PREFIX)) {
                return;
              }
              if (value == State.REMOVED) {
                session.state().remove(key);
              } else {
                session.state().put(key, value);
              }
            });

    session.events().add(event);
  }

  /**
   * Routes {@code app:} and {@code user:} keys to the tables that outlive this session.
   *
   * <p>Separate from {@link #applyToLiveSession} because these are the only parts of a delta that
   * touch the database directly; the session's own keys go into the row in one write at the end.
   */
  private void persistScopedState(String appName, String userId, Event event) {
    EventActions actions = event.actions();
    if (actions == null || actions.stateDelta() == null || actions.stateDelta().isEmpty()) {
      return;
    }

    actions
        .stateDelta()
        .forEach(
            (key, value) -> {
              if (key.startsWith(State.APP_PREFIX)) {
                writeScopedState(
                    "adk_app_state",
                    appName,
                    null,
                    key.substring(State.APP_PREFIX.length()),
                    value);
              } else if (key.startsWith(State.USER_PREFIX)) {
                writeScopedState(
                    "adk_user_state",
                    appName,
                    userId,
                    key.substring(State.USER_PREFIX.length()),
                    value);
              }
            });
  }

  private void writeScopedState(
      String table, String appName, @Nullable String userId, String key, Object value) {
    boolean userScoped = userId != null;

    if (value == State.REMOVED) {
      jdbc.sql(
              "DELETE FROM %s WHERE app_name = :appName AND state_key = :key%s"
                  .formatted(table, userScoped ? " AND user_id = :userId" : ""))
          .param("appName", appName)
          .param("key", key)
          .params(userScoped ? Map.of("userId", userId) : Map.of())
          .update();
      return;
    }

    String sql =
        userScoped
            ? """
              INSERT INTO adk_user_state (app_name, user_id, state_key, state_value, updated_at)
              VALUES (:appName, :userId, :key, CAST(:value AS jsonb), now())
              ON CONFLICT (app_name, user_id, state_key)
              DO UPDATE SET state_value = EXCLUDED.state_value, updated_at = now()
              """
            : """
              INSERT INTO adk_app_state (app_name, state_key, state_value, updated_at)
              VALUES (:appName, :key, CAST(:value AS jsonb), now())
              ON CONFLICT (app_name, state_key)
              DO UPDATE SET state_value = EXCLUDED.state_value, updated_at = now()
              """;

    jdbc.sql(sql)
        .param("appName", appName)
        .param("key", key)
        .param("value", writeJson(value))
        .params(userScoped ? Map.of("userId", userId) : Map.of())
        .update();
  }

  private void persistEvent(String appName, String userId, String sessionId, Event event) {
    jdbc.sql(
            """
            INSERT INTO adk_events (app_name, user_id, session_id, sequence, event_id, author,
                                    invocation_id, payload, timestamp)
            VALUES (:appName, :userId, :sessionId,
                    COALESCE((SELECT MAX(sequence) FROM adk_events
                               WHERE app_name = :appName AND user_id = :userId
                                 AND session_id = :sessionId), 0) + 1,
                    :eventId, :author, :invocationId, CAST(:payload AS jsonb), :timestamp)
            """)
        .param("appName", appName)
        .param("userId", userId)
        .param("sessionId", sessionId)
        .param("eventId", event.id())
        .param("author", event.author())
        .param("invocationId", event.invocationId())
        .param("payload", event.toJson())
        .param(
            "timestamp",
            OffsetDateTime.ofInstant(Instant.ofEpochMilli(event.timestamp()), ZoneOffset.UTC))
        .update();
  }

  private Optional<StoredSession> loadSession(String appName, String userId, String sessionId) {
    return jdbc.sql(
            """
            SELECT state, last_update_time FROM adk_sessions
             WHERE app_name = :appName AND user_id = :userId AND session_id = :sessionId
            """)
        .param("appName", appName)
        .param("userId", userId)
        .param("sessionId", sessionId)
        .query(
            (rs, rowNum) ->
                new StoredSession(
                    readState(rs.getString("state")),
                    rs.getObject("last_update_time", OffsetDateTime.class).toInstant()))
        .optional();
  }

  private List<Event> loadEvents(String appName, String userId, String sessionId) {
    return jdbc.sql(
            """
            SELECT payload FROM adk_events
             WHERE app_name = :appName AND user_id = :userId AND session_id = :sessionId
             ORDER BY sequence
            """)
        .param("appName", appName)
        .param("userId", userId)
        .param("sessionId", sessionId)
        .query((rs, rowNum) -> JsonBaseModel.fromJsonString(rs.getString("payload"), Event.class))
        .list();
  }

  /**
   * Applies {@link GetSessionConfig} filtering.
   *
   * <p>Both filters are applied together when both are present. ADK 1.7.1 fixed exactly this bug in
   * its own implementations — applying only one silently returns more history than the caller asked
   * for, which for a resumption path means replaying events that were meant to be excluded.
   */
  private List<Event> applyConfig(List<Event> events, @Nullable GetSessionConfig config) {
    if (config == null) {
      return events;
    }

    List<Event> filtered = events;

    if (config.afterTimestamp().isPresent()) {
      long cutoff = config.afterTimestamp().get().toEpochMilli();
      filtered = filtered.stream().filter(e -> e.timestamp() >= cutoff).toList();
    }

    if (config.numRecentEvents().isPresent()) {
      int keep = config.numRecentEvents().get();
      int from = Math.max(0, filtered.size() - keep);
      filtered = new ArrayList<>(filtered.subList(from, filtered.size()));
    }

    return filtered;
  }

  /** Builds a session, merging app- and user-scoped state back in under their prefixes. */
  private Session buildSession(
      String appName,
      String userId,
      String sessionId,
      Map<String, Object> sessionState,
      List<Event> events,
      Instant lastUpdateTime) {

    ConcurrentMap<String, Object> merged = new ConcurrentHashMap<>(sessionState);

    jdbc.sql("SELECT state_key, state_value FROM adk_app_state WHERE app_name = :appName")
        .param("appName", appName)
        .query(
            (rs, rowNum) ->
                merged.put(
                    State.APP_PREFIX + rs.getString("state_key"),
                    readValue(rs.getString("state_value"))))
        .list();

    jdbc.sql(
            "SELECT state_key, state_value FROM adk_user_state "
                + "WHERE app_name = :appName AND user_id = :userId")
        .param("appName", appName)
        .param("userId", userId)
        .query(
            (rs, rowNum) ->
                merged.put(
                    State.USER_PREFIX + rs.getString("state_key"),
                    readValue(rs.getString("state_value"))))
        .list();

    return Session.builder(sessionId)
        .appName(appName)
        .userId(userId)
        .state(merged)
        // Synchronised, because the four specialists inside the ParallelAgent append to this list
        // from four threads at once. An ArrayList here loses events, and it loses them silently.
        .events(java.util.Collections.synchronizedList(new ArrayList<>(events)))
        .lastUpdateTime(lastUpdateTime)
        .build();
  }

  /** Strips prefixed keys, leaving only what belongs in the session's own row. */
  private Map<String, Object> sessionScopedState(Map<String, Object> state) {
    Map<String, Object> scoped = new LinkedHashMap<>();
    state.forEach(
        (key, value) -> {
          if (!key.startsWith(State.APP_PREFIX)
              && !key.startsWith(State.USER_PREFIX)
              && !key.startsWith(State.TEMP_PREFIX)) {
            scoped.put(key, value);
          }
        });
    return scoped;
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("could not serialise ADK state", e);
    }
  }

  private Map<String, Object> readState(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("could not read ADK session state", e);
    }
  }

  private Object readValue(String json) {
    try {
      return objectMapper.readValue(json, Object.class);
    } catch (Exception e) {
      throw new IllegalStateException("could not read ADK state value", e);
    }
  }

  private record StoredSession(Map<String, Object> state, Instant lastUpdateTime) {}
}
