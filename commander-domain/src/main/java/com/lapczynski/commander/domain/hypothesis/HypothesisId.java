package com.lapczynski.commander.domain.hypothesis;

import java.util.Objects;
import java.util.UUID;

/** Identity of a hypothesis, stable across its revisions. */
public record HypothesisId(UUID value) {

  public HypothesisId {
    Objects.requireNonNull(value, "hypothesis id must not be null");
  }

  public static HypothesisId newId() {
    return new HypothesisId(UUID.randomUUID());
  }

  public static HypothesisId of(String value) {
    return new HypothesisId(UUID.fromString(value));
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
