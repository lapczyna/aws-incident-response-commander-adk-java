package com.lapczynski.demotarget;

import com.lapczynski.demotarget.fault.FaultRegistry;
import com.lapczynski.demotarget.fault.FaultType;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Wiring for the demo target service. */
@Configuration
@EnableScheduling
public class DemoTargetConfiguration {

  /**
   * A single injectable clock.
   *
   * <p>Every fault expiry is evaluated against this, so tests can advance time deterministically
   * instead of sleeping. A test that proves expiry by waiting thirty real minutes is a test nobody
   * runs.
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /** Reports the running version, honouring the BAD_VERSION fault. */
  @RestController
  static class VersionController {

    private final FaultRegistry registry;
    private final String actualVersion;

    VersionController(FaultRegistry registry, ObjectProvider<BuildProperties> buildProperties) {
      this.registry = registry;
      BuildProperties build = buildProperties.getIfAvailable();
      this.actualVersion = build == null ? "2.3.1" : build.getVersion();
    }

    @GetMapping("/api/version")
    public VersionResponse version() {
      var fault = registry.active(FaultType.BAD_VERSION);
      String reported =
          fault.map(f -> f.stringParam("version", actualVersion)).orElse(actualVersion);
      return new VersionResponse(reported, !reported.equals(actualVersion));
    }

    /**
     * @param simulated whether this version is injected rather than real. Reported honestly so a
     *     demo can never be mistaken for a genuine bad deployment.
     */
    record VersionResponse(String version, boolean simulated) {}
  }
}
