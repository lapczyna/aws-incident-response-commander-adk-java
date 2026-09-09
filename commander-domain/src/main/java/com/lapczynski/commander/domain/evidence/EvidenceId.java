package com.lapczynski.commander.domain.evidence;

import java.util.Objects;
import java.util.UUID;

/** Identity of a single piece of collected evidence. */
public record EvidenceId(UUID value) {

  public EvidenceId {
    Objects.requireNonNull(value, "evidence id must not be null");
  }

  public static EvidenceId newId() {
    return new EvidenceId(UUID.randomUUID());
  }

  public static EvidenceId of(String value) {
    return new EvidenceId(UUID.fromString(value));
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
