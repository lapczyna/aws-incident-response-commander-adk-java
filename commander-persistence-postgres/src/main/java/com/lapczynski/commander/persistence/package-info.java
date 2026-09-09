/**
 * PostgreSQL persistence: Flyway migrations, JDBC repositories, and custom implementations of ADK's
 * session, memory and artifact SPIs.
 *
 * <p>ADK for Java ships no JDBC session service, so {@code PostgresSessionService} exists to make
 * incidents survive a restart while awaiting approval. See ADR-0004.
 */
package com.lapczynski.commander.persistence;
