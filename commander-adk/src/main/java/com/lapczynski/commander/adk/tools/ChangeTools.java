package com.lapczynski.commander.adk.tools;

import com.google.adk.tools.Annotations.Schema;
import com.lapczynski.commander.application.signal.ChangeHistoryPort;
import com.lapczynski.commander.application.signal.DeploymentHistoryPort;
import com.lapczynski.commander.application.signal.SignalSourceException;
import com.lapczynski.commander.application.signal.TimeWindow;
import com.lapczynski.commander.domain.evidence.EvidenceSource;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only tools for "what changed, and when".
 *
 * <p>Separated from {@link InvestigationTools} because the change investigator is given only these.
 * Narrow tool sets are the point: a specialist that could query metrics as well would drift into
 * duplicating the metrics investigator's work, spending budget to reach the same conclusion twice.
 *
 * <p>Change events and deployment records carry user-supplied text — a commit message, a deployment
 * description — so their output is <strong>untrusted</strong> and is wrapped accordingly. A
 * deployment description is an excellent place to hide an instruction aimed at a model.
 */
public class ChangeTools {

  private static final Logger log = LoggerFactory.getLogger(ChangeTools.class);

  private static final Duration DEFAULT_LOOKBACK = Duration.ofHours(4);
  private static final Duration MAX_LOOKBACK = Duration.ofHours(12);

  private final ChangeHistoryPort changes;
  private final DeploymentHistoryPort deployments;
  private final Clock clock;

  public ChangeTools(ChangeHistoryPort changes, DeploymentHistoryPort deployments, Clock clock) {
    this.changes = changes;
    this.deployments = deployments;
    this.clock = clock;
  }

  /** Recent infrastructure changes affecting a service. */
  @Schema(
      description =
          "List infrastructure changes affecting a service, newest first, from CloudTrail. Use "
              + "this to find out whether anything changed shortly before the incident began. "
              + "Correlating a change with the moment a metric moved is usually the strongest "
              + "evidence available.")
  public Map<String, Object> recentChanges(
      @Schema(description = "The service name, for example 'checkout'.") String serviceName,
      @Schema(description = "How many minutes to look back. Maximum 720.", optional = true)
          Integer lookbackMinutes) {

    TimeWindow window = window(lookbackMinutes);

    try {
      List<ChangeHistoryPort.ChangeEvent> events = changes.recentChanges(serviceName, window, 20);

      if (events.isEmpty()) {
        return Map.of(
            "status",
            "ok",
            "changeCount",
            0,
            "note",
            "No infrastructure changes were recorded in this window. If the incident began during "
                + "it, a deployment is unlikely to be the cause.");
      }

      List<Map<String, Object>> rows = new ArrayList<>();
      for (ChangeHistoryPort.ChangeEvent event : events) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventName", event.eventName());
        row.put("occurredAt", event.occurredAt().toString());
        row.put("performedBy", event.performedBy());
        row.put("isDeployment", event.isDeployment());
        row.put(
            "detail", EvidenceSanitizer.prepare(EvidenceSource.CLOUDTRAIL_CHANGES, event.detail()));
        rows.add(row);
      }

      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("status", "ok");
      payload.put("changeCount", rows.size());
      payload.put("windowStart", window.from().toString());
      payload.put("windowEnd", window.to().toString());
      payload.put("changes", rows);
      return payload;

    } catch (SignalSourceException e) {
      return gap(EvidenceSource.CLOUDTRAIL_CHANGES, serviceName, e);
    }
  }

  /**
   * Version history, including which version is current and which was last healthy.
   *
   * <p>The last-known-good version is computed here rather than left to the model. It is the target
   * a rollback would use, and deriving it from a list is exactly the kind of step a model can get
   * subtly wrong — picking the most recent entry rather than the most recent *healthy* one that is
   * not the current one.
   */
  @Schema(
      description =
          "List the versions deployed to a service, newest first, and identify the last known "
              + "good version. Use this to establish what is running now and what a rollback "
              + "would return to.")
  public Map<String, Object> deploymentHistory(
      @Schema(description = "The service name, for example 'checkout'.") String serviceName) {

    try {
      List<DeploymentHistoryPort.DeployedVersion> history = deployments.history(serviceName, 10);

      if (history.isEmpty()) {
        return Map.of(
            "status", "ok",
            "versionCount", 0,
            "note", "No deployment history is recorded for this service.");
      }

      List<Map<String, Object>> rows = new ArrayList<>();
      for (DeploymentHistoryPort.DeployedVersion version : history) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("version", version.version());
        row.put("taskDefinition", version.taskDefinition());
        row.put("deployedAt", version.deployedAt().toString());
        row.put("deployedBy", version.deployedBy());
        row.put("current", version.current());
        row.put("healthy", version.healthy());
        rows.add(row);
      }

      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("status", "ok");
      payload.put("versionCount", rows.size());
      payload.put("versions", rows);

      deployments
          .lastKnownGood(serviceName)
          .ifPresentOrElse(
              good -> {
                payload.put("lastKnownGoodVersion", good.version());
                payload.put("lastKnownGoodTaskDefinition", good.taskDefinition());
              },
              () ->
                  payload.put(
                      "lastKnownGoodVersion",
                      "none - no previous healthy version is recorded, so a rollback has no target"));

      return payload;

    } catch (SignalSourceException e) {
      return gap(EvidenceSource.DEPLOYMENT_HISTORY, serviceName, e);
    }
  }

  private TimeWindow window(Integer lookbackMinutes) {
    Duration lookback =
        lookbackMinutes == null
            ? DEFAULT_LOOKBACK
            : Duration.ofMinutes(Math.clamp(lookbackMinutes, 1, MAX_LOOKBACK.toMinutes()));
    return TimeWindow.endingAt(clock.instant(), lookback);
  }

  private static Map<String, Object> gap(
      EvidenceSource source, String subject, SignalSourceException e) {
    log.warn(
        "Signal source unavailable: source={} subject={} reason={}", source, subject, e.reason());

    return Map.of(
        "status",
        "unavailable",
        "source",
        source.name(),
        "reason",
        e.reason().name(),
        "detail",
        EvidenceSanitizer.stripControlCharacters(e.getMessage()),
        "guidance",
        "This evidence could not be collected. State explicitly that change history is missing. "
            + "Do not conclude that nothing changed - you cannot tell.");
  }
}
