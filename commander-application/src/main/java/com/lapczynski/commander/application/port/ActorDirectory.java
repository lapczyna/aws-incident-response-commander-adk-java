package com.lapczynski.commander.application.port;

import com.lapczynski.commander.domain.approval.Actor;

/**
 * Records who the system has seen act.
 *
 * <p>Exists because "who approved this" has to be answerable without inference. Approval decisions,
 * status transitions and audit events all reference an actor row, so an actor who has never been
 * recorded cannot be written as having done anything — the database refuses the write rather than
 * storing an unattributable one.
 *
 * <p>This is <strong>not</strong> authentication or authorisation. It records an identity that has
 * already been authenticated elsewhere and whose role has already been decided; it grants nothing.
 * Registering an actor as an approver does not make them one — {@code ApprovalService} checks the
 * role on the actor it is handed, and Spring Security decided that role before the request reached
 * any of this.
 */
public interface ActorDirectory {

  /**
   * Records an actor, or updates the name and role of one already known.
   *
   * <p>Called before an actor is written anywhere that references them. Updating on every sighting
   * rather than only on first sight is deliberate: a person whose role changed in the identity
   * provider should be described correctly in the next incident, and a stale row would make the
   * audit trail disagree with the directory it came from.
   */
  void record(Actor actor);
}
