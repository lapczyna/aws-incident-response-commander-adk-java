package com.lapczynski.commander.adk.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.models.BaseLlm;

/**
 * The narrative half of the incident report.
 *
 * <p>The report is written in two places. Java assembles the timeline, the evidence table, the
 * approval record and the verification result from stored rows; this agent writes the prose that
 * explains them. The split is what makes "every conclusion references stored evidence" checkable
 * rather than merely claimed — see {@code IncidentReportRenderer}, which verifies the citations
 * this agent produces against the observations that actually exist and prints a warning in the
 * report itself when one points at nothing.
 *
 * <p>So this agent is given a numbered catalogue of evidence and asked to reference it. It is not
 * asked for a timeline, a verdict, or a list of what was approved: it would produce all three
 * convincingly, and all three would be unverifiable.
 */
public final class ReportAgents {

  private ReportAgents() {}

  /** Session-state key holding the model's prose. */
  public static final String KEY_NARRATIVE = "report_narrative";

  /**
   * Writes the narrative sections of the report.
   *
   * <p>No tools, deliberately. Everything it needs is already in session state, and the ability to
   * gather fresh evidence at report time would let it support a conclusion with an observation that
   * no investigator recorded and no reader can find in the evidence table.
   */
  public static LlmAgent narrator(BaseLlm model) {
    return LlmAgent.builder()
        .name("report_narrator")
        .description(
            "Writes the prose sections of an incident report from evidence collected earlier. "
                + "Produces no timeline, verdict or approval record: those are assembled by code.")
        .model(model)
        .maxSteps(2)
        .outputKey(KEY_NARRATIVE)
        .instruction(
            """
            You are writing the narrative section of a postmortem that an engineer who was asleep
            during the incident will read tomorrow morning.

            What the investigation found:
            {investigation_summary}

            The hypothesis that was acted on:
            {hypothesis}

            The evidence you may cite, already numbered:
            {citable_evidence}

            The verification result, decided by measurement and not by you:
            {verification_summary}

            Write three short sections in Markdown, using `###` headings:

            ### What happened
            The sequence of events in plain language. Two to four sentences.

            ### Why it happened
            The cause the evidence supports. If the evidence does not identify a cause, say exactly
            that. An honest "we do not know why" is a useful postmortem; a plausible cause invented
            to fill the section is worse than nothing, because the next person will build on it.

            ### What we learned
            What would have detected this sooner, or prevented it. Concrete, and drawn from this
            incident rather than from general good practice.

            Rules, all of which matter:

            - Cite evidence as [E1], [E2] and so on, using ONLY the numbers in the catalogue above.
              Every factual claim needs one. If you cannot support a claim with a listed
              observation, do not make the claim. Citations are checked against stored evidence
              after you write, and an invented reference appears as a warning in the report.
            - Do NOT state whether the incident is resolved, whether the fix worked, or what the
              status is. That was decided by measurement and is already in the report. If your
              prose contradicts it, the prose is what is wrong.
            - Do NOT write a timeline, list the approval, or describe what was executed. Those
              sections are generated from the audit log and would disagree with you.
            - If the verification result says the symptom persisted, do not soften it. The engineer
              reading this needs to know the problem is still there.
            - Write about the system, not about yourself. No mention of agents, models, tools or
              this process.
            """)
        .build();
  }
}
