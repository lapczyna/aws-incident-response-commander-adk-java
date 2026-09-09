package com.lapczynski.commander.aws;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.ecs.EcsClient;

/**
 * AWS clients, configured with explicit bounds.
 *
 * <p>Every default the SDK ships is overridden here, because the defaults are wrong for this
 * workload in a specific way: they are tuned for a service that would rather wait than fail, and an
 * incident investigation would rather fail than wait. A CloudWatch call that takes two minutes to
 * succeed has already missed the moment it mattered, and it has held an agent's budget the whole
 * time.
 *
 * <p><strong>No static credentials, ever.</strong> {@link DefaultCredentialsProvider} resolves the
 * task role in ECS and the developer's profile locally. There is no code path that accepts an
 * access key, and none should be added: a key in configuration is a key in a log, an image, or a
 * repository sooner or later.
 *
 * <p>Only active under the {@code aws} profile. Without it the simulator satisfies the same ports,
 * which is what lets the whole system run and be tested with no AWS account.
 */
@Configuration
@Profile("aws")
public class AwsClientConfiguration {

  /**
   * Time allowed for a single HTTP attempt.
   *
   * <p>Deliberately short. An investigation runs several sources concurrently and has a wall-clock
   * deadline of its own; a source that cannot answer in five seconds is better reported as an
   * evidence gap than waited on.
   */
  private static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(5);

  /**
   * Total time allowed for a call including retries.
   *
   * <p>Bounds the worst case at roughly two attempts plus backoff, so one slow source cannot
   * consume the whole investigation's deadline on its own.
   */
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(15);

  /**
   * Retry budget.
   *
   * <p>Two retries, not the SDK's default of three, and only for genuinely transient conditions:
   * throttling, 5xx and connection failures. A retried 4xx is a retried mistake, and retrying an
   * AccessDenied three times turns a clear permissions problem into a slow one.
   */
  private static final int MAX_RETRIES = 2;

  private static ClientOverrideConfiguration overrides() {
    return ClientOverrideConfiguration.builder()
        .apiCallAttemptTimeout(ATTEMPT_TIMEOUT)
        .apiCallTimeout(CALL_TIMEOUT)
        .retryStrategy(
            software.amazon.awssdk.retries.StandardRetryStrategy.builder()
                .maxAttempts(MAX_RETRIES + 1)
                .backoffStrategy(
                    software.amazon.awssdk.retries.api.BackoffStrategy.exponentialDelay(
                        Duration.ofMillis(100), Duration.ofSeconds(2)))
                .build())
        .build();
  }

  @Bean
  public CloudWatchClient cloudWatchClient(@Value("${commander.aws.region}") String region) {
    return CloudWatchClient.builder()
        .region(Region.of(region))
        .credentialsProvider(DefaultCredentialsProvider.create())
        .overrideConfiguration(overrides())
        .build();
  }

  @Bean
  public CloudWatchLogsClient cloudWatchLogsClient(
      @Value("${commander.aws.region}") String region) {
    return CloudWatchLogsClient.builder()
        .region(Region.of(region))
        .credentialsProvider(DefaultCredentialsProvider.create())
        .overrideConfiguration(overrides())
        .build();
  }

  @Bean
  public EcsClient ecsClient(@Value("${commander.aws.region}") String region) {
    return EcsClient.builder()
        .region(Region.of(region))
        .credentialsProvider(DefaultCredentialsProvider.create())
        .overrideConfiguration(overrides())
        .build();
  }

  @Bean
  public CloudTrailClient cloudTrailClient(@Value("${commander.aws.region}") String region) {
    return CloudTrailClient.builder()
        .region(Region.of(region))
        .credentialsProvider(DefaultCredentialsProvider.create())
        .overrideConfiguration(overrides())
        .build();
  }

  /** Exposed so tests can assert the bounds rather than trusting that they were set. */
  public static Duration attemptTimeout() {
    return ATTEMPT_TIMEOUT;
  }

  public static Duration callTimeout() {
    return CALL_TIMEOUT;
  }

  public static int maxRetries() {
    return MAX_RETRIES;
  }
}
