package com.lapczynski.commander.adk.diagnosis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lapczynski.commander.domain.remediation.ActionType;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import com.lapczynski.commander.domain.remediation.ResourceRef;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a planner's proposal text into a typed {@link ProposedAction}, or nothing.
 *
 * <p>The important word is <em>or nothing</em>. Every failure mode here — unparseable output, an
 * unrecognised action type, a malformed ARN, a missing target — returns {@link Optional#empty()},
 * and the policy gate treats an absent proposal as a stop. Parsing is a place where being
 * accommodating is dangerous: guessing what a model meant by "restart the thing" would be the
 * single easiest way to execute something nobody proposed.
 *
 * <p>{@link ActionType} being an enum does most of the work. A model cannot invent an action,
 * because anything outside the closed set fails to parse before it reaches a policy rule. That is
 * the cheapest defence against excessive agency in the system, and it operates before any
 * configuration is consulted.
 */
public final class RemediationProposalParser {

  private static final Logger log = LoggerFactory.getLogger(RemediationProposalParser.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Cap on argument count, so a proposal cannot smuggle in an unbounded map. */
  private static final int MAX_ARGUMENTS = 10;

  private RemediationProposalParser() {}

  /**
   * Parses a proposal.
   *
   * @return the action, or empty if anything at all was wrong with it
   */
  public static Optional<ProposedAction> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }

    Optional<JsonNode> json = extractJson(raw);
    if (json.isEmpty()) {
      log.warn("Remediation proposal was not parseable as JSON; no action will be proposed");
      return Optional.empty();
    }

    JsonNode node = json.get();

    // "No action needed" is a legitimate and frequently correct outcome, expressed by omitting the
    // action rather than by inventing a harmless one to fill the field.
    String actionTypeRaw = text(node, "actionType");
    if (actionTypeRaw.isBlank() || "NONE".equalsIgnoreCase(actionTypeRaw)) {
      log.info("Planner proposed no action");
      return Optional.empty();
    }

    ActionType actionType;
    try {
      actionType = ActionType.valueOf(actionTypeRaw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      // The closed enum doing its job. Anything invented is refused here, before policy runs.
      log.warn(
          "Planner proposed an unrecognised action type '{}'; refusing to interpret it",
          actionTypeRaw);
      return Optional.empty();
    }

    JsonNode target = node.get("target");
    if (target == null || !target.isObject()) {
      log.warn("Proposal for {} named no target resource; refusing", actionType);
      return Optional.empty();
    }

    String arn = text(target, "arn");
    String accountId = text(target, "accountId");
    String region = text(target, "region");
    String environment = text(target, "environment");
    String resourceType = text(target, "resourceType");

    if (arn.isBlank() || accountId.isBlank() || region.isBlank() || environment.isBlank()) {
      log.warn("Proposal for {} had an incomplete target; refusing", actionType);
      return Optional.empty();
    }

    Map<String, String> arguments = new LinkedHashMap<>();
    JsonNode args = node.get("arguments");
    if (args != null && args.isObject()) {
      args.fields()
          .forEachRemaining(
              entry -> {
                if (arguments.size() >= MAX_ARGUMENTS) {
                  return;
                }
                JsonNode value = entry.getValue();
                // Scalars only. A nested structure has no unambiguous canonical form, and the
                // canonical form is what the approval fingerprint is computed over.
                if (value != null && value.isValueNode()) {
                  arguments.put(entry.getKey(), value.asText());
                }
              });
    }

    String description = text(node, "humanDescription");
    if (description.isBlank()) {
      description = "%s against %s".formatted(actionType, arn);
    }

    try {
      ResourceRef resource =
          new ResourceRef(
              arn,
              accountId,
              region,
              environment,
              resourceType.isBlank() ? "unknown" : resourceType);
      return Optional.of(new ProposedAction(actionType, resource, arguments, description));

    } catch (IllegalArgumentException e) {
      // ResourceRef validates the ARN shape. A malformed one is refused rather than repaired:
      // repairing it would mean guessing which resource was meant.
      log.warn("Proposal for {} had an invalid target: {}", actionType, e.getMessage());
      return Optional.empty();
    }
  }

  private static Optional<JsonNode> extractJson(String raw) {
    int start = raw.indexOf('{');
    int end = raw.lastIndexOf('}');
    if (start < 0 || end <= start) {
      return Optional.empty();
    }
    try {
      return Optional.of(MAPPER.readTree(raw.substring(start, end + 1)));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? "" : value.asText("").trim();
  }
}
