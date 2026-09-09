package com.lapczynski.commander.domain.remediation;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A single action a remediation proposal would perform: what, against which resource, with which
 * arguments.
 *
 * <p>The arguments map is stored in a {@link TreeMap}, giving it a deterministic iteration order
 * regardless of how it was built. That is not tidiness — the canonical form of this map feeds the
 * approval fingerprint (ADR-0007), and a fingerprint that varied with map ordering would make
 * approvals randomly stale and, worse, could let two different argument sets produce the same
 * digest depending on insertion order.
 *
 * @param arguments action parameters, restricted to scalar values. Nested structures are rejected
 *     so the canonical form stays unambiguous and a human reviewing the approval can actually read
 *     what they are approving.
 */
public record ProposedAction(
    ActionType type, ResourceRef target, Map<String, String> arguments, String humanDescription) {

  public ProposedAction {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(target, "target must not be null");
    Objects.requireNonNull(arguments, "arguments must not be null");
    Objects.requireNonNull(humanDescription, "humanDescription must not be null");
    if (humanDescription.isBlank()) {
      throw new IllegalArgumentException(
          "humanDescription must not be blank; an approver has to be able to read what "
              + "they are approving");
    }
    arguments.forEach(
        (key, value) -> {
          Objects.requireNonNull(key, "argument key must not be null");
          Objects.requireNonNull(value, "argument value must not be null for key " + key);
          if (key.isBlank()) {
            throw new IllegalArgumentException("argument key must not be blank");
          }
        });
    // Sorted copy: this is what makes the canonical form stable.
    arguments = Map.copyOf(new TreeMap<>(arguments));
  }

  /**
   * ASCII unit separator (0x1F), delimiting fields within the canonical form.
   *
   * <p>Declared as a named constant rather than written as a literal control character in the
   * string. An invisible byte in source feeding a security-critical digest is a trap: an editor, a
   * copy-paste or a re-encoding could silently change it, and every previously issued approval
   * fingerprint would stop matching with no visible diff to explain why.
   */
  private static final char FIELD_SEPARATOR = 0x1F;

  /** ASCII record separator (0x1E), delimiting argument entries. */
  private static final char ENTRY_SEPARATOR = 0x1E;

  /**
   * A stable, unambiguous string form of this action, used as fingerprint input.
   *
   * <p>Separators are control characters that cannot occur in an ARN, an account id or a sane
   * argument value. Joining with commas or colons instead would let a value containing that
   * character impersonate a field boundary, so two different actions could canonicalise identically
   * and one approval would authorise the other.
   */
  public String canonicalForm() {
    StringBuilder sb = new StringBuilder(128);
    sb.append(type.name())
        .append(FIELD_SEPARATOR)
        .append(target.arn())
        .append(FIELD_SEPARATOR)
        .append(target.accountId())
        .append(FIELD_SEPARATOR)
        .append(target.region())
        .append(FIELD_SEPARATOR)
        .append(target.environment());
    new TreeMap<>(arguments)
        .forEach(
            (key, value) ->
                sb.append(ENTRY_SEPARATOR).append(key).append(FIELD_SEPARATOR).append(value));
    return sb.toString();
  }

  public boolean isStateChanging() {
    return type.isStateChanging();
  }
}
