package com.lapczynski.commander.application.signal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads application logs.
 *
 * <p>Everything this port returns is <strong>untrusted</strong>. A log line contains whatever a
 * caller managed to get written, which includes text shaped like instructions to a model. Results
 * are bounded here and sanitised at the tool boundary before they reach a prompt.
 */
public interface LogsPort {

  /** Upper bound on entries returned by a single query. */
  int MAX_ENTRIES = 100;

  /** Upper bound on a single log message, in characters. */
  int MAX_MESSAGE_LENGTH = 2_000;

  /**
   * @throws SignalSourceException if the logs cannot be read
   */
  LogQueryResult query(LogQuery query);

  record LogQuery(String serviceName, String pattern, TimeWindow window, int limit) {
    public LogQuery {
      Objects.requireNonNull(serviceName, "serviceName must not be null");
      Objects.requireNonNull(pattern, "pattern must not be null");
      Objects.requireNonNull(window, "window must not be null");
      if (limit <= 0 || limit > MAX_ENTRIES) {
        throw new IllegalArgumentException("limit must be within [1, %d]".formatted(MAX_ENTRIES));
      }
    }
  }

  /**
   * @param truncated whether more entries matched than were returned. Surfaced so a conclusion
   *     drawn from a partial view can say so rather than implying it saw everything.
   */
  record LogQueryResult(List<LogEntry> entries, boolean truncated, long totalMatched) {
    public LogQueryResult {
      entries = List.copyOf(entries);
    }

    public boolean isEmpty() {
      return entries.isEmpty();
    }
  }

  record LogEntry(Instant timestamp, String level, String message, Map<String, String> fields) {
    public LogEntry {
      Objects.requireNonNull(timestamp, "timestamp must not be null");
      Objects.requireNonNull(level, "level must not be null");
      Objects.requireNonNull(message, "message must not be null");
      fields = Map.copyOf(fields);
      if (message.length() > MAX_MESSAGE_LENGTH) {
        message = message.substring(0, MAX_MESSAGE_LENGTH) + "...[truncated]";
      }
    }
  }
}
