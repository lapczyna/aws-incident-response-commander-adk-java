package com.lapczynski.commander.aws;

import com.lapczynski.commander.application.signal.LogsPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;

/**
 * Reads application logs from CloudWatch Logs.
 *
 * <p>Uses {@code FilterLogEvents} rather than Logs Insights. Insights is more expressive, but it is
 * an asynchronous three-call protocol (start, poll, fetch) whose latency is unpredictable and whose
 * polling loop would sit inside an investigation's deadline. For "find recent lines matching a
 * pattern" the synchronous call is a better fit, and predictable latency is worth more here than
 * query power.
 *
 * <p>Everything returned is <strong>untrusted</strong>: log text is whatever a caller managed to
 * get written. Bounding happens here; sanitising and delimiting happen at the tool boundary.
 */
public class CloudWatchLogsAdapter implements LogsPort {

  private static final Logger log = LoggerFactory.getLogger(CloudWatchLogsAdapter.class);

  /**
   * Hard ceiling on events fetched from AWS, independent of the caller's limit.
   *
   * <p>A pattern matching everything in a busy log group would otherwise page through an enormous
   * result set to satisfy a request for twenty lines. This bounds the cost of the call itself, not
   * just the size of the answer.
   */
  private static final int MAX_EVENTS_SCANNED = 500;

  private final CloudWatchLogsClient logs;
  private final String logGroupPrefix;

  public CloudWatchLogsAdapter(CloudWatchLogsClient logs, String logGroupPrefix) {
    this.logs = logs;
    this.logGroupPrefix = logGroupPrefix;
  }

  @Override
  public LogQueryResult query(LogQuery query) {
    // Compiled before the call so a bad pattern is a bad request rather than a wasted round trip.
    Pattern pattern = compile(query.pattern());

    FilterLogEventsRequest request =
        FilterLogEventsRequest.builder()
            .logGroupName(logGroupPrefix + query.serviceName())
            .startTime(query.window().from().toEpochMilli())
            .endTime(query.window().to().toEpochMilli())
            .limit(Math.min(MAX_EVENTS_SCANNED, query.limit() * 5))
            .build();

    FilterLogEventsResponse response;
    try {
      response = logs.filterLogEvents(request);
    } catch (Exception e) {
      log.warn(
          "CloudWatch Logs query failed: service={} error={}", query.serviceName(), e.toString());
      throw AwsFailures.translate("CloudWatch Logs", e);
    }

    // CloudWatch's own filter syntax is not a regular expression, so the pattern is applied here.
    // Fetching a bounded superset and filtering locally keeps the port's contract - a regex - the
    // same in both AWS and simulator modes, which is what lets one set of prompts work for both.
    List<LogEntry> matched = new ArrayList<>();
    long totalMatched = 0;

    for (FilteredLogEvent event : response.events()) {
      String message = event.message();
      if (message == null || !pattern.matcher(message).find()) {
        continue;
      }
      totalMatched++;
      if (matched.size() < query.limit()) {
        matched.add(
            new LogEntry(
                Instant.ofEpochMilli(event.timestamp()),
                inferLevel(message),
                message,
                Map.of("logStream", String.valueOf(event.logStreamName()))));
      }
    }

    matched.sort(Comparator.comparing(LogEntry::timestamp).reversed());

    // Truncation is reported when either the caller's limit or the scan ceiling was reached: a
    // conclusion drawn from a partial view must be able to say it was partial, and the scan
    // ceiling makes the view partial just as effectively as the limit does.
    boolean truncated =
        totalMatched > query.limit() || response.events().size() >= MAX_EVENTS_SCANNED;

    return new LogQueryResult(matched, truncated, totalMatched);
  }

  /**
   * Guesses a level from the message text.
   *
   * <p>CloudWatch has no notion of a log level; it is a convention inside the message. The guess is
   * conservative and defaults to INFO, because over-reporting ERROR would distort an investigation
   * more than under-reporting it.
   */
  private static String inferLevel(String message) {
    String head = message.length() > 200 ? message.substring(0, 200) : message;
    if (head.contains("ERROR") || head.contains("SEVERE")) {
      return "ERROR";
    }
    if (head.contains("WARN")) {
      return "WARN";
    }
    if (head.contains("DEBUG") || head.contains("TRACE")) {
      return "DEBUG";
    }
    return "INFO";
  }

  private static Pattern compile(String pattern) {
    try {
      return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException("invalid log search pattern: " + pattern, e);
    }
  }
}
