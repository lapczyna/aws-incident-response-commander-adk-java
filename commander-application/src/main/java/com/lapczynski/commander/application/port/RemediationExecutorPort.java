package com.lapczynski.commander.application.port;

import com.lapczynski.commander.domain.remediation.ProposedAction;

/**
 * Performs an action that policy allowed and a human authorised.
 *
 * <p>An outbound port with exactly one method, so that the thing which can change the world is
 * replaceable per deployment: AWS in a real one, nothing at all in a demo. It exists because the
 * only other place this shape was expressed was inside the agent module, and having the AWS adapter
 * implement an interface from there would put ADK on the AWS module's classpath — which {@code
 * ArchitectureRulesTest} forbids, and which would be the wrong dependency anyway.
 *
 * <p><strong>Implementations are reached only from inside the remediation tool</strong>, after the
 * policy engine has run for the second time and the action's fingerprint has been claimed. An
 * implementation is therefore entitled to assume it has been authorised — and should still verify
 * anything it can check for itself against live state, because the alternative is trusting a caller
 * with what it was told rather than with what is true.
 */
@FunctionalInterface
public interface RemediationExecutorPort {

  /**
   * @return a short description of what was done, for the incident record. Must describe what
   *     actually happened rather than what was requested: this string ends up in a report a human
   *     reads to decide whether the incident is over.
   * @throws RuntimeException if the action could not be performed. Throwing is correct; returning a
   *     cheerful string for an action that failed is the failure mode this system exists to avoid.
   */
  String execute(ProposedAction action);
}
