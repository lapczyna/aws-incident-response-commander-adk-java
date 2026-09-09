package com.lapczynski.commander.application.signal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Reads alarm state. */
public interface AlarmsPort {

  int MAX_ALARMS = 50;

  /**
   * @throws SignalSourceException if alarms cannot be read
   */
  List<AlarmState> activeAlarms(String serviceName);

  /**
   * @param state ALARM, OK or INSUFFICIENT_DATA. INSUFFICIENT_DATA is deliberately distinguishable
   *     from OK: "the alarm says nothing is wrong" and "the alarm has no data" support very
   *     different conclusions.
   */
  record AlarmState(
      String name,
      String state,
      String reason,
      String metricName,
      double threshold,
      Instant stateChangedAt) {

    public AlarmState {
      Objects.requireNonNull(name, "name must not be null");
      Objects.requireNonNull(state, "state must not be null");
      Objects.requireNonNull(reason, "reason must not be null");
      Objects.requireNonNull(metricName, "metricName must not be null");
      Objects.requireNonNull(stateChangedAt, "stateChangedAt must not be null");
    }

    public boolean isFiring() {
      return "ALARM".equals(state);
    }

    public boolean hasNoData() {
      return "INSUFFICIENT_DATA".equals(state);
    }
  }
}
