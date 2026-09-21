package com.lapczynski.commander.api.config;

import com.lapczynski.commander.simulator.ScenarioLibrary;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Where an investigation gets its evidence.
 *
 * <p>Six ports, satisfied either by the deterministic simulator or by the AWS adapters. Nothing
 * upstream knows which: the specialists, the tools and the policy gate see the same interfaces
 * either way, and that is what makes the whole workflow runnable, demonstrable and testable with no
 * AWS account.
 *
 * <p>The simulator is the default because it is the safer of the two in both senses. It costs
 * nothing, and it cannot read anything real.
 */
@Configuration
public class SignalSourceConfiguration {

  /**
   * One object satisfying all six ports, because one scenario is one coherent world.
   *
   * <p>A single bean rather than six adapters, and not only to save typing: separate beans per port
   * would allow a metric from one scenario and logs from another, which would produce an
   * investigation whose evidence contradicts itself for reasons that have nothing to do with the
   * incident. It also means every injection point resolves to the same instance, so switching
   * scenarios switches all six at once.
   */
  @Bean
  @Profile("simulator")
  public DemoScenarioSource demoScenarioSource(
      ScenarioLibrary library, Clock clock, CommanderProperties properties) {
    return new DemoScenarioSource(library, clock, properties.demo().scenario());
  }
}
