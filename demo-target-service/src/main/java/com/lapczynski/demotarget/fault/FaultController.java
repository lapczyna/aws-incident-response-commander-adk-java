package com.lapczynski.demotarget.fault;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Control surface for demo faults.
 *
 * <p>Exempt from fault injection (see {@link FaultInjectionFilter}), so these endpoints keep
 * working no matter what has been injected. Turning a fault off must never depend on the service
 * being healthy.
 */
@RestController
@RequestMapping("/admin/faults")
@Validated
public class FaultController {

  private final FaultRegistry registry;
  private final CpuPressureGenerator cpuPressure;
  private final DbPoolPressureHolder dbPressure;
  private final Clock clock;

  public FaultController(
      FaultRegistry registry,
      CpuPressureGenerator cpuPressure,
      DbPoolPressureHolder dbPressure,
      Clock clock) {
    this.registry = registry;
    this.cpuPressure = cpuPressure;
    this.dbPressure = dbPressure;
    this.clock = clock;
  }

  /** Everything currently active, with time remaining. */
  @GetMapping
  public FaultStatus status() {
    var now = clock.instant();
    List<FaultView> active =
        registry.activeFaults().values().stream()
            .map(
                fault ->
                    new FaultView(
                        fault.type().name(),
                        fault.parameters(),
                        fault.activatedAt().toString(),
                        fault.expiresAt().toString(),
                        fault.remaining(now).toSeconds(),
                        fault.activatedBy()))
            .toList();

    return new FaultStatus(
        registry.isEnabled(), active, cpuPressure.activeBurners(), dbPressure.heldConnections());
  }

  /**
   * Injects a fault.
   *
   * <p>The duration is required. There is no way to inject a fault that does not expire.
   */
  @PostMapping("/{type}")
  public ResponseEntity<FaultView> inject(
      @PathVariable String type, @Valid @RequestBody InjectRequest request) {

    FaultType faultType = parseType(type);
    ActiveFault fault =
        registry.inject(
            faultType,
            request.parameters() == null ? Map.of() : request.parameters(),
            Duration.ofSeconds(request.durationSeconds()),
            request.activatedBy() == null ? "unknown" : request.activatedBy());

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new FaultView(
                fault.type().name(),
                fault.parameters(),
                fault.activatedAt().toString(),
                fault.expiresAt().toString(),
                fault.remaining(clock.instant()).toSeconds(),
                fault.activatedBy()));
  }

  /** Clears one fault. */
  @DeleteMapping("/{type}")
  public ResponseEntity<Void> clear(@PathVariable String type) {
    boolean cleared = registry.clear(parseType(type));
    return cleared ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
  }

  /**
   * Clears everything.
   *
   * <p>Works even when injection is disabled: the way out is never gated on configuration.
   */
  @DeleteMapping
  public ResetResponse clearAll() {
    return new ResetResponse(registry.clearAll());
  }

  private static FaultType parseType(String raw) {
    try {
      return FaultType.valueOf(raw.toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
    } catch (IllegalArgumentException e) {
      throw new UnknownFaultTypeException(raw);
    }
  }

  // ------------------------------------------------------------- payloads

  /**
   * @param durationSeconds required. Two layers apply: this bound rejects an absurd request at the
   *     edge, and {@link FaultType#clampDuration} then reduces it to the ceiling for the specific
   *     type. Asking for 1800s of CPU pressure is accepted here and clamped to 5 minutes there.
   *     <p>The {@code @Valid} on the handler parameter is what makes these annotations real.
   *     Without it they are decoration that reads like a control and enforces nothing.
   */
  public record InjectRequest(
      @Min(1) @Max(1800) long durationSeconds,
      Map<String, String> parameters,
      String activatedBy) {}

  public record FaultView(
      String type,
      Map<String, String> parameters,
      String activatedAt,
      String expiresAt,
      long remainingSeconds,
      String activatedBy) {}

  public record FaultStatus(
      boolean injectionEnabled,
      List<FaultView> active,
      int cpuBurnerThreads,
      int heldDatabaseConnections) {}

  public record ResetResponse(int cleared) {}

  // ------------------------------------------------------------ errors

  static class UnknownFaultTypeException extends RuntimeException {
    UnknownFaultTypeException(String raw) {
      super(
          "unknown fault type '%s'; supported: %s"
              .formatted(raw, java.util.Arrays.toString(FaultType.values())));
    }
  }

  @ExceptionHandler(UnknownFaultTypeException.class)
  ResponseEntity<Map<String, String>> handleUnknownType(UnknownFaultTypeException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(FaultRegistry.FaultInjectionDisabledException.class)
  ResponseEntity<Map<String, String>> handleDisabled(
      FaultRegistry.FaultInjectionDisabledException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, String>> handleInvalid(IllegalArgumentException e) {
    return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
  }
}
