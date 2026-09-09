package com.lapczynski.commander.domain.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lapczynski.commander.domain.incident.IncidentId;
import com.lapczynski.commander.domain.remediation.ActionType;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import com.lapczynski.commander.domain.remediation.ResourceRef;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The fingerprint is the mechanism that stops approval replay and stale approval, so its properties
 * are tested as properties, not as examples.
 *
 * <p>The tests that matter most are the negative ones: two different actions must never share a
 * fingerprint, and no reordering, no separator trickery and no change of incident version may
 * produce a collision.
 */
class ActionFingerprintTest {

  private static final IncidentId INCIDENT = IncidentId.of("11111111-1111-1111-1111-111111111111");
  private static final IncidentId OTHER_INCIDENT =
      IncidentId.of("22222222-2222-2222-2222-222222222222");

  private static ResourceRef service(String name) {
    return new ResourceRef(
        "arn:aws:ecs:eu-west-1:123456789012:service/demo/" + name,
        "123456789012",
        "eu-west-1",
        "demo",
        "ecs:service");
  }

  private static ProposedAction rollback(Map<String, String> args) {
    return new ProposedAction(
        ActionType.ROLLBACK_DEPLOYMENT,
        service("checkout"),
        args,
        "Roll back to task definition 41");
  }

  @Nested
  @DisplayName("stability")
  class Stability {

    @Test
    @DisplayName("the same action, incident and version always yields the same digest")
    void isDeterministic() {
      ProposedAction action = rollback(Map.of("taskDefinition", "41"));

      ActionFingerprint first = ActionFingerprint.of(action, INCIDENT, 7L);
      ActionFingerprint second = ActionFingerprint.of(action, INCIDENT, 7L);

      assertThat(first).isEqualTo(second);
      assertThat(first.matches(second)).isTrue();
    }

    @Test
    @DisplayName("argument insertion order does not affect the digest")
    void isInsertionOrderIndependent() {
      Map<String, String> forward = new LinkedHashMap<>();
      forward.put("alpha", "1");
      forward.put("beta", "2");
      forward.put("gamma", "3");

      Map<String, String> reversed = new LinkedHashMap<>();
      reversed.put("gamma", "3");
      reversed.put("beta", "2");
      reversed.put("alpha", "1");

      assertThat(ActionFingerprint.of(rollback(forward), INCIDENT, 1L))
          .as("a fingerprint that varied with map ordering would make approvals randomly stale")
          .isEqualTo(ActionFingerprint.of(rollback(reversed), INCIDENT, 1L));
    }

    @Test
    @DisplayName("the digest is 64 lowercase hex characters")
    void hasExpectedShape() {
      ActionFingerprint fp = ActionFingerprint.of(rollback(Map.of()), INCIDENT, 0L);

      assertThat(fp.hex()).hasSize(64).matches("[0-9a-f]{64}");
      assertThat(fp.abbreviated()).hasSize(12);
    }
  }

  @Nested
  @DisplayName("sensitivity - each of these differences must change the digest")
  class Sensitivity {

    @Test
    @DisplayName("a different incident version invalidates the approval")
    void versionChangesDigest() {
      ProposedAction action = rollback(Map.of("taskDefinition", "41"));

      assertThat(ActionFingerprint.of(action, INCIDENT, 7L))
          .as("this is what makes an approval go stale when the incident moves on")
          .isNotEqualTo(ActionFingerprint.of(action, INCIDENT, 8L));
    }

    @Test
    @DisplayName("the same action against a different incident is a different fingerprint")
    void incidentChangesDigest() {
      ProposedAction action = rollback(Map.of("taskDefinition", "41"));

      assertThat(ActionFingerprint.of(action, INCIDENT, 1L))
          .as("otherwise an approval could be replayed onto an unrelated incident")
          .isNotEqualTo(ActionFingerprint.of(action, OTHER_INCIDENT, 1L));
    }

    @Test
    @DisplayName("a different argument value changes the digest")
    void argumentValueChangesDigest() {
      assertThat(ActionFingerprint.of(rollback(Map.of("taskDefinition", "41")), INCIDENT, 1L))
          .isNotEqualTo(
              ActionFingerprint.of(rollback(Map.of("taskDefinition", "42")), INCIDENT, 1L));
    }

    @Test
    @DisplayName("a different target resource changes the digest")
    void targetChangesDigest() {
      ProposedAction checkout =
          new ProposedAction(
              ActionType.RESTART_ECS_TASK, service("checkout"), Map.of(), "Restart checkout");
      ProposedAction payments =
          new ProposedAction(
              ActionType.RESTART_ECS_TASK, service("payments"), Map.of(), "Restart payments");

      assertThat(ActionFingerprint.of(checkout, INCIDENT, 1L))
          .as("approving a restart of checkout must never authorise restarting payments")
          .isNotEqualTo(ActionFingerprint.of(payments, INCIDENT, 1L));
    }

    @Test
    @DisplayName("a different action type against the same target changes the digest")
    void actionTypeChangesDigest() {
      ProposedAction restart =
          new ProposedAction(ActionType.RESTART_ECS_TASK, service("checkout"), Map.of(), "Restart");
      ProposedAction rollback =
          new ProposedAction(
              ActionType.ROLLBACK_DEPLOYMENT, service("checkout"), Map.of(), "Roll back");

      assertThat(ActionFingerprint.of(restart, INCIDENT, 1L))
          .isNotEqualTo(ActionFingerprint.of(rollback, INCIDENT, 1L));
    }
  }

  @Nested
  @DisplayName("collision resistance against crafted arguments")
  class CollisionResistance {

    @Test
    @DisplayName("arguments cannot be crafted to impersonate a field boundary")
    void separatorInjectionDoesNotCollide() {
      // If fields were joined with an ordinary character such as ':' or ',', these two distinct
      // actions could canonicalise to the same string. Control-character separators prevent it.
      ProposedAction split = rollback(Map.of("taskDefinition", "41", "extra", "value"));
      ProposedAction merged = rollback(Map.of("taskDefinition", "41,extra,value"));

      assertThat(ActionFingerprint.of(split, INCIDENT, 1L))
          .as("a value containing a separator must not be able to forge extra arguments")
          .isNotEqualTo(ActionFingerprint.of(merged, INCIDENT, 1L));
    }

    @Test
    @DisplayName("a key/value swap does not collide")
    void keyValueSwapDoesNotCollide() {
      ProposedAction ab = rollback(Map.of("a", "b"));
      ProposedAction ba = rollback(Map.of("b", "a"));

      assertThat(ActionFingerprint.of(ab, INCIDENT, 1L))
          .isNotEqualTo(ActionFingerprint.of(ba, INCIDENT, 1L));
    }

    @Test
    @DisplayName("many distinct actions produce entirely distinct digests")
    void noCollisionsAcrossManyVariants() {
      Set<String> digests =
          IntStream.range(0, 500)
              .mapToObj(
                  i -> ActionFingerprint.of(rollback(Map.of("n", String.valueOf(i))), INCIDENT, i))
              .map(ActionFingerprint::hex)
              .collect(Collectors.toSet());

      assertThat(digests).as("500 distinct actions must yield 500 distinct digests").hasSize(500);
    }
  }

  @Nested
  @DisplayName("validation")
  class Validation {

    @Test
    @DisplayName("rejects a digest of the wrong length")
    void rejectsWrongLength() {
      assertThatThrownBy(() -> ActionFingerprint.fromHex("abc123"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("64");
    }

    @Test
    @DisplayName("rejects uppercase or non-hex characters")
    void rejectsNonHex() {
      String uppercase = "A".repeat(64);
      assertThatThrownBy(() -> ActionFingerprint.fromHex(uppercase))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("lowercase hexadecimal");
    }

    @Test
    @DisplayName("rejects a negative incident version")
    void rejectsNegativeVersion() {
      assertThatThrownBy(() -> ActionFingerprint.of(rollback(Map.of()), INCIDENT, -1L))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("matching against null is false, not an exception")
    void matchesNullSafely() {
      assertThat(ActionFingerprint.of(rollback(Map.of()), INCIDENT, 1L).matches(null)).isFalse();
    }

    @Test
    @DisplayName("a fingerprint from a random UUID incident still round-trips through hex")
    void roundTripsThroughHex() {
      ActionFingerprint original =
          ActionFingerprint.of(rollback(Map.of()), new IncidentId(UUID.randomUUID()), 3L);

      assertThat(ActionFingerprint.fromHex(original.hex())).isEqualTo(original);
    }
  }
}
