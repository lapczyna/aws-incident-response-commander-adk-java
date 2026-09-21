package com.lapczynski.commander.api;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that drive the real application over HTTP.
 *
 * <p>The whole context: every bean, the real agent runtime, the real policy engine, the real
 * PostgreSQL. The only thing that is not real is the model, and it is not real in the way the
 * {@code fake} profile is not real — it calls every tool it is offered and returns the same
 * proposal every time, which is exactly what makes the surrounding machinery testable.
 *
 * <p>Real PostgreSQL rather than an in-memory substitute, for the reason the persistence tests
 * give: the schema uses partial unique indexes, arrays, JSONB and a trigger, and a test that passes
 * against a fake database and fails against the real one is worse than no test. It also means the
 * ADK session service under test is the one that will run in production.
 */
@SpringBootTest(classes = CommanderApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles({"fake", "simulator", "local-identity"})
@Tag("integration")
abstract class ApiIntegrationTest {

  @SuppressWarnings("resource") // Closed by the Testcontainers JVM shutdown hook.
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("commander")
          .withUsername("commander")
          .withPassword("commander");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }
}
