# ADR-0008: One bridge between ADK's RxJava3 Flowable and Spring's servlet stack

- **Status:** Accepted
- **Date:** 2026-09-08
- **Phase:** 0

## Context

ADK Java is built on **RxJava 3**: `Runner.runAsync(...)` returns `Flowable<Event>`,
`BaseSessionService` returns `Single` / `Maybe` / `Completable`, and tools return
`Single<Map<String, Object>>`. Spring Boot 4 on the servlet stack uses `SseEmitter`, and its
reactive support is Reactor. RxJava is not Reactor, and neither is the servlet API.

The UI needs live investigation progress, and persistence needs to write ADK events as they occur.

## Decision

A single bridge class in `commander-adk`. Nothing outside that module sees an `io.reactivex` type —
`ArchitectureRulesTest` forbids `io.reactivex..` in the domain, and ADK types are confined to
`commander-adk` generally.

The bridge is responsible for:

1. **`Flowable<Event>` to `SseEmitter`**, with backpressure handled explicitly. The UI is a slow
   consumer, so the strategy keeps the latest state and drops superseded intermediate frames rather
   than buffering without bound. A slow browser must never stall an investigation.
2. **Scheduler ownership.** ADK work runs on a bounded, named scheduler — not on the servlet request
   thread and not on RxJava's default computation pool. Blocking JDBC inside the session service
   gets its own bounded IO scheduler. Both are sized by configuration and instrumented.
3. **MDC propagation.** RxJava does not carry SLF4J's MDC across operator boundaries, so
   `incidentId`, `sessionId`, `invocationId`, `traceId`, `toolCallId` and `approvalId` would vanish
   from log lines the moment work hops a scheduler. The bridge captures the context map at
   subscription and restores it around each emission. Without this, correlated logging — one of the
   project's stated goals — silently does not work.
4. **Terminal handling.** `doOnComplete`, `doOnError` and cancellation each persist a definite
   outcome. An incident must never be left in a running state because a stream ended quietly.

## Consequences

**Good**
- RxJava stays an implementation detail of one module.
- Correlation IDs survive the async boundary, so an incident is traceable end to end.
- Thread pools are explicit and monitored rather than inherited by accident.

**Costs**
- Bridging two async models is genuinely fiddly, and subscription-time context capture is easy to
  get subtly wrong. It gets focused tests, including one asserting MDC survives a scheduler hop.
- Blocking JDBC behind an Rx interface needs care: an undersized IO scheduler starves the event
  stream and presents as a hung investigation.

**Rejected:** adding RxJava-to-Reactor adapters and going reactive end to end. That would spread
reactive types through the API and persistence layers for no benefit — this workload is a handful of
long-running investigations, not a high-concurrency service.
