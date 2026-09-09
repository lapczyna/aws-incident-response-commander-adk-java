package com.lapczynski.commander.domain.remediation;

import java.util.Objects;

/**
 * A concrete AWS resource an action would target.
 *
 * <p>Account, region and environment are carried explicitly rather than being parsed back out of
 * the ARN at check time. The policy engine compares them field by field, so a malformed or
 * deliberately misleading ARN cannot smuggle an action into the wrong account.
 */
public record ResourceRef(
    String arn, String accountId, String region, String environment, String resourceType) {

  public ResourceRef {
    Objects.requireNonNull(arn, "arn must not be null");
    Objects.requireNonNull(accountId, "accountId must not be null");
    Objects.requireNonNull(region, "region must not be null");
    Objects.requireNonNull(environment, "environment must not be null");
    Objects.requireNonNull(resourceType, "resourceType must not be null");
    if (!arn.startsWith("arn:")) {
      throw new IllegalArgumentException("not an ARN: " + arn);
    }
    if (arn.isBlank() || accountId.isBlank() || region.isBlank()) {
      throw new IllegalArgumentException("arn, accountId and region must all be present");
    }
  }

  @Override
  public String toString() {
    return arn;
  }
}
