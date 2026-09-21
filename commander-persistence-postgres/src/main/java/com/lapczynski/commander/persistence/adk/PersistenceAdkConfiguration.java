package com.lapczynski.commander.persistence.adk;

import com.google.adk.sessions.BaseSessionService;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes the PostgreSQL implementations of ADK's service SPIs.
 *
 * <p>In this module rather than in the application because it is the documented exception to the
 * rule that ADK types stay inside {@code commander-adk}: implementing ADK's session SPI requires
 * naming ADK's session types (ADR-0004). {@code ArchitectureRulesTest} permits it here and nowhere
 * else.
 *
 * <p>This bean is what makes an approval survive a restart. With ADK's in-memory session service, a
 * process that stops while an incident waits for a human loses the invocation; with this one, the
 * invocation is rows in PostgreSQL and a different process can resume it.
 */
@Configuration
public class PersistenceAdkConfiguration {

  /**
   * The session service the runners share.
   *
   * <p>On the IO scheduler, explicitly. Every operation here is blocking JDBC, and running it on
   * RxJava's computation scheduler — sized to the CPU count — would let a handful of concurrent
   * investigations starve every other stream in the process.
   */
  @Bean
  public BaseSessionService sessionService(JdbcClient jdbc, TransactionTemplate transactions) {
    return new PostgresSessionService(jdbc, transactions, ioScheduler());
  }

  private static Scheduler ioScheduler() {
    return Schedulers.io();
  }
}
