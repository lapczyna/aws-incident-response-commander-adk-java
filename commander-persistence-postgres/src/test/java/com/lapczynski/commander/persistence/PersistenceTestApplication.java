package com.lapczynski.commander.persistence;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot context for persistence tests.
 *
 * <p>Scoped to this package so the tests boot a datasource, Flyway and the repositories, and
 * nothing else. Booting the full commander application here would drag in ADK and the AWS adapters,
 * making a schema test depend on things that have nothing to do with the schema.
 */
@SpringBootApplication
class PersistenceTestApplication {}
