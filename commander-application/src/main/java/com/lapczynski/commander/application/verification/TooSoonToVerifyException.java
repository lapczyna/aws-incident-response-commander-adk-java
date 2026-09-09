package com.lapczynski.commander.application.verification;

import java.time.Instant;

/**
 * Thrown when recovery is checked before there is enough post-remediation data to check it with.
 *
 * <p>An exception rather than a "not yet recovered" answer, because the two are not the same and
 * conflating them would send an incident to {@code FAILED} for the sole reason that the system
 * asked too early. The caller waits until {@link #readyAt()} and asks again.
 */
public class TooSoonToVerifyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient Instant readyAt;

  public TooSoonToVerifyException(Instant readyAt) {
    super(
        "not enough post-remediation data to judge recovery yet; the observation window is "
            + "complete at "
            + readyAt);
    this.readyAt = readyAt;
  }

  /** The earliest instant at which a verification would measure only post-remediation data. */
  public Instant readyAt() {
    return readyAt;
  }
}
