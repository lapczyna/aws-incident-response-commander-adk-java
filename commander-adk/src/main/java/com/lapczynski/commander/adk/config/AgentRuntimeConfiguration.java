package com.lapczynski.commander.adk.config;

import com.google.adk.models.BaseLlm;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.lapczynski.commander.adk.agent.IncidentAgentFactory;
import com.lapczynski.commander.adk.approval.RemediationTool;
import com.lapczynski.commander.adk.plugin.InvestigationBudgetPlugin;
import com.lapczynski.commander.adk.tools.ChangeTools;
import com.lapczynski.commander.adk.tools.InvestigationTools;
import com.lapczynski.commander.adk.workflow.AdkIncidentWorkflow;
import com.lapczynski.commander.adk.workflow.EvidenceCapture;
import com.lapczynski.commander.application.ExecutionJournal;
import com.lapczynski.commander.application.port.EvidenceRepository;
import com.lapczynski.commander.application.port.IdempotencyStore;
import com.lapczynski.commander.application.port.IncidentWorkflow;
import com.lapczynski.commander.application.port.RemediationExecutorPort;
import com.lapczynski.commander.application.port.TargetTags;
import com.lapczynski.commander.application.report.IncidentReportService;
import com.lapczynski.commander.application.signal.AlarmsPort;
import com.lapczynski.commander.application.signal.ChangeHistoryPort;
import com.lapczynski.commander.application.signal.DeploymentHistoryPort;
import com.lapczynski.commander.application.signal.EcsPort;
import com.lapczynski.commander.application.signal.LogsPort;
import com.lapczynski.commander.application.signal.MetricsPort;
import com.lapczynski.commander.application.verification.RecoveryVerifier;
import com.lapczynski.commander.domain.policy.PolicyEngine;
import com.lapczynski.commander.domain.remediation.ProposedAction;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the agent runtime.
 *
 * <p>Lives here rather than in the application module for the reason {@code ArchitectureRulesTest}
 * enforces: ADK types do not leave {@code commander-adk}. A Spring configuration class that built a
 * {@code Runner} would drag the whole framework into whichever module held it, and the one module
 * that must stay free of it is the one holding the incident state machine.
 *
 * <p>Two runners, one session service. The diagnosis runner owns the pipeline that can pause for a
 * human; the closure runner measures recovery and narrates it. They share a session because the
 * narrator has to see what the investigation found — and because sharing it is what {@code
 * ResumeDiagnosticTest} proves is enough to resume an invocation from a process that did not start
 * it.
 */
@Configuration
public class AgentRuntimeConfiguration {

  private static final Logger log = LoggerFactory.getLogger(AgentRuntimeConfiguration.class);

  /**
   * The scheduler the blocking stages run on.
   *
   * <p>Explicitly the IO scheduler, not RxJava's default. The specialists perform blocking JDBC and
   * HTTP work, and the computation scheduler is sized to the CPU count — four blocked
   * investigations would starve it, and the symptom would be investigations that simply stop.
   */
  @Bean
  public Scheduler agentScheduler() {
    return Schedulers.io();
  }

  @Bean
  public InvestigationTools investigationTools(
      MetricsPort metrics, LogsPort logs, EcsPort ecs, AlarmsPort alarms, Clock clock) {
    return new InvestigationTools(metrics, logs, ecs, alarms, clock);
  }

  @Bean
  public ChangeTools changeTools(
      ChangeHistoryPort changes, DeploymentHistoryPort deployments, Clock clock) {
    return new ChangeTools(changes, deployments, clock);
  }

  @Bean
  public EvidenceCapture evidenceCapture(EvidenceRepository evidence, Clock clock) {
    return new EvidenceCapture(evidence, clock);
  }

  @Bean
  public ExecutionJournal executionJournal(
      com.lapczynski.commander.application.port.ExecutionRepository executions,
      com.lapczynski.commander.application.port.ApprovalRepository approvals,
      Clock clock) {
    return new ExecutionJournal(executions, approvals, clock);
  }

  /**
   * The only tool that can change anything.
   *
   * <p>The executor is supplied by whichever integration profile is active. There is deliberately
   * no default: a remediation tool with a no-op executor would report success for an action that
   * never happened, which is the one failure mode this system exists to avoid. Absent an executor
   * bean, it refuses at execution time and says why.
   */
  @Bean
  public RemediationTool remediationTool(
      PolicyEngine policyEngine,
      IdempotencyStore idempotency,
      TargetTags targetTags,
      ObjectProvider<RemediationExecutorPort> executor,
      ExecutionJournal journal,
      Clock clock) {

    RemediationExecutorPort resolved =
        executor.getIfAvailable(() -> AgentRuntimeConfiguration::refuseWithNoExecutor);

    // The port becomes the tool's own functional interface here, and nowhere else. That one method
    // reference is the entire coupling between the agent runtime and whatever can actually change
    // something, which is why neither module needs to know the other exists.
    return new RemediationTool(
        policyEngine, idempotency, targetTags.values(), resolved::execute, journal, clock);
  }

  private static String refuseWithNoExecutor(ProposedAction action) {
    throw new IllegalStateException(
        "No remediation executor is configured, so %s cannot be performed against %s. Nothing "
                .formatted(action.type(), action.target().arn())
            + "was changed. Activate the aws profile, or leave dry-run enabled.");
  }

  /**
   * The runner that investigates and, if a human agrees, acts.
   *
   * <p>The plugin is where the tool-call budget and the wall-clock deadline live. Both are
   * per-invocation, so a runaway investigation is bounded without bounding the next one.
   */
  @Bean
  public Runner diagnosisRunner(
      BaseLlm model,
      InvestigationTools tools,
      ChangeTools changeTools,
      PolicyEngine policyEngine,
      RemediationTool remediationTool,
      BaseSessionService sessions,
      Scheduler agentScheduler,
      TargetTags targetTags,
      @Value("${commander.investigation.max-tool-calls:16}") int maxToolCalls,
      @Value("${commander.investigation.deadline:PT5M}") Duration deadline) {

    log.info(
        "Agent runtime: maxToolCalls={} deadline={} targetTags={}",
        maxToolCalls,
        deadline,
        targetTags.values());

    return Runner.builder()
        .app(
            IncidentAgentFactory.resumableApp(
                IncidentAgentFactory.incidentPipeline(
                    model,
                    tools,
                    changeTools,
                    policyEngine,
                    targetTags.values(),
                    remediationTool,
                    agentScheduler),
                List.of(new InvestigationBudgetPlugin(maxToolCalls, deadline))))
        .sessionService(sessions)
        .build();
  }

  /** The runner that measures recovery and writes the narrative. */
  @Bean
  public Runner closureRunner(
      BaseLlm model, RecoveryVerifier verifier, BaseSessionService sessions) {
    return Runner.builder()
        .app(
            IncidentAgentFactory.resumableApp(
                IncidentAgentFactory.verificationPipeline(model, verifier)))
        .sessionService(sessions)
        .build();
  }

  @Bean
  public IncidentWorkflow incidentWorkflow(
      Runner diagnosisRunner,
      Runner closureRunner,
      BaseSessionService sessions,
      EvidenceCapture evidenceCapture,
      IncidentReportService reports) {
    return new AdkIncidentWorkflow(
        diagnosisRunner, closureRunner, sessions, evidenceCapture, reports);
  }
}
