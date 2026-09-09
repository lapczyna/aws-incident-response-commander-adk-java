package com.lapczynski.commander.persistence.adk;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.adk.sessions.State;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.lapczynski.commander.persistence.PostgresIntegrationTest;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs one suite against <em>both</em> {@link InMemorySessionService} and {@link
 * PostgresSessionService}.
 *
 * <p>This is the test that makes ADR-0004 safe. A custom implementation of someone else's SPI fails
 * in a particularly unpleasant way: not by throwing, but by handling state slightly differently, so
 * the in-memory profile behaves one way and the durable profile another. The demo works, production
 * does not, and nothing points at the session service.
 *
 * <p>Running the same assertions against ADK's own implementation makes ADK the oracle. Where the
 * two disagree, the custom one is wrong by definition.
 */
class SessionServiceContractTest extends PostgresIntegrationTest {

  private static final String APP = "commander";
  private static final String USER = "operator";

  @Autowired private JdbcClient jdbc;
  @Autowired private TransactionTemplate transactions;

  private PostgresSessionService postgres;

  @BeforeEach
  void setUp() {
    jdbc.sql("DELETE FROM adk_sessions").update();
    jdbc.sql("DELETE FROM adk_app_state").update();
    jdbc.sql("DELETE FROM adk_user_state").update();
    postgres = new PostgresSessionService(jdbc, transactions, Schedulers.io());
  }

  /**
   * The implementations under test.
   *
   * <p>Supplied as suppliers rather than instances so each test gets a clean pair; a shared
   * in-memory service would leak sessions between tests and mask ordering bugs.
   */
  static Stream<Arguments> implementations() {
    return Stream.of(
        Arguments.of(
            "InMemory (ADK's own)",
            (java.util.function.Supplier<BaseSessionService>) InMemorySessionService::new),
        Arguments.of("Postgres (ours)", (java.util.function.Supplier<BaseSessionService>) null));
  }

  /** Resolves the supplier, substituting the Postgres instance for the null placeholder. */
  private BaseSessionService resolve(java.util.function.Supplier<BaseSessionService> supplier) {
    return supplier == null ? postgres : supplier.get();
  }

  private static Event event(String author, String text, Map<String, Object> stateDelta) {
    EventActions actions =
        EventActions.builder()
            .stateDelta(new java.util.concurrent.ConcurrentHashMap<>(stateDelta))
            .build();
    return Event.builder()
        .id(Event.generateEventId())
        .invocationId("inv-" + UUID.randomUUID())
        .author(author)
        .content(Content.fromParts(Part.fromText(text)))
        .actions(actions)
        .timestamp(Instant.now().toEpochMilli())
        .build();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("implementations")
  @DisplayName("a session round-trips with its state")
  void sessionRoundTrips(String name, java.util.function.Supplier<BaseSessionService> supplier) {
    BaseSessionService service = resolve(supplier);

    Session created =
        service.createSession(APP, USER, Map.of("incidentId", "inc-1"), "s-1").blockingGet();

    assertThat(created.id()).isEqualTo("s-1");
    assertThat(created.state()).containsEntry("incidentId", "inc-1");

    Session loaded = service.getSession(APP, USER, "s-1", Optional.empty()).blockingGet();
    assertThat(loaded).isNotNull();
    assertThat(loaded.state()).containsEntry("incidentId", "inc-1");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("implementations")
  @DisplayName("a missing session yields empty")
  void missingSessionIsEmpty(
      String name, java.util.function.Supplier<BaseSessionService> supplier) {
    BaseSessionService service = resolve(supplier);

    assertThat(service.getSession(APP, USER, "nope", Optional.empty()).blockingGet()).isNull();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("implementations")
  @DisplayName("events are returned in the order they were appended")
  void eventsKeepOrder(String name, java.util.function.Supplier<BaseSessionService> supplier) {
    BaseSessionService service = resolve(supplier);
    Session session = service.createSession(APP, USER, Map.of(), "s-2").blockingGet();

    service.appendEvent(session, event("user", "first", Map.of())).blockingGet();
    service.appendEvent(session, event("agent", "second", Map.of())).blockingGet();
    service.appendEvent(session, event("agent", "third", Map.of())).blockingGet();

    List<Event> events = service.listEvents(APP, USER, "s-2").blockingGet().events();

    assertThat(events)
        .as("replaying a conversation out of order reconstructs a different conversation")
        .hasSize(3)
        .extracting(
            e -> e.content().orElseThrow().parts().orElseThrow().getFirst().text().orElseThrow())
        .containsExactly("first", "second", "third");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("implementations")
  @DisplayName("a state delta is applied to session state")
  void stateDeltaIsApplied(String name, java.util.function.Supplier<BaseSessionService> supplier) {
    BaseSessionService service = resolve(supplier);
    Session session = service.createSession(APP, USER, Map.of(), "s-3").blockingGet();

    service
        .appendEvent(session, event("agent", "found it", Map.of("hypothesis", "bad deploy")))
        .blockingGet();

    Session reloaded = service.getSession(APP, USER, "s-3", Optional.empty()).blockingGet();
    assertThat(reloaded.state()).containsEntry("hypothesis", "bad deploy");
  }

  /**
   * A deliberate, documented divergence from {@link InMemorySessionService}.
   *
   * <p>ADK is internally inconsistent here. {@code BaseSessionService}'s own default {@code
   * appendEvent} explicitly skips keys prefixed {@code temp:}, but {@code InMemorySessionService}
   * overrides that method and its catch-all branch writes every non-{@code app:}/{@code user:} key
   * into session state — {@code temp:} included — before delegating to the default.
   *
   * <p>For an in-memory store the distinction is nearly invisible: the session object is the store,
   * nothing is ever reloaded, and per-invocation scratch that lingers costs only memory. For a
   * durable store it matters. Writing {@code temp:} state to PostgreSQL would make scratch values
   * outlive the invocation that created them and reappear on the next reload — precisely what the
   * prefix exists to prevent.
   *
   * <p>This implementation therefore follows the <em>interface contract</em> rather than ADK's
   * in-memory behaviour. The parameterised suite above deliberately excludes this case, because
   * asserting a shared contract that ADK's own implementation does not honour would mean either
   * failing on ADK's code or copying a bug into the durable path.
   */
  @Test
  @DisplayName("temp: keys are not persisted, diverging from ADK's in-memory behaviour")
  void tempKeysAreNotPersistedByPostgres() {
    Session session = postgres.createSession(APP, USER, Map.of(), "s-4").blockingGet();

    postgres
        .appendEvent(
            session, event("agent", "scratch", Map.of(State.TEMP_PREFIX + "scratch", "discard me")))
        .blockingGet();

    Session reloaded = postgres.getSession(APP, USER, "s-4", Optional.empty()).blockingGet();

    assertThat(reloaded.state())
        .as("temp: is per-invocation scratch; persisting it would resurrect it on every reload")
        .doesNotContainKey(State.TEMP_PREFIX + "scratch");

    Integer rows =
        jdbc.sql("SELECT count(*) FROM adk_sessions WHERE state::text LIKE '%temp:%'")
            .query(Integer.class)
            .single();
    assertThat(rows).as("nothing temp:-prefixed reached storage at all").isZero();
  }

  @Test
  @DisplayName("ADK's in-memory service does keep temp: state, which is why the case is excluded")
  void inMemoryServiceRetainsTempState() {
    BaseSessionService inMemory = new InMemorySessionService();
    Session session = inMemory.createSession(APP, USER, Map.of(), "s-4b").blockingGet();

    inMemory
        .appendEvent(
            session, event("agent", "scratch", Map.of(State.TEMP_PREFIX + "scratch", "kept")))
        .blockingGet();

    Session reloaded = inMemory.getSession(APP, USER, "s-4b", Optional.empty()).blockingGet();

    assertThat(reloaded.state())
        .as("pins ADK's actual behaviour so this divergence is noticed if upstream changes it")
        .containsEntry(State.TEMP_PREFIX + "scratch", "kept");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("implementations")
  @DisplayName("app: state is shared across sessions")
  void appStateIsShared(String name, java.util.function.Supplier<BaseSessionService> supplier) {
    BaseSessionService service = resolve(supplier);
    Session first = service.createSession(APP, USER, Map.of(), "s-5a").blockingGet();

    service
        .appendEvent(first, event("agent", "note", Map.of(State.APP_PREFIX + "runbook", "v3")))
        .blockingGet();

    service.createSession(APP, USER, Map.of(), "s-5b").blockingGet();
    Session second = service.getSession(APP, USER, "s-5b", Optional.empty()).blockingGet();

    assertThat(second.state())
        .as("app-scoped state belongs to the app, not to whichever session happened to write it")
        .containsEntry(State.APP_PREFIX + "runbook", "v3");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("implementations")
  @DisplayName("user: state is shared across a user's sessions")
  void userStateIsShared(String name, java.util.function.Supplier<BaseSessionService> supplier) {
    BaseSessionService service = resolve(supplier);
    Session first = service.createSession(APP, USER, Map.of(), "s-6a").blockingGet();

    service
        .appendEvent(first, event("agent", "note", Map.of(State.USER_PREFIX + "timezone", "UTC")))
        .blockingGet();

    service.createSession(APP, USER, Map.of(), "s-6b").blockingGet();
    Session second = service.getSession(APP, USER, "s-6b", Optional.empty()).blockingGet();

    assertThat(second.state()).containsEntry(State.USER_PREFIX + "timezone", "UTC");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("implementations")
  @DisplayName("app state survives deletion of the session that wrote it")
  void appStateSurvivesSessionDeletion(
      String name, java.util.function.Supplier<BaseSessionService> supplier) {
    BaseSessionService service = resolve(supplier);
    Session session = service.createSession(APP, USER, Map.of(), "s-7").blockingGet();

    service
        .appendEvent(session, event("agent", "note", Map.of(State.APP_PREFIX + "policy", "strict")))
        .blockingGet();
    service.deleteSession(APP, USER, "s-7").blockingAwait();

    service.createSession(APP, USER, Map.of(), "s-7b").blockingGet();
    Session fresh = service.getSession(APP, USER, "s-7b", Optional.empty()).blockingGet();

    assertThat(fresh.state())
        .as("deleting one session must not discard state shared by all of them")
        .containsEntry(State.APP_PREFIX + "policy", "strict");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("implementations")
  @DisplayName("a partial event is not persisted")
  void partialEventsAreNotPersisted(
      String name, java.util.function.Supplier<BaseSessionService> supplier) {
    BaseSessionService service = resolve(supplier);
    Session session = service.createSession(APP, USER, Map.of(), "s-8").blockingGet();

    Event partial =
        Event.builder()
            .id(Event.generateEventId())
            .author("agent")
            .content(Content.fromParts(Part.fromText("half a th")))
            .partial(true)
            .timestamp(Instant.now().toEpochMilli())
            .build();

    service.appendEvent(session, partial).blockingGet();

    assertThat(service.listEvents(APP, USER, "s-8").blockingGet().events())
        .as("streaming fragments would replay a conversation as a stutter of half-formed messages")
        .isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("implementations")
  @DisplayName("numRecentEvents returns the most recent events, not the oldest")
  void numRecentEventsKeepsTheTail(
      String name, java.util.function.Supplier<BaseSessionService> supplier) {
    BaseSessionService service = resolve(supplier);
    Session session = service.createSession(APP, USER, Map.of(), "s-9").blockingGet();

    for (int i = 1; i <= 5; i++) {
      service.appendEvent(session, event("agent", "event-" + i, Map.of())).blockingGet();
    }

    Session limited =
        service
            .getSession(
                APP,
                USER,
                "s-9",
                Optional.of(GetSessionConfig.builder().numRecentEvents(2).build()))
            .blockingGet();

    assertThat(limited.events())
        .hasSize(2)
        .extracting(
            e -> e.content().orElseThrow().parts().orElseThrow().getFirst().text().orElseThrow())
        .containsExactly("event-4", "event-5");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("implementations")
  @DisplayName("listSessions finds a user's sessions")
  void listSessionsFindsThem(
      String name, java.util.function.Supplier<BaseSessionService> supplier) {
    BaseSessionService service = resolve(supplier);
    service.createSession(APP, USER, Map.of(), "s-10a").blockingGet();
    service.createSession(APP, USER, Map.of(), "s-10b").blockingGet();

    assertThat(service.listSessions(APP, USER).blockingGet().sessions())
        .extracting(Session::id)
        .contains("s-10a", "s-10b");
  }

  @Test
  @DisplayName("a restarted process reconstructs the session from storage alone")
  void survivesProcessRestart() {
    Session session = postgres.createSession(APP, USER, Map.of(), "s-restart").blockingGet();
    postgres
        .appendEvent(session, event("agent", "investigating", Map.of("stage", "INVESTIGATING")))
        .blockingGet();
    postgres
        .appendEvent(session, event("agent", "awaiting", Map.of("stage", "AWAITING_APPROVAL")))
        .blockingGet();

    // A brand new service instance, as a restarted JVM would have: no shared memory, only the
    // database. This is the property the whole approval story depends on.
    PostgresSessionService afterRestart =
        new PostgresSessionService(jdbc, transactions, Schedulers.io());

    Session recovered =
        afterRestart.getSession(APP, USER, "s-restart", Optional.empty()).blockingGet();

    assertThat(recovered).isNotNull();
    assertThat(recovered.state()).containsEntry("stage", "AWAITING_APPROVAL");
    assertThat(recovered.events())
        .as("the events are what ADK replays to resume the invocation that was waiting")
        .hasSize(2);
  }
}
