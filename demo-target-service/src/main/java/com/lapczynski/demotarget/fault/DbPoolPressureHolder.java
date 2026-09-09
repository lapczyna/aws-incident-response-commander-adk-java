package com.lapczynski.demotarget.fault;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Starves the connection pool by holding connections open.
 *
 * <p>The important constraint: it will never hold the <em>last</em> connection. The count is capped
 * at {@code poolSize - 1}, so the service always retains capacity to serve a request — including
 * the request that releases the fault. Holding the whole pool would deadlock the service against
 * its own admin endpoint and the only way out would be a restart, which is not a demo, it is a
 * trap.
 *
 * <p>The DataSource is optional. The service can run without a database for tests and simple demos;
 * asking for pool pressure then reports that the fault is inapplicable rather than failing to
 * start.
 */
@Component
public class DbPoolPressureHolder {

  private static final Logger log = LoggerFactory.getLogger(DbPoolPressureHolder.class);

  private final FaultRegistry registry;
  private final ObjectProvider<DataSource> dataSourceProvider;
  private final List<Connection> held = new ArrayList<>();

  public DbPoolPressureHolder(
      FaultRegistry registry, ObjectProvider<DataSource> dataSourceProvider) {
    this.registry = registry;
    this.dataSourceProvider = dataSourceProvider;
  }

  /** Acquires or releases connections to match the current fault state. */
  @Scheduled(fixedDelay = 1_000)
  public synchronized void reconcile() {
    var fault = registry.active(FaultType.DB_POOL_PRESSURE);

    if (fault.isEmpty()) {
      releaseAll();
      return;
    }
    if (!held.isEmpty()) {
      return;
    }

    DataSource dataSource = dataSourceProvider.getIfAvailable();
    if (dataSource == null) {
      log.warn("DB pool pressure requested but no DataSource is configured; ignoring");
      return;
    }

    int requested = fault.get().intParam("connections", 1);
    int ceiling = safeCeiling(dataSource);
    int toHold = Math.min(requested, ceiling);

    if (toHold <= 0) {
      log.warn("DB pool pressure requested but the pool is too small to leave a spare connection");
      return;
    }

    log.warn(
        "DB pool pressure starting: holding {} connection(s) (requested {}, ceiling {})",
        toHold,
        requested,
        ceiling);

    for (int i = 0; i < toHold; i++) {
      try {
        held.add(dataSource.getConnection());
      } catch (SQLException e) {
        // Running out of connections while trying to starve the pool is a legitimate outcome, not
        // an error worth failing on.
        log.info(
            "DB pool pressure stopped acquiring at {} connection(s): {}",
            held.size(),
            e.getMessage());
        break;
      }
    }
  }

  /**
   * The most connections that may be held: one fewer than the pool's maximum.
   *
   * <p>Falls back to a conservative single connection when the pool size cannot be determined,
   * because guessing high here is how a demo becomes unrecoverable.
   */
  private int safeCeiling(DataSource dataSource) {
    if (dataSource instanceof HikariDataSource hikari) {
      return Math.max(0, hikari.getMaximumPoolSize() - 1);
    }
    return 1;
  }

  private void releaseAll() {
    if (held.isEmpty()) {
      return;
    }
    log.info("DB pool pressure ending: releasing {} connection(s)", held.size());
    for (Connection connection : held) {
      try {
        connection.close();
      } catch (SQLException e) {
        log.warn("Could not release a held connection: {}", e.getMessage());
      }
    }
    held.clear();
  }

  /** Releases everything on shutdown, so a fault cannot outlive the process that created it. */
  @PreDestroy
  public synchronized void shutdown() {
    releaseAll();
  }

  /** How many connections are currently held. Exposed for tests and the status endpoint. */
  public synchronized int heldConnections() {
    return held.size();
  }
}
