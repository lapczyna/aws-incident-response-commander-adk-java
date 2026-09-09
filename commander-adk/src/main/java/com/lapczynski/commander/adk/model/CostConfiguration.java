package com.lapczynski.commander.adk.model;

import com.lapczynski.commander.adk.plugin.TelemetryPlugin;
import com.lapczynski.commander.domain.cost.CostGuard;
import com.lapczynski.commander.domain.cost.ModelPricing;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Prices the active model and enforces the monthly budget.
 *
 * <p>One pricing bean per profile, mirroring {@link ModelProfiles}, so that adding a provider means
 * stating what it costs. There is no default: a provider with no declared price would be billed at
 * zero and spend without limit, which is precisely the failure the budget exists to prevent.
 *
 * <p>Prices are configuration rather than constants because they change, differ by region, and a
 * stale hard-coded figure produces a guard that is confidently wrong. The defaults are the
 * published list prices at the time of writing and are stated in {@code docs/model-setup.md}.
 */
@Configuration
public class CostConfiguration {

  private static final Logger log = LoggerFactory.getLogger(CostConfiguration.class);

  /**
   * The monthly ceiling on model spend.
   *
   * <p>Bounds LLM usage only. It says nothing about what the surrounding AWS infrastructure costs,
   * and no value configured here should be read as a claim about that.
   */
  @Bean
  public CostGuard costGuard(
      @Value("${commander.model.monthly-budget-usd:10.00}") String monthlyBudgetUsd, Clock clock) {

    BigDecimal limit = new BigDecimal(monthlyBudgetUsd);
    log.info("Model spending limit: USD {} per calendar month, fail-closed", limit.toPlainString());
    return new CostGuard(limit, clock.instant());
  }

  @Bean
  @Profile("gemini")
  public ModelPricing geminiPricing(
      @Value("${commander.model.gemini.name:gemini-3.5-flash-lite}") String modelName,
      @Value("${commander.model.gemini.price.input-per-million:0.10}") String input,
      @Value("${commander.model.gemini.price.output-per-million:0.40}") String output) {
    return new ModelPricing(modelName, new BigDecimal(input), new BigDecimal(output));
  }

  /**
   * Local inference has no per-token price.
   *
   * <p>Zero is accurate here, not a placeholder. Running a model locally costs electricity and
   * hardware, neither of which this guard can see or should pretend to.
   */
  @Bean
  @Profile("ollama")
  public ModelPricing ollamaPricing(
      @Value("${commander.model.ollama.name:qwen3:4b}") String modelName) {
    return ModelPricing.free(modelName);
  }

  @Bean
  @Profile("bedrock")
  public ModelPricing bedrockPricing(
      @Value("${commander.model.bedrock.name:amazon.nova-lite-v1:0}") String modelName,
      @Value("${commander.model.bedrock.price.input-per-million:0.06}") String input,
      @Value("${commander.model.bedrock.price.output-per-million:0.24}") String output) {
    return new ModelPricing(modelName, new BigDecimal(input), new BigDecimal(output));
  }

  /** The CI and local-demo default. Costs nothing because it calls nothing. */
  @Bean
  @Profile("fake")
  public ModelPricing fakePricing() {
    return ModelPricing.free("fake");
  }

  @Bean
  public TelemetryPlugin telemetryPlugin(
      MeterRegistry meters, ModelPricing pricing, CostGuard costGuard, Clock clock) {
    return new TelemetryPlugin(meters, pricing, costGuard, clock);
  }

  /**
   * The clock everything time-dependent reads.
   *
   * <p>A bean rather than {@code Instant.now()} scattered through the code, so tests can fix time
   * and so expiry, budgets and verification windows all agree on what "now" means.
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
  public Clock systemClock() {
    return Clock.systemUTC();
  }
}
