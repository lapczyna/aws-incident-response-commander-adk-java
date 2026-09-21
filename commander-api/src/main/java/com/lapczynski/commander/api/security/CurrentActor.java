package com.lapczynski.commander.api.security;

import com.lapczynski.commander.domain.approval.Actor;
import com.lapczynski.commander.domain.approval.ActorRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Who is making this request, in the domain's terms.
 *
 * <p>One place where an authenticated principal becomes an {@link Actor}, because the alternative
 * is every controller constructing one — and separation of duties depends on the actor's id being
 * stable and truthful. An approver whose id varied between requests could approve an incident they
 * opened, since the check compares ids.
 *
 * <p><strong>The system actor is never produced here.</strong> {@code Actor.SYSTEM} is what the
 * workflow acts as, and {@code ApprovalService} refuses it explicitly: the system cannot approve
 * its own remediation. A request that arrives unauthenticated therefore gets an anonymous viewer,
 * which can read and decide nothing, rather than anything that looks like the system.
 */
@Component
public class CurrentActor {

  /**
   * The actor for an unauthenticated request.
   *
   * <p>A viewer, so that {@code role().canApprove()} is false. Reached only on paths security
   * permits without authentication; a controller that is reachable anonymously and calls this gets
   * an actor that can do nothing rather than a null to dereference.
   */
  private static final Actor ANONYMOUS = new Actor("anonymous", "Anonymous", ActorRole.VIEWER);

  public Actor get() {
    return from(SecurityContextHolder.getContext().getAuthentication());
  }

  /** Exposed for the console and for tests, which hold the authentication already. */
  public Actor from(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()) {
      return ANONYMOUS;
    }

    String id = authentication.getName();
    if (id == null || id.isBlank() || "anonymousUser".equals(id)) {
      return ANONYMOUS;
    }

    return new Actor(id, id, highestRole(authentication));
  }

  /**
   * The strongest role the principal holds.
   *
   * <p>Ordered deliberately: a principal with both APPROVER and INVESTIGATOR is an approver. Taking
   * the first authority instead would make behaviour depend on the order a directory happened to
   * return groups in, which is the kind of thing that works for a year and then does not.
   */
  private static ActorRole highestRole(Authentication authentication) {
    if (hasAuthority(authentication, "ROLE_APPROVER")) {
      return ActorRole.APPROVER;
    }
    return hasAuthority(authentication, "ROLE_INVESTIGATOR")
        ? ActorRole.INVESTIGATOR
        : ActorRole.VIEWER;
  }

  private static boolean hasAuthority(Authentication authentication, String authority) {
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(authority::equals);
  }
}
