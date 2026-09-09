package com.lapczynski.commander.domain.cost;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * What a model costs, per million tokens.
 *
 * <p>{@link BigDecimal} rather than {@code double}. These figures are summed thousands of times and
 * compared against a spending limit, and binary floating point drifts in exactly the direction that
 * makes a limit stop being a limit. The amounts are small enough that the drift would take a long
 * time to matter and long enough that nobody would notice when it did.
 *
 * <p>Prices are configuration, not constants. They change, they differ by region, and a hard-coded
 * price that has gone stale produces a cost guard that is confidently wrong. {@link #free()} exists
 * for the local and fake profiles, where the honest price is zero rather than a guess.
 *
 * @param inputPerMillionTokens price of one million input tokens, in USD
 * @param outputPerMillionTokens price of one million output tokens, in USD
 */
public record ModelPricing(
    String modelId, BigDecimal inputPerMillionTokens, BigDecimal outputPerMillionTokens) {

  private static final BigDecimal MILLION = new BigDecimal("1000000");

  /** Currency amounts are carried to six places; USD is rounded only for display. */
  private static final MathContext PRECISION = new MathContext(12, RoundingMode.HALF_UP);

  public ModelPricing {
    Objects.requireNonNull(modelId, "modelId must not be null");
    Objects.requireNonNull(inputPerMillionTokens, "inputPerMillionTokens must not be null");
    Objects.requireNonNull(outputPerMillionTokens, "outputPerMillionTokens must not be null");

    if (inputPerMillionTokens.signum() < 0 || outputPerMillionTokens.signum() < 0) {
      throw new IllegalArgumentException("prices must not be negative");
    }
  }

  /**
   * A model that costs nothing to call.
   *
   * <p>For the Ollama and fake profiles. Local inference has a real cost in electricity and
   * hardware; it has no per-token cost, and pretending otherwise would put a fictional number in
   * front of an operator.
   */
  public static ModelPricing free(String modelId) {
    return new ModelPricing(modelId, BigDecimal.ZERO, BigDecimal.ZERO);
  }

  /** Price in USD for the given token counts. */
  public BigDecimal costOf(long inputTokens, long outputTokens) {
    if (inputTokens < 0 || outputTokens < 0) {
      throw new IllegalArgumentException("token counts must not be negative");
    }

    BigDecimal input =
        inputPerMillionTokens
            .multiply(BigDecimal.valueOf(inputTokens), PRECISION)
            .divide(MILLION, PRECISION);
    BigDecimal output =
        outputPerMillionTokens
            .multiply(BigDecimal.valueOf(outputTokens), PRECISION)
            .divide(MILLION, PRECISION);

    return input.add(output, PRECISION);
  }

  public boolean isFree() {
    return inputPerMillionTokens.signum() == 0 && outputPerMillionTokens.signum() == 0;
  }
}
