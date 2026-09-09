package com.lapczynski.commander.domain.incident;

import java.util.Objects;

/**
 * The service an incident concerns, identified by name and environment.
 *
 * <p>Environment is part of the identity on purpose. "payments in staging" and "payments in
 * production" are different targets, and the policy engine relies on that distinction to refuse
 * actions aimed at the wrong environment.
 */
public record ServiceRef(String name, String environment) {

  public ServiceRef {
    Objects.requireNonNull(name, "service name must not be null");
    Objects.requireNonNull(environment, "environment must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("service name must not be blank");
    }
    if (environment.isBlank()) {
      throw new IllegalArgumentException("environment must not be blank");
    }
  }

  @Override
  public String toString() {
    return name + "@" + environment;
  }
}
