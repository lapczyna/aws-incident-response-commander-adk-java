package com.lapczynski.commander.simulator;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Loads scenario fixtures from the classpath.
 *
 * <p>All fixtures are parsed eagerly at construction and a malformed one fails fast. A scenario
 * that only breaks when someone tries to run it would surface during a demo, which is the worst
 * possible moment.
 *
 * <p>Unknown YAML properties are rejected rather than ignored, so a typo in a fixture key is an
 * error rather than a silently missing signal. That matters here: a misspelled {@code afterValue}
 * would quietly produce a flat metric and an investigation that finds nothing, and the fixture
 * would look fine.
 */
@Component
public class ScenarioLibrary {

  private static final String FIXTURE_PATTERN = "classpath*:scenarios/*.yaml";

  private final Map<String, Scenario> scenarios;

  public ScenarioLibrary() {
    this(FIXTURE_PATTERN);
  }

  ScenarioLibrary(String pattern) {
    ObjectMapper mapper =
        new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .findAndRegisterModules();

    Map<String, Scenario> loaded = new LinkedHashMap<>();
    try {
      Resource[] resources = new PathMatchingResourcePatternResolver().getResources(pattern);
      for (Resource resource : resources) {
        try (InputStream in = resource.getInputStream()) {
          Scenario scenario = mapper.readValue(in, Scenario.class);
          Scenario previous = loaded.put(scenario.id(), scenario);
          if (previous != null) {
            throw new IllegalStateException(
                "duplicate scenario id '%s' in %s"
                    .formatted(scenario.id(), resource.getFilename()));
          }
        } catch (IOException e) {
          throw new IllegalStateException(
              "could not read scenario fixture " + resource.getFilename(), e);
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("could not scan for scenario fixtures", e);
    }

    if (loaded.isEmpty()) {
      throw new IllegalStateException(
          "no scenario fixtures found on the classpath matching " + pattern);
    }
    this.scenarios = Map.copyOf(loaded);
  }

  public Optional<Scenario> find(String id) {
    return Optional.ofNullable(scenarios.get(id));
  }

  public Scenario require(String id) {
    return find(id)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "unknown scenario '%s'; available: %s".formatted(id, ids())));
  }

  public List<String> ids() {
    return scenarios.keySet().stream().sorted().toList();
  }

  public List<Scenario> all() {
    return scenarios.values().stream().toList();
  }
}
