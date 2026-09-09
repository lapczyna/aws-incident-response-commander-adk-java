package com.lapczynski.demotarget.fault;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Burns CPU on a bounded number of threads, for a bounded time, at a bounded duty cycle.
 *
 * <p>This is the fault with the most potential to ruin the host it runs on, so it has the most
 * constraints. All four hold simultaneously:
 *
 * <ol>
 *   <li><strong>Thread count</strong> is capped at {@code availableProcessors() - 1}, so at least
 *       one core always remains for the request that turns the fault off.
 *   <li><strong>Duty cycle</strong> is 70% — each thread burns for 70ms then sleeps 30ms. Even at
 *       maximum threads the machine stays responsive and the process stays killable.
 *   <li><strong>Deadline</strong> is checked inside the loop, so a thread stops on its own even if
 *       nothing signals it.
 *   <li><strong>Interruptible</strong> — the loop honours the interrupt flag, so shutdown is
 *       immediate rather than waiting out the fault.
 * </ol>
 *
 * <p>Threads are daemons. A JVM that is asked to exit will not be held open by a demo fault.
 */
@Component
public class CpuPressureGenerator {

  private static final Logger log = LoggerFactory.getLogger(CpuPressureGenerator.class);

  private static final long BURN_NANOS =
      (long) (FaultType.CPU_CYCLE.toNanos() * FaultType.CPU_DUTY_CYCLE);
  private static final long IDLE_MILLIS =
      (long) (FaultType.CPU_CYCLE.toMillis() * (1 - FaultType.CPU_DUTY_CYCLE));

  private final FaultRegistry registry;
  private final Clock clock;
  private final List<Thread> burners = new CopyOnWriteArrayList<>();
  private final AtomicBoolean running = new AtomicBoolean(false);

  public CpuPressureGenerator(FaultRegistry registry, Clock clock) {
    this.registry = registry;
    this.clock = clock;
  }

  /**
   * Starts or stops burner threads to match the current fault state.
   *
   * <p>Polled rather than event-driven so that expiry, manual clearing and process restart all
   * converge on the same behaviour without a separate teardown path for each.
   */
  @Scheduled(fixedDelay = 1_000)
  public void reconcile() {
    var fault = registry.active(FaultType.CPU_PRESSURE);

    if (fault.isEmpty()) {
      if (running.compareAndSet(true, false)) {
        stopBurners();
      }
      return;
    }

    if (running.compareAndSet(false, true)) {
      startBurners(fault.get());
    }
  }

  private void startBurners(ActiveFault fault) {
    int requested = fault.intParam("threads", 1);
    int threads = Math.min(requested, FaultType.maxCpuThreads());
    Instant deadline = fault.expiresAt();

    log.warn(
        "CPU pressure starting: threads={} (requested {}, host cap {}) until {}",
        threads,
        requested,
        FaultType.maxCpuThreads(),
        deadline);

    for (int i = 0; i < threads; i++) {
      Thread burner = new Thread(() -> burnUntil(deadline), "demo-cpu-burner-" + i);
      burner.setDaemon(true);
      // Below normal priority: the fault should degrade the service, not outcompete the request
      // handler that will be used to switch it off.
      burner.setPriority(Thread.MIN_PRIORITY);
      burners.add(burner);
      burner.start();
    }
  }

  private void stopBurners() {
    log.info("CPU pressure stopping: interrupting {} thread(s)", burners.size());
    burners.forEach(Thread::interrupt);
    burners.clear();
  }

  /**
   * The burn loop.
   *
   * <p>Every iteration checks both the deadline and the interrupt flag, so the loop terminates on
   * its own schedule even if the reconciler never runs again. There is no path through this method
   * that spins indefinitely.
   */
  private void burnUntil(Instant deadline) {
    try {
      while (clock.instant().isBefore(deadline) && !Thread.currentThread().isInterrupted()) {
        long burnUntilNanos = System.nanoTime() + BURN_NANOS;
        // Busy work with a bounded end, re-checking interruption so a long burn phase cannot
        // delay shutdown by more than one cycle.
        while (System.nanoTime() < burnUntilNanos && !Thread.currentThread().isInterrupted()) {
          Math.sqrt(System.nanoTime() % 1_000_003d);
        }
        Thread.sleep(IDLE_MILLIS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Stops every burner on shutdown, whatever state the registry is in. */
  @PreDestroy
  public void shutdown() {
    running.set(false);
    stopBurners();
  }

  /** Burner threads currently alive. Exposed for tests and for the fault status endpoint. */
  public int activeBurners() {
    return (int) burners.stream().filter(Thread::isAlive).count();
  }
}
