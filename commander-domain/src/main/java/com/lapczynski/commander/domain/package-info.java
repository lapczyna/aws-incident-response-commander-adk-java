/**
 * The incident domain: aggregate, state machine, evidence and hypothesis models, and the
 * deterministic policy engine that decides whether an action may execute.
 *
 * <p>This package is framework-free by design. It must not reference Spring, Google ADK, the AWS
 * SDK or JDBC; {@code ArchitectureRulesTest} in commander-api enforces that. Everything an LLM must
 * never be trusted to decide lives here, in plain Java that can be exhaustively unit-tested.
 */
package com.lapczynski.commander.domain;
