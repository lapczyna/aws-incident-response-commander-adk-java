package com.lapczynski.commander.api.config;

import com.lapczynski.commander.application.ApprovalService;
import com.lapczynski.commander.application.IncidentService;
import com.lapczynski.commander.application.port.ActorDirectory;
import com.lapczynski.commander.application.port.ApprovalRepository;
import com.lapczynski.commander.application.port.AuditLog;
import com.lapczynski.commander.application.port.EvidenceRepository;
import com.lapczynski.commander.application.port.ExecutionRepository;
import com.lapczynski.commander.application.port.IncidentRepository;
import com.lapczynski.commander.application.port.IncidentStateLookup;
import com.lapczynski.commander.application.port.IncidentWorkflow;
import com.lapczynski.commander.application.port.ReportRepository;
import com.lapczynski.commander.application.port.SafetyMetrics;
import com.lapczynski.commander.application.port.TargetTags;
import com.lapczynski.commander.application.port.VerificationRepository;
import com.lapczynski.commander.application.report.IncidentReportRenderer;
import com.lapczynski.commander.application.report.IncidentReportService;
import com.lapczynski.commander.application.signal.MetricsPort;
import com.lapczynski.commander.application.verification.RecoveryVerifier;
import com.lapczynski.commander.domain.incident.Incident;
import com.lapczynski.commander.domain.policy.PolicyConfiguration;
import com.lapczynski.commander.domain.policy.PolicyEngine;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the application's own services.
 *
 * <p>Plain constructors, no component scanning of the domain. The classes below have no Spring
 * annotations on them and are not going to acquire any: the point of keeping the domain
 * framework-free is that every safety decision can be exercised in a unit test with no container,
 * and a {@code @Service} annotation on {@code ApprovalService} would quietly end that.
 */
@Configuration
@EnableConfigurationProperties(CommanderProperties.class)
public class CommanderConfiguration {

  private static final Logger log = LoggerFactory.getLogger(CommanderConfiguration.class);

  /**
   * The deployment's limits, logged at start-up.
   *
   * <p>Logged because this is the answer to "what can this instance do", and reconstructing it from
   * a dozen environment variables after the fact is exactly the exercise nobody performs during an
   * incident.
   */
  @Bean
  public PolicyConfiguration policyConfiguration(CommanderProperties properties) {
    PolicyConfiguration configuration = properties.policy().toDomain();

    log.info(
        "Policy: actionsEnabled={} dryRun={} account={} region={} environment={} "
            + "allowedActions={} allowlistedResources={} minimumConfidence={}",
        configuration.actionsEnabled(),
        configuration.dryRun(),
        configuration.awsAccountId(),
        configuration.awsRegion(),
        configuration.environment(),
        configuration.allowedActions(),
        configuration.allowedResourceArns().size(),
        configuration.minimumConfidence().value());

    if (configuration.actionsEnabled() && !configuration.dryRun()) {
      log.warn(
          "THIS INSTANCE CAN CHANGE AWS RESOURCES. Actions are enabled and dry run is off. "
              + "Approved remediations will be executed against account {} in {}.",
          configuration.awsAccountId(),
          configuration.awsRegion());
    }
    return configuration;
  }

  @Bean
  public PolicyEngine policyEngine(PolicyConfiguration configuration) {
    return new PolicyEngine(configuration);
  }

  /**
   * The tags the gate compares a target against.
   *
   * <p>Derived from the policy configuration rather than configured separately, so there is one
   * answer to "which tag marks demo infrastructure" instead of two that can disagree. The executor
   * still re-reads the live tags before acting; see {@link TargetTags}.
   */
  @Bean
  public TargetTags targetTags(CommanderProperties properties) {
    CommanderProperties.Policy policy = properties.policy();
    return new TargetTags(
        Map.of(
            policy.requiredTag().key(),
            policy.requiredTag().value(),
            "Environment",
            policy.environment()));
  }

  /**
   * The remediation tool's view of an incident: does it exist, and what state is it in.
   *
   * <p>A lambda over the repository rather than the repository itself. The tool is the only code in
   * the system that can change AWS, and handing it {@code IncidentRepository} would hand it {@code
   * update} as well — the ability to rewrite the record it is about to be judged against. This
   * gives it the one question it needs to ask.
   */
  @Bean
  public IncidentStateLookup incidentStateLookup(IncidentRepository incidents) {
    return incidentId -> incidents.findById(incidentId).map(Incident::status);
  }

  @Bean
  public ApprovalService approvalService(
      ApprovalRepository approvals,
      IncidentRepository incidents,
      AuditLog auditLog,
      Clock clock,
      SafetyMetrics metrics) {
    return new ApprovalService(approvals, incidents, auditLog, clock, metrics);
  }

  @Bean
  public RecoveryVerifier recoveryVerifier(
      MetricsPort metrics,
      EvidenceRepository evidence,
      ExecutionRepository executions,
      VerificationRepository verifications,
      IncidentRepository incidents,
      AuditLog auditLog,
      Clock clock,
      SafetyMetrics safetyMetrics) {
    return new RecoveryVerifier(
        metrics, evidence, executions, verifications, incidents, auditLog, clock, safetyMetrics);
  }

  @Bean
  public IncidentReportRenderer incidentReportRenderer() {
    return new IncidentReportRenderer();
  }

  @Bean
  public IncidentReportService incidentReportService(
      IncidentRepository incidents,
      EvidenceRepository evidence,
      AuditLog auditLog,
      ApprovalRepository approvals,
      ExecutionRepository executions,
      VerificationRepository verifications,
      ReportRepository reports,
      IncidentReportRenderer renderer,
      Clock clock) {
    return new IncidentReportService(
        incidents,
        evidence,
        auditLog,
        approvals,
        executions,
        verifications,
        reports,
        renderer,
        clock);
  }

  @Bean
  public IncidentService incidentService(
      IncidentRepository incidents,
      ApprovalRepository approvals,
      ActorDirectory actors,
      ApprovalService approvalService,
      IncidentWorkflow workflow,
      IncidentReportService reports,
      AuditLog auditLog,
      Clock clock,
      CommanderProperties properties) {
    return new IncidentService(
        incidents,
        approvals,
        actors,
        approvalService,
        workflow,
        reports,
        auditLog,
        clock,
        properties.approval().ttl());
  }

  /**
   * A metrics implementation is always present, and is a no-op unless one is registered.
   *
   * <p>{@link SafetyMetrics#NONE} rather than a null check at every call site. A service built
   * without a registry behaves identically to one with it, which is what makes "metrics never
   * affect a decision" true by construction rather than by review.
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(SafetyMetrics.class)
  public SafetyMetrics safetyMetrics() {
    return SafetyMetrics.NONE;
  }
}
