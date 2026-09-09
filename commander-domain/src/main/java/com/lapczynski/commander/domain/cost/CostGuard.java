package com.lapczynski.commander.domain.cost;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stops model spending before it exceeds what was budgeted.
 *
 * <p><strong>Fails closed.</strong> When the limit is reached, calls are refused. The alternative —
 * warn and continue — turns a budget into a description of what already happened, and this project
 * states a hard limit on LLM spend that has to mean something.
 *
 * <p>The limit is per calendar month in UTC, matching how providers bill, and the accumulated total
 * resets when the month rolls over. Resetting on a rolling window would be friendlier and would not
 * correspond to any bill anyone receives.
 *
 * <p>Deliberately in the domain module, with no dependency on a metrics library or a database. The
 * decision to refuse is arithmetic on two numbers, and it should be testable without either.
 *
 * <p>This bounds <em>model</em> spend only. It knows nothing about what the surrounding AWS
 * infrastructure costs, and no configuration of it should be read as a claim about that.
 */
public final class CostGuard {

  /** Accumulated spend, and the month it belongs to. Replaced atomically as one value. */
  private record Ledger(YearMonth month, BigDecimal spent, long calls) {}

  private final BigDecimal monthlyLimit;
  private final AtomicReference<Ledger> ledger;

  /**
   * @param monthlyLimit the most that may be spent on model calls in a calendar month, in USD
   */
  public CostGuard(BigDecimal monthlyLimit, Instant now) {
    Objects.requireNonNull(monthlyLimit, "monthlyLimit must not be null");
    if (monthlyLimit.signum() < 0) {
      throw new IllegalArgumentException("monthlyLimit must not be negative");
    }
    this.monthlyLimit = monthlyLimit;
    this.ledger = new AtomicReference<>(new Ledger(monthOf(now), BigDecimal.ZERO, 0L));
  }

  /** Why a call was refused, or that it may proceed. */
  public sealed interface Verdict {

    record Allowed(BigDecimal spentThisMonth, BigDecimal remaining) implements Verdict {}

    /**
     * The budget is spent.
     *
     * @param overBy how far the projected spend exceeds the limit
     */
    record Refused(BigDecimal spentThisMonth, BigDecimal limit, BigDecimal overBy)
        implements Verdict {

      public String explanation() {
        return "The model budget of USD %s for this month is exhausted (spent USD %s). No further "
                .formatted(limit.toPlainString(), spentThisMonth.toPlainString())
            + "model calls will be made until the limit is raised or the month rolls over.";
      }
    }
  }

  /**
   * Whether a call costing {@code estimatedCost} may proceed.
   *
   * <p>Checked against the estimate <em>before</em> the call, not the actual cost after it. A guard
   * that only noticed afterwards would permit exactly one unbounded call, which for a large enough
   * context is the entire budget.
   */
  public Verdict check(BigDecimal estimatedCost, Instant now) {
    Ledger current = rollIfNeeded(now);
    BigDecimal projected = current.spent().add(estimatedCost);

    if (projected.compareTo(monthlyLimit) > 0) {
      return new Verdict.Refused(current.spent(), monthlyLimit, projected.subtract(monthlyLimit));
    }
    return new Verdict.Allowed(current.spent(), monthlyLimit.subtract(current.spent()));
  }

  /** Records what a completed call actually cost. */
  public void record(BigDecimal actualCost, Instant now) {
    Objects.requireNonNull(actualCost, "actualCost must not be null");
    if (actualCost.signum() < 0) {
      throw new IllegalArgumentException("a call cannot cost a negative amount");
    }

    YearMonth month = monthOf(now);
    ledger.updateAndGet(
        current ->
            current.month().equals(month)
                ? new Ledger(month, current.spent().add(actualCost), current.calls() + 1)
                // Month rolled over between the check and the record. Start the new month with
                // this call rather than discarding it.
                : new Ledger(month, actualCost, 1L));
  }

  public BigDecimal spentThisMonth(Instant now) {
    return rollIfNeeded(now).spent();
  }

  public long callsThisMonth(Instant now) {
    return rollIfNeeded(now).calls();
  }

  public BigDecimal monthlyLimit() {
    return monthlyLimit;
  }

  private Ledger rollIfNeeded(Instant now) {
    YearMonth month = monthOf(now);
    return ledger.updateAndGet(
        current ->
            current.month().equals(month) ? current : new Ledger(month, BigDecimal.ZERO, 0L));
  }

  private static YearMonth monthOf(Instant instant) {
    return YearMonth.from(instant.atZone(ZoneOffset.UTC));
  }
}
