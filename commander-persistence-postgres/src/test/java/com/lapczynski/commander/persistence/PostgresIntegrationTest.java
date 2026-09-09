package com.lapczynski.commander.persistence;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that need a real PostgreSQL.
 *
 * <p>Real PostgreSQL rather than H2: this project's schema uses partial unique indexes, arrays,
 * JSONB and a plpgsql trigger, none of which an in-memory substitute reproduces faithfully. A test
 * that passes against a fake database and fails against the real one is worse than no test.
 *
 * <p>The container is static, so one instance is shared by every subclass in the module. Flyway
 * runs once against it; individual tests clean up after themselves rather than paying for a fresh
 * container each time.
 */
@SpringBootTest(classes = PersistenceTestApplication.class)
@Tag("integration")
public abstract class PostgresIntegrationTest {

  @SuppressWarnings("resource") // Closed by the Testcontainers JVM shutdown hook.
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("commander")
          .withUsername("commander")
          .withPassword("commander")
          .withReuse(false);

  static {
    POSTGRES.start();
  }

  /**
   * Removes all incidents and everything cascading from them.
   *
   * <p>Deleting an incident cascades into audit_events, which is append-only, so the deletion is
   * refused unless maintenance intent is declared. Tests are held to the same rule as production
   * retention code rather than being given a private back door: if this statement were not needed,
   * the append-only guarantee would not be real.
   *
   * <p>The work is wrapped in a {@code DO} block because the declaration is transaction-scoped and
   * the JDBC connection is in autocommit. Issued as two separate statements, {@code SET LOCAL}
   * would apply to a transaction that ends immediately and the following {@code DELETE} would be
   * refused - silently the wrong shape, loudly the wrong result. A {@code DO} block is one
   * transaction, so {@code set_config(..., is_local => true)} covers the delete.
   */
  protected static void truncateIncidents(JdbcClient jdbc) {
    jdbc.sql(
            """
            DO $$
            BEGIN
                PERFORM set_config('app.audit_maintenance', 'on', true);
                DELETE FROM incidents;
            END
            $$
            """)
        .update();
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }
}
