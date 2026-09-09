package com.lapczynski.demotarget.api;

import com.lapczynski.demotarget.fault.FaultRegistry;
import com.lapczynski.demotarget.fault.FaultType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A deliberately ordinary payment API. This is the workload an incident is about.
 *
 * <p>State is in memory and bounded: this service exists to produce realistic signals, not to be a
 * payment system. An unbounded map here would be its own slow resource leak, which is exactly the
 * failure mode the fault subsystem is careful to avoid elsewhere.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

  private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

  /** Hard cap on retained payments. Oldest are evicted; this is a demo, not a ledger. */
  private static final int MAX_RETAINED = 1_000;

  private final Map<String, Payment> payments = new ConcurrentHashMap<>();
  private final FaultRegistry registry;
  private final Clock clock;

  public PaymentController(FaultRegistry registry, Clock clock) {
    this.registry = registry;
    this.clock = clock;
  }

  @PostMapping
  public ResponseEntity<Payment> authorize(@Valid @RequestBody PaymentRequest request) {
    simulateDownstreamCall();

    Payment payment =
        new Payment(
            UUID.randomUUID().toString(),
            request.orderId(),
            request.amountMinor(),
            request.currency(),
            "AUTHORIZED",
            clock.instant().toString());

    if (payments.size() >= MAX_RETAINED) {
      payments.keySet().stream().findFirst().ifPresent(payments::remove);
    }
    payments.put(payment.id(), payment);

    log.info(
        "Authorized payment id={} orderId={} amountMinor={} currency={}",
        payment.id(),
        payment.orderId(),
        payment.amountMinor(),
        payment.currency());

    return ResponseEntity.status(HttpStatus.CREATED).body(payment);
  }

  @GetMapping("/{id}")
  public ResponseEntity<Payment> get(@PathVariable String id) {
    Payment payment = payments.get(id);
    return payment == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(payment);
  }

  @GetMapping
  public List<Payment> list() {
    return payments.values().stream().limit(50).toList();
  }

  /**
   * Simulates a call to a downstream dependency.
   *
   * <p>Logs in the shape a real client would, so the log-based scenarios have something realistic
   * for an investigator to find.
   */
  private void simulateDownstreamCall() {
    var fault = registry.active(FaultType.DOWNSTREAM_TIMEOUT);
    if (fault.isEmpty()) {
      return;
    }
    String dependency = fault.get().stringParam("dependency", "payments-api");
    long millis = Math.min(fault.get().intParam("millis", 2000), FaultType.MAX_LATENCY.toMillis());
    try {
      Thread.sleep(millis);
      log.error("Read timed out calling {} after {}ms", dependency, millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public record PaymentRequest(
      @NotBlank String orderId, @Positive long amountMinor, @NotBlank String currency) {}

  public record Payment(
      String id,
      String orderId,
      long amountMinor,
      String currency,
      String status,
      String authorizedAt) {}
}
