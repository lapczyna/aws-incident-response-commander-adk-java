package com.lapczynski.commander.adk.workflow;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.lapczynski.commander.application.port.EvidenceRepository;
import com.lapczynski.commander.domain.evidence.Confidence;
import com.lapczynski.commander.domain.evidence.Evidence;
import com.lapczynski.commander.domain.evidence.EvidenceGap;
import com.lapczynski.commander.domain.evidence.EvidenceId;
import com.lapczynski.commander.domain.evidence.EvidenceSource;
import com.lapczynski.commander.domain.incident.IncidentId;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns what the investigation read into rows a report can cite.
 *
 * <p>Without this, evidence exists only inside agent session state: summarisable in a report but
 * never checkable against it, so "every conclusion references stored evidence" would be a claim
 * rather than a property. {@code IncidentReportRenderer} scans the narrative for citations that do
 * not resolve to a stored observation and prints them as a warning; that check has nothing to work
 * with unless the observations were written down.
 *
 * <p>The source of truth is the <strong>tool response</strong>, not the tool's own arguments and
 * not the model's account of what it found. What is stored is exactly the payload the model was
 * given, which is the only version of events that can be compared against a conclusion drawn from
 * it.
 *
 * <p>Failures are stored too, and stored separately. "The logs showed nothing" and "the logs could
 * not be read" support entirely different conclusions, and collapsing them into one absent row
 * would lose the distinction at precisely the moment it matters.
 */
public class EvidenceCapture {

  private static final Logger log = LoggerFactory.getLogger(EvidenceCapture.class);

  /**
   * How much of a tool response is kept.
   *
   * <p>Below {@link Evidence#MAX_CONTENT_LENGTH}, so that a large response is truncated here rather
   * than rejected by the domain. Losing the tail of an oversized log dump costs a little context;
   * throwing away the whole observation because it was long costs the citation.
   */
  private static final int MAX_CONTENT = 8_000;

  /**
   * Which tool read which source.
   *
   * <p>Explicit, because the source decides whether the content is untrusted, and deciding that by
   * inspecting the content is the game an attacker wins.
   */
  private static final Map<String, EvidenceSource> SOURCES =
      Map.of(
          "queryServiceMetric", EvidenceSource.CLOUDWATCH_METRICS,
          "queryServiceLogs", EvidenceSource.CLOUDWATCH_LOGS,
          "inspectEcsState", EvidenceSource.ECS_STATE,
          "inspectAlarms", EvidenceSource.CLOUDWATCH_ALARMS,
          "recentChanges", EvidenceSource.CLOUDTRAIL_CHANGES,
          "deploymentHistory", EvidenceSource.DEPLOYMENT_HISTORY);

  private final EvidenceRepository evidence;
  private final Clock clock;

  public EvidenceCapture(EvidenceRepository evidence, Clock clock) {
    this.evidence = evidence;
    this.clock = clock;
  }

  /** Stores every observation and every failure to observe found in this run's events. */
  public void persist(IncidentId incidentId, List<Event> events) {
    for (Event event : events) {
      for (FunctionResponse response : responses(event)) {
        String tool = response.name().orElse("");
        EvidenceSource source = SOURCES.get(tool);
        if (source == null) {
          // Not an evidence-gathering tool. executeRemediation is the obvious one: what it did is
          // recorded by the execution journal, and storing it as an observation would let a report
          // cite the action as proof that the action worked.
          continue;
        }

        Map<String, Object> payload = response.response().orElse(Map.of());
        String collectedBy = event.author() == null ? "investigator" : event.author();

        try {
          if ("unavailable".equals(String.valueOf(payload.get("status")))) {
            evidence.saveGap(gap(incidentId, source, collectedBy, payload));
          } else {
            evidence.save(observation(incidentId, source, collectedBy, tool, payload));
          }
        } catch (RuntimeException e) {
          // Never fatal. An investigation that completed and could not write one of its rows is
          // worth far more than one aborted at the last step, and the gap is visible in the report
          // as a missing citation rather than hidden.
          log.warn(
              "Could not store evidence: incidentId={} tool={} source={}",
              incidentId,
              tool,
              source,
              e);
        }
      }
    }
  }

  /**
   * The evidence an approval request should cite.
   *
   * <p>Everything the investigation observed, which is the honest answer: the approver is being
   * asked to authorise an action justified by the investigation as a whole, and a narrower list
   * would be the model's opinion about which of its observations mattered.
   */
  public List<EvidenceId> citedEvidence(IncidentId incidentId) {
    return evidence.findByIncident(incidentId).stream().map(Evidence::id).toList();
  }

  private Evidence observation(
      IncidentId incidentId,
      EvidenceSource source,
      String collectedBy,
      String tool,
      Map<String, Object> payload) {

    String content = truncate(String.valueOf(payload));
    return new Evidence(
        EvidenceId.newId(),
        incidentId,
        source,
        collectedBy,
        summarise(tool, payload),
        content,
        // Evidence is what a source said, and a source saying it is not in doubt. Confidence
        // belongs to the hypothesis built on top of it, which is where it is actually assessed.
        Confidence.CERTAIN,
        clock.instant(),
        Optional.empty());
  }

  private EvidenceGap gap(
      IncidentId incidentId,
      EvidenceSource source,
      String attemptedBy,
      Map<String, Object> payload) {

    return new EvidenceGap(
        incidentId,
        source,
        attemptedBy,
        reason(String.valueOf(payload.get("reason"))),
        truncate(String.valueOf(payload.getOrDefault("detail", "no detail reported"))),
        clock.instant());
  }

  /**
   * Maps the tool's reason onto the domain's.
   *
   * <p>An unrecognised reason becomes {@code UPSTREAM_ERROR} rather than being dropped. A gap
   * recorded with an imprecise reason is still a recorded gap; a gap not recorded because its
   * reason did not parse is an investigation that looks more complete than it was.
   */
  private static EvidenceGap.Reason reason(String raw) {
    try {
      return EvidenceGap.Reason.valueOf(raw);
    } catch (IllegalArgumentException e) {
      return EvidenceGap.Reason.UPSTREAM_ERROR;
    }
  }

  /**
   * A one-line description for the evidence table, derived from the payload rather than invented.
   */
  private static String summarise(String tool, Map<String, Object> payload) {
    Object kind = payload.getOrDefault("kind", tool);

    if (payload.containsKey("metricName")) {
      return "Metric %s".formatted(payload.get("metricName"));
    }
    if (payload.containsKey("matchCount")) {
      return "Log search: %s matching entries".formatted(payload.get("matchCount"));
    }
    if (payload.containsKey("desiredCount")) {
      return "ECS state: %s of %s tasks running"
          .formatted(payload.get("runningCount"), payload.get("desiredCount"));
    }
    return "Observation from %s".formatted(kind);
  }

  private static String truncate(String value) {
    return value.length() <= MAX_CONTENT
        ? value
        : value.substring(0, MAX_CONTENT) + "… [truncated at " + MAX_CONTENT + " characters]";
  }

  private static List<FunctionResponse> responses(Event event) {
    return event
        .content()
        .flatMap(Content::parts)
        .map(parts -> parts.stream().map(Part::functionResponse).flatMap(Optional::stream).toList())
        .orElse(List.of());
  }
}
