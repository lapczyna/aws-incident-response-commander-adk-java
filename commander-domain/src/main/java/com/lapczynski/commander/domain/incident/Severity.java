package com.lapczynski.commander.domain.incident;

/**
 * Incident severity as reported by the alert source.
 *
 * <p>Deliberately separate from remediation risk. A low-severity incident can still warrant a
 * high-risk fix, and a critical one can be resolved by something harmless. Conflating the two would
 * let urgency argue its way past the approval requirement.
 */
public enum Severity {
  SEV1,
  SEV2,
  SEV3,
  SEV4;

  /** Whether this severity is severe enough to page a human immediately. */
  public boolean isPageworthy() {
    return this == SEV1 || this == SEV2;
  }
}
