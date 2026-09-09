package com.lapczynski.commander.domain.approval;

import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * A SHA-256 digest binding an approval to one exact action, against one incident, at one version.
 *
 * <p>This is the mechanism behind ADR-0007. An approval that referenced only an approval id would
 * authorise "whatever the system decides to do next", which is precisely the confused-deputy
 * problem: the model authors the proposal and would then get to decide what the human's approval
 * covers.
 *
 * <p>Including the incident's optimistic-locking version is what makes approvals go stale. If
 * anything material about the incident changes between proposal and execution, the recomputed
 * fingerprint differs and the approval no longer matches. A human approved a rollback given the
 * evidence they saw; they did not approve it given evidence that arrived afterwards.
 *
 * <p>Comparison uses {@link MessageDigest#isEqual} rather than {@link String#equals}, so matching
 * takes constant time and does not leak, through timing, how much of a forged fingerprint was
 * correct.
 */
public record ActionFingerprint(String hex) {

  private static final char FIELD_SEPARATOR = 0x1F;
  private static final int SHA256_HEX_LENGTH = 64;

  public ActionFingerprint {
    Objects.requireNonNull(hex, "fingerprint must not be null");
    if (hex.length() != SHA256_HEX_LENGTH) {
      throw new IllegalArgumentException(
          "expected a %d-character SHA-256 hex digest, got %d characters"
              .formatted(SHA256_HEX_LENGTH, hex.length()));
    }
    if (!hex.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
      throw new IllegalArgumentException("fingerprint must be lowercase hexadecimal");
    }
  }

  /**
   * Computes the fingerprint for an action against a specific incident version.
   *
   * <p>Every input is part of the identity of what was approved:
   *
   * <ul>
   *   <li>the action type, target resource and arguments, via {@link
   *       ProposedAction#canonicalForm()}
   *   <li>the incident id, so an approval cannot be replayed onto a different incident
   *   <li>the incident version, so any material change invalidates it
   * </ul>
   */
  public static ActionFingerprint of(
      ProposedAction action, IncidentId incidentId, long incidentVersion) {
    Objects.requireNonNull(action, "action must not be null");
    Objects.requireNonNull(incidentId, "incidentId must not be null");
    if (incidentVersion < 0) {
      throw new IllegalArgumentException("incidentVersion must not be negative");
    }

    String canonical =
        action.canonicalForm()
            + FIELD_SEPARATOR
            + incidentId.value()
            + FIELD_SEPARATOR
            + incidentVersion;

    byte[] digest = sha256(canonical.getBytes(StandardCharsets.UTF_8));
    return new ActionFingerprint(HexFormat.of().formatHex(digest));
  }

  /** Parses a stored fingerprint. */
  public static ActionFingerprint fromHex(String hex) {
    return new ActionFingerprint(hex);
  }

  /**
   * Constant-time comparison against another fingerprint.
   *
   * <p>Use this rather than {@code equals} anywhere the value being compared could have been
   * supplied by a caller.
   */
  public boolean matches(ActionFingerprint other) {
    if (other == null) {
      return false;
    }
    return MessageDigest.isEqual(
        hex.getBytes(StandardCharsets.US_ASCII), other.hex.getBytes(StandardCharsets.US_ASCII));
  }

  /** A short prefix for log lines. The full digest is not secret, but it is noisy. */
  public String abbreviated() {
    return hex.substring(0, 12);
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandated by the Java platform specification; absence means a broken JRE.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  @Override
  public String toString() {
    return abbreviated() + "...";
  }
}
