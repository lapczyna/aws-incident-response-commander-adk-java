package com.lapczynski.commander.application.port;

import java.util.Map;
import java.util.Objects;

/**
 * The tags the policy gate compares a proposed target against.
 *
 * <p>A type rather than a bare {@code Map<String, String>} so that it can be injected without
 * ambiguity, and so that its one rule is stated where it is used: <strong>these never come from a
 * model.</strong> A model that could assert its own target's tags could assert its way past the tag
 * requirement, which is one of the three independent layers enforcing it.
 *
 * <p>They are also not the last word. The gate evaluates against this copy; {@code
 * AwsRemediationExecutor} re-reads the tags from live AWS immediately before acting, because a copy
 * supplied at configuration time cannot know whether a resource was retagged since. The two checks
 * exist precisely because this one can be out of date.
 */
public record TargetTags(Map<String, String> values) {

  public TargetTags {
    Objects.requireNonNull(values, "values must not be null");
    values = Map.copyOf(values);
  }

  public static TargetTags of(String key, String value) {
    return new TargetTags(Map.of(key, value));
  }
}
