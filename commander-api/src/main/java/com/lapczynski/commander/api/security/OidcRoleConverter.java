package com.lapczynski.commander.api.security;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Turns a token's group claim into roles this system recognises.
 *
 * <p>An allowlist, not a translation. Only three role names mean anything here, and a claim
 * carrying anything else contributes nothing — so a token from a provider with a large group
 * directory cannot grant authority by accident, and a group named {@code admin} somewhere else in
 * the organisation does not become an approver here.
 *
 * <p>A token with no recognised group authenticates as a viewer. That is the safe direction: a
 * claim-mapping mistake produces someone who can read and decide nothing, rather than someone who
 * can authorise a production change.
 */
@Component
@Profile("oidc")
public class OidcRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

  /** The only group names that mean anything. Everything else is ignored. */
  private static final Set<String> RECOGNISED = Set.of("APPROVER", "INVESTIGATOR", "VIEWER");

  private final String claim;
  private final String principalClaim;

  public OidcRoleConverter(
      @Value("${commander.security.roles-claim:groups}") String claim,
      @Value("${commander.security.principal-claim:preferred_username}") String principalClaim) {
    this.claim = claim;
    this.principalClaim = principalClaim;
  }

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    List<String> groups = jwt.getClaimAsStringList(claim);

    Collection<GrantedAuthority> authorities =
        groups == null
            ? List.of()
            : groups.stream()
                .filter(java.util.Objects::nonNull)
                .map(group -> group.trim().toUpperCase(Locale.ROOT))
                .filter(RECOGNISED::contains)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .distinct()
                .toList();

    // The principal name is the actor id used for separation of duties, so it must identify a
    // person stably. Falling back to the subject rather than to a display name: a display name can
    // change, and an actor id that changed would let someone approve an incident they opened.
    String principal = jwt.getClaimAsString(principalClaim);
    String name = principal == null || principal.isBlank() ? jwt.getSubject() : principal;

    return new JwtAuthenticationToken(jwt, authorities, name);
  }
}
