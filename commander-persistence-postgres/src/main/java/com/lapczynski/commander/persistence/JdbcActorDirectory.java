package com.lapczynski.commander.persistence;

import com.lapczynski.commander.application.port.ActorDirectory;
import com.lapczynski.commander.domain.approval.Actor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Upserts the actors that other tables reference.
 *
 * <p>One statement, and the {@code ON CONFLICT} clause is the whole design. Two requests from the
 * same person arriving together would otherwise race between "does this row exist" and "insert it",
 * and the loser would fail a request for a reason that has nothing to do with what it was trying to
 * do. Letting the database resolve it means the race has no losing side.
 *
 * <p>The role is updated on conflict rather than left alone. A person whose role changed should be
 * described correctly in the next incident; a row that recorded what they were a year ago would
 * make the audit trail disagree with the identity provider it came from.
 */
@Repository
public class JdbcActorDirectory implements ActorDirectory {

  private final JdbcClient jdbc;

  public JdbcActorDirectory(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void record(Actor actor) {
    jdbc.sql(
            """
            INSERT INTO actors (id, display_name, role)
            VALUES (:id, :displayName, :role)
            ON CONFLICT (id) DO UPDATE
                SET display_name = EXCLUDED.display_name,
                    role         = EXCLUDED.role
            """)
        .param("id", actor.id())
        .param("displayName", actor.displayName())
        .param("role", actor.role().name())
        .update();
  }
}
