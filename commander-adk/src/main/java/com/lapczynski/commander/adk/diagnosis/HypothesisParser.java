package com.lapczynski.commander.adk.diagnosis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lapczynski.commander.domain.evidence.Confidence;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a model's hypothesis text into a validated structure.
 *
 * <p>This is where "every conclusion references stored evidence" stops being a prompt instruction
 * and becomes a checked property. A model asked to cite its evidence will usually comply and will
 * sometimes not — inventing a plausible reference, citing a source it never read, or quietly
 * dropping the citations when the answer feels obvious. None of those are detectable by reading the
 * prose.
 *
 * <p>So the parser validates rather than trusts:
 *
 * <ul>
 *   <li>Confidence is clamped into [0,1]. A model that returns 95 meaning 95% does not become
 *       impossibly certain.
 *   <li>Cited sources are checked against the set that actually reported. A citation of a source
 *       that never ran is dropped and recorded, not accepted.
 *   <li><strong>Confidence is capped when citations are missing or invalid.</strong> This is the
 *       important one: an uncited hypothesis cannot be highly confident, whatever the model says,
 *       because the policy engine reads that number when deciding whether an action may proceed.
 * </ul>
 *
 * <p>Parsing failures are never fatal. A malformed response yields a low-confidence hypothesis
 * carrying the raw text, because an investigation that produced an unparseable answer has still
 * produced something a human should see.
 */
public final class HypothesisParser {

  private static final Logger log = LoggerFactory.getLogger(HypothesisParser.class);

  /**
   * Ceiling applied when a hypothesis cites nothing valid.
   *
   * <p>Chosen to sit below the policy engine's default minimum confidence, so an uncited hypothesis
   * cannot authorise an action even if the model claimed certainty. The two numbers are related on
   * purpose and a test asserts the relationship holds.
   */
  public static final Confidence UNCITED_CONFIDENCE_CAP = new Confidence(0.4);

  /** Ceiling applied when some citations were valid and others were not. */
  public static final Confidence PARTIALLY_CITED_CONFIDENCE_CAP = new Confidence(0.65);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HypothesisParser() {}

  /**
   * The parsed result.
   *
   * @param confidence already adjusted for citation quality, so callers need not remember to apply
   *     the cap themselves
   * @param citedSources sources that both appear in the response and actually reported
   * @param rejectedCitations citations naming a source that did not report. Retained rather than
   *     discarded: a model citing evidence it never saw is a signal worth surfacing in the report.
   * @param wellFormed whether the response parsed as the requested structure
   */
  public record ParsedHypothesis(
      String statement,
      String reasoning,
      Confidence confidence,
      List<String> citedSources,
      List<String> rejectedCitations,
      boolean wellFormed) {

    public ParsedHypothesis {
      citedSources = List.copyOf(citedSources);
      rejectedCitations = List.copyOf(rejectedCitations);
    }

    /** Whether this hypothesis rests on at least one source that genuinely reported. */
    public boolean hasValidCitations() {
      return !citedSources.isEmpty();
    }

    /** Whether the model cited anything that did not exist. */
    public boolean citedMissingEvidence() {
      return !rejectedCitations.isEmpty();
    }
  }

  /**
   * Parses a hypothesis response.
   *
   * @param raw the model's output
   * @param availableSources the evidence keys that actually produced findings this run. Citations
   *     outside this set are rejected.
   */
  public static ParsedHypothesis parse(String raw, Set<String> availableSources) {
    if (raw == null || raw.isBlank()) {
      return malformed("", availableSources);
    }

    Optional<JsonNode> json = extractJson(raw);
    if (json.isEmpty()) {
      log.warn("Hypothesis response was not parseable as JSON; treating as low-confidence prose");
      return malformed(raw, availableSources);
    }

    JsonNode node = json.get();
    String statement = text(node, "statement", raw);
    String reasoning = text(node, "reasoning", "");

    List<String> cited = new ArrayList<>();
    List<String> rejected = new ArrayList<>();
    JsonNode evidence = node.get("supportingEvidence");
    if (evidence != null && evidence.isArray()) {
      Set<String> seen = new LinkedHashSet<>();
      evidence.forEach(
          element -> {
            String value = element.asText("").trim();
            if (value.isEmpty() || !seen.add(value)) {
              return;
            }
            if (availableSources.contains(value)) {
              cited.add(value);
            } else {
              // Not silently dropped. A model citing a source that never ran is a finding.
              rejected.add(value);
            }
          });
    }

    if (!rejected.isEmpty()) {
      log.warn(
          "Hypothesis cited sources that did not report: cited={} available={}",
          rejected,
          availableSources);
    }

    Confidence declared = readConfidence(node);
    Confidence adjusted = applyCitationCap(declared, cited, rejected);

    return new ParsedHypothesis(statement, reasoning, adjusted, cited, rejected, true);
  }

  /**
   * Caps confidence according to how well the hypothesis is supported.
   *
   * <p>Only ever lowers it. A model cannot argue its way to more certainty than its citations
   * justify, which matters because the policy engine reads this number.
   */
  private static Confidence applyCitationCap(
      Confidence declared, List<String> cited, List<String> rejected) {
    if (cited.isEmpty()) {
      return min(declared, UNCITED_CONFIDENCE_CAP);
    }
    if (!rejected.isEmpty()) {
      return min(declared, PARTIALLY_CITED_CONFIDENCE_CAP);
    }
    return declared;
  }

  private static Confidence min(Confidence a, Confidence b) {
    return a.value() <= b.value() ? a : b;
  }

  /**
   * Reads the declared confidence, tolerating the forms models actually emit.
   *
   * <p>Accepts a fraction, a percentage, or the words low/medium/high. Clamping rather than
   * rejecting keeps a usable hypothesis from being thrown away over formatting, and clamping is
   * safe because it can only reduce an over-claim.
   */
  private static Confidence readConfidence(JsonNode node) {
    JsonNode value = node.get("confidence");
    if (value == null || value.isNull()) {
      return UNCITED_CONFIDENCE_CAP;
    }

    if (value.isNumber()) {
      double raw = value.asDouble();
      // A model asked for 0-1 that answers 85 means 85%, not impossible certainty.
      return Confidence.clamped(raw > 1.0 ? raw / 100.0 : raw);
    }

    return switch (value.asText("").trim().toLowerCase(java.util.Locale.ROOT)) {
      case "high" -> new Confidence(0.85);
      case "medium", "moderate" -> new Confidence(0.6);
      case "low" -> new Confidence(0.3);
      default -> UNCITED_CONFIDENCE_CAP;
    };
  }

  /**
   * Finds the JSON object in a response.
   *
   * <p>Models frequently wrap JSON in prose or a fenced code block even when told not to.
   * Extracting the outermost braces is more robust than insisting on a clean response, and failing
   * over formatting would discard a perfectly good diagnosis.
   */
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

  private static String text(JsonNode node, String field, String fallback) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? fallback : value.asText(fallback);
  }

  private static ParsedHypothesis malformed(String raw, Set<String> availableSources) {
    return new ParsedHypothesis(
        raw.isBlank() ? "No hypothesis was produced." : raw,
        "The model's response could not be parsed as a structured hypothesis, so its citations "
            + "could not be verified. Treat this as unsupported.",
        UNCITED_CONFIDENCE_CAP,
        List.of(),
        List.of(),
        false);
  }
}
