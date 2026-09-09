package com.lapczynski.commander.domain.approval;

import java.util.Objects;
import java.util.UUID;

/** Identity of an approval request. */
public record ApprovalId(UUID value) {

  public ApprovalId {
    Objects.requireNonNull(value, "approval id must not be null");
  }

  public static ApprovalId newId() {
    return new ApprovalId(UUID.randomUUID());
  }

  public static ApprovalId of(String value) {
    return new ApprovalId(UUID.fromString(value));
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
