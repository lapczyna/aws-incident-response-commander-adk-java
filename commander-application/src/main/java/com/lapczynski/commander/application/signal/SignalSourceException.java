package com.lapczynski.commander.application.signal;

import com.lapczynski.commander.domain.evidence.EvidenceGap;
import java.util.Objects;

/**
 * Thrown when a signal source cannot answer.
 *
 * <p>Carries an {@link EvidenceGap.Reason} rather than a bare message so a failed investigator can
 * be turned into a recorded gap instead of aborting the whole investigation. A specialist that
 * cannot read CloudWatch should degrade the conclusion, not destroy it.
 */
public class SignalSourceException extends RuntimeException {

  private final EvidenceGap.Reason reason;

  public SignalSourceException(EvidenceGap.Reason reason, String message) {
    super(message);
    this.reason = Objects.requireNonNull(reason, "reason must not be null");
  }

  public SignalSourceException(EvidenceGap.Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = Objects.requireNonNull(reason, "reason must not be null");
  }

  public EvidenceGap.Reason reason() {
    return reason;
  }
}
