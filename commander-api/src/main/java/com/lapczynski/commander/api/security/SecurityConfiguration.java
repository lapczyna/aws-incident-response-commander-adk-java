package com.lapczynski.commander.api.security;

import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/**
 * Who may reach what.
 *
 * <p>Three roles, matching the domain's {@code ActorRole}: a viewer reads, an investigator opens
 * incidents and starts investigations, an approver decides. The mapping from an authenticated
 * principal to a domain actor is in {@link CurrentActor}; this file decides only which requests are
 * allowed through at all.
 *
 * <p><strong>Method security is the real boundary, not this.</strong> The URL rules below are a
 * coarse first pass; {@code @PreAuthorize} on the approval endpoints is what actually stops a
 * non-approver deciding, and {@code ApprovalService} refuses again on the actor's role after that.
 * A URL pattern is one refactor away from not matching the endpoint it was written for, which is
 * why it is not the only thing standing between a viewer and a production change.
 *
 * <p>Two chains, by profile. {@code local-identity} is form-and-basic with fixed users, for the
 * demo; {@code oidc} validates bearer tokens from a real identity provider. Neither is a default:
 * one of them has to be chosen, because a security configuration that falls back to something is a
 * security configuration nobody read.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

  private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

  /**
   * Paths that are open regardless of profile.
   *
   * <p>{@code /webjars/**} is here because the console's only script is served from the jar rather
   * than a CDN, and a login page whose stylesheet and script are behind the login is a page that
   * renders as unstyled text on the one screen where a reader most needs to trust what they see.
   */
  private static final String[] PUBLIC = {
    "/actuator/health",
    "/actuator/health/**",
    "/actuator/info",
    "/login",
    "/css/**",
    "/js/**",
    "/webjars/**"
  };

  /**
   * The demo chain: fixed users, HTTP Basic, and a session for the console.
   *
   * <p>Active under {@code local-identity}, which is on by default in {@code application.yaml} and
   * should be replaced by {@code oidc} anywhere that matters. The users below exist so the approval
   * flow can be demonstrated — including the part where the person who opened an incident is
   * refused when they try to approve acting on it, which needs two identities to show at all.
   */
  @Bean
  @Profile("local-identity")
  public SecurityFilterChain localChain(HttpSecurity http) throws Exception {
    log.warn(
        "Security profile: local-identity. Fixed demo credentials are in use. Do not run this "
            + "where it can be reached by anyone you would not hand the password to.");

    http.authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(PUBLIC)
                    .permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers(EndpointRequest.toAnyEndpoint())
                    .hasRole("APPROVER")
                    .anyRequest()
                    .authenticated())
        .httpBasic(basic -> {})
        .formLogin(form -> form.defaultSuccessUrl("/console", true))
        // Both mechanisms are wanted — Basic for scripts, the form for the console — but only one
        // of them can answer an unauthenticated request. Without this, Basic wins and an operator
        // opening the console gets a browser credential dialog instead of the login page, which
        // is both uglier and unable to say which demo identities exist.
        .exceptionHandling(
            exceptions ->
                exceptions.defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/login"), browserRequest()))
        .logout(logout -> logout.logoutSuccessUrl("/login"))
        // Enabled for the browser console, and disabled for the API, which is called with Basic
        // credentials by scripts and carries no ambient session to forge a request from.
        .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"));

    return http.build();
  }

  /**
   * The real chain: bearer tokens, no session, no fallback.
   *
   * <p>Roles come from the token's claims, mapped by {@link OidcRoleConverter}. A token with no
   * recognised role authenticates as a viewer, which can read and decide nothing — the safe
   * direction for a claim-mapping mistake.
   */
  @Bean
  @Profile("oidc")
  public SecurityFilterChain oidcChain(HttpSecurity http, OidcRoleConverter roles)
      throws Exception {
    log.info("Security profile: oidc. Bearer tokens only; no local users exist.");

    http.authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(PUBLIC)
                    .permitAll()
                    .requestMatchers(EndpointRequest.toAnyEndpoint())
                    .hasRole("APPROVER")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(roles)))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // No session to ride, so nothing for CSRF to protect against.
        .csrf(csrf -> csrf.disable());

    return http.build();
  }

  /**
   * The demo identities.
   *
   * <p>Three of them, and the third one matters: {@code reporter} can open incidents and cannot
   * approve, which is what makes the separation-of-duties refusal demonstrable rather than merely
   * documented.
   *
   * <p>Passwords come from configuration with a development default, and the default is logged as a
   * warning above. {@code {noop}} is deliberately absent — these are bcrypt-encoded through the
   * delegating encoder, so a copied configuration cannot end up with a plaintext password in a
   * properties file.
   */
  @Bean
  @Profile("local-identity")
  public InMemoryUserDetailsManager demoUsers(
      PasswordEncoder encoder,
      @org.springframework.beans.factory.annotation.Value(
              "${commander.security.demo-password:commander}")
          String password) {

    String encoded = encoder.encode(password);

    UserDetails approver =
        User.withUsername("approver").password(encoded).roles("APPROVER").build();
    UserDetails responder =
        User.withUsername("responder").password(encoded).roles("INVESTIGATOR").build();
    UserDetails viewer = User.withUsername("viewer").password(encoded).roles("VIEWER").build();

    return new InMemoryUserDetailsManager(List.of(approver, responder, viewer));
  }

  /**
   * A request from a browser, as distinct from one from a script.
   *
   * <p>A wildcard {@code Accept} header is explicitly not a browser. A client that states no
   * preference — curl, and MockMvc unless told otherwise — would otherwise match {@code text/html}
   * and be sent a 302 to a login page it cannot use, instead of the 401 that tells it to send
   * credentials.
   */
  private static MediaTypeRequestMatcher browserRequest() {
    MediaTypeRequestMatcher matcher = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
    matcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));
    return matcher;
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }
}
