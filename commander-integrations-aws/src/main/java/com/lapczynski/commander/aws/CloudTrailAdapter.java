package com.lapczynski.commander.aws;

import com.lapczynski.commander.application.signal.ChangeHistoryPort;
import com.lapczynski.commander.application.signal.TimeWindow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.Event;
import software.amazon.awssdk.services.cloudtrail.model.LookupAttribute;
import software.amazon.awssdk.services.cloudtrail.model.LookupAttributeKey;
import software.amazon.awssdk.services.cloudtrail.model.LookupEventsRequest;
import software.amazon.awssdk.services.cloudtrail.model.LookupEventsResponse;
import software.amazon.awssdk.services.cloudtrail.model.Resource;

/**
 * Reads recent infrastructure changes from CloudTrail.
 *
 * <p>Queries by resource name rather than fetching everything and filtering. CloudTrail lookup is
 * slow and rate-limited, and an unfiltered lookup over a busy account would spend an
 * investigation's entire deadline retrieving events about unrelated services.
 *
 * <p>Output is <strong>untrusted</strong>. A deployment description, a commit message or a tag
 * value reaches this port as free text supplied by whoever made the change, which makes it an
 * excellent place to hide an instruction aimed at a model. Sanitising happens at the tool boundary.
 *
 * <p>Deliberately does <em>not</em> return the raw {@code cloudTrailEvent} JSON. It is large, it is
 * mostly irrelevant to an investigation, and it contains identity details that have no business in
 * a prompt or an incident report.
 */
public class CloudTrailAdapter implements ChangeHistoryPort {

  private static final Logger log = LoggerFactory.getLogger(CloudTrailAdapter.class);

  private final CloudTrailClient cloudTrail;

  public CloudTrailAdapter(CloudTrailClient cloudTrail) {
    this.cloudTrail = cloudTrail;
  }

  @Override
  public List<ChangeEvent> recentChanges(String serviceName, TimeWindow window, int limit) {
    int cappedLimit = Math.min(limit, ChangeHistoryPort.MAX_EVENTS);

    LookupEventsRequest request =
        LookupEventsRequest.builder()
            .lookupAttributes(
                LookupAttribute.builder()
                    .attributeKey(LookupAttributeKey.RESOURCE_NAME)
                    .attributeValue(serviceName)
                    .build())
            .startTime(window.from())
            .endTime(window.to())
            .maxResults(cappedLimit)
            .build();

    LookupEventsResponse response;
    try {
      response = cloudTrail.lookupEvents(request);
    } catch (Exception e) {
      log.warn("CloudTrail lookup failed: service={} error={}", serviceName, e.toString());
      throw AwsFailures.translate("CloudTrail", e);
    }

    // Deliberately not paginated. One page is the whole budget: an investigation wants the changes
    // immediately before an incident, and if twenty of them are not enough to explain it, the
    // twenty-first will not be either.
    List<ChangeEvent> changes = new ArrayList<>();
    for (Event event : response.events()) {
      changes.add(
          new ChangeEvent(
              event.eventId(),
              event.eventName(),
              event.eventSource(),
              event.username() == null ? "unknown" : event.username(),
              firstResourceArn(event),
              describe(event),
              event.eventTime()));
    }

    changes.sort(Comparator.comparing(ChangeEvent::occurredAt).reversed());
    return changes;
  }

  /**
   * Builds a short human-readable description.
   *
   * <p>Assembled from structured fields rather than passing through the raw event JSON, so what
   * reaches a prompt is bounded and predictable in shape even though its content is still
   * attacker-influenceable and treated as such.
   */
  private static String describe(Event event) {
    StringBuilder description = new StringBuilder();
    description.append(event.eventName());

    if (event.hasResources() && !event.resources().isEmpty()) {
      description.append(" on ");
      description.append(
          event.resources().stream()
              .map(Resource::resourceName)
              .filter(java.util.Objects::nonNull)
              .limit(3)
              .reduce((a, b) -> a + ", " + b)
              .orElse("unnamed resource"));
    }
    if (event.username() != null) {
      description.append(" by ").append(event.username());
    }
    return description.toString();
  }

  private static String firstResourceArn(Event event) {
    if (!event.hasResources() || event.resources().isEmpty()) {
      return "";
    }
    String name = event.resources().getFirst().resourceName();
    return name == null ? "" : name;
  }
}
