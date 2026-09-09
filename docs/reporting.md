# Verification and reporting

The last two things an incident response system does are decide whether the problem is gone and
write down what happened. Both are places where a plausible answer is worse than no answer, so both
are built so that the part which must be right is not the part a model produces.

## Recovery verification

**The verdict is arithmetic.** `RecoveryVerification.judge` compares the value that motivated the
incident, the value measured afterwards, and the threshold recovery is defined against. There is no
prompt involved. `RecoveryVerifierAgent` is a `BaseAgent` with no model in it, for the same reason
`PolicyGateAgent` is — see [ADR-0010](adr/0010-deterministic-recovery-verification.md).

| Outcome | Meaning | Incident goes to |
|---|---|---|
| `RECOVERED` | At or below the recovery threshold | `RESOLVED` |
| `NOT_RECOVERED` | The action completed; the symptom persists | `FAILED` |
| `PARTIALLY_RECOVERED` | Materially better, still breaching | `FAILED` |
| `INDETERMINATE` | Could not be measured | `FAILED` |

Only the first resolves an incident. The other three ask for a human, including
`PARTIALLY_RECOVERED`: "better, but not fixed" is a decision, and not one this system is entitled to
make on its own.

### What "improved" means

Improvement is measured against the distance back to the threshold, not as a ratio of the raw
values. A metric that fell from 100× the threshold to 50× has halved and is still an outage. Judged
as a ratio it looks like a 50% success; judged against the excess over the threshold it is not
recovery.

### Three ways to be wrong that are deliberately closed

**Measuring too early.** The observation window must lie entirely after the action settled, because
averaging across the moment of the change mixes the broken state into the result. Asking sooner
raises `TooSoonToVerifyException` rather than returning `NOT_RECOVERED` — concluding failure because
we were impatient would close an incident on a fix that may well have worked.

**Measuring nothing.** An unreadable metric is `INDETERMINATE`, never recovered. The failure is
recorded as an evidence gap so the report can say *why*, and a zero-confidence evidence row is
written so the verdict still cites something real rather than fabricating a datapoint to satisfy the
constructor.

**Measuring the wrong thing.** The metric and threshold are read from state fixed when the incident
was raised, not from anything produced later. Letting either be restated after the fact would allow
the target to move to wherever the measurement happened to land.

## The report

Two authors, and the split is the design:

| Section | Written by | Source |
|---|---|---|
| Summary, status, duration | Java | the incident aggregate |
| Analysis (three prose sections) | **the model** | session state |
| Timeline | Java | the append-only audit log |
| Evidence table and citation numbers | Java | stored evidence rows |
| Missing evidence | Java | stored evidence gaps |
| Remediation, approval, decision | Java | approval and execution tables |
| Recovery verification | Java | the stored measurement |

Everything is read back from the database rather than carried in memory from the investigation. That
is slower, and it means a claim in the report and a row in the audit log cannot disagree. It also
means a report can be regenerated for an incident the current process never handled.

### Citations are checked

The model is given a pre-numbered evidence catalogue and asked to reference it. It is not asked to
invent the numbering, because a number it chose could not be checked against anything.

After it writes, `IncidentReportRenderer.uncitedClaims` scans the narrative for references that
resolve to no stored observation and prints a warning **in the report** naming them:

> **Citation warning.** The narrative references [E7], which do not correspond to any stored
> observation. Treat the associated claims as unsupported.

Reported rather than silently stripped. A citation pointing at nothing is a finding about the
investigation, and hiding it would defeat the purpose of checking.

Citation numbers come from insertion order, using a generated sequence rather than `collected_at`.
Timestamps tie constantly — four specialists running in parallel record within the same millisecond
— and a regenerated report that renumbered itself would contradict one a human had already read.

### Things the model is explicitly forbidden to write

The narrator is instructed not to state whether the incident is resolved, not to write a timeline,
and not to describe the approval or the execution. It would produce all of them convincingly, and
all of them would be unverifiable against the sections generated beside them.

### Three things a reader must not be able to miss

- **A dry run says so**, in its own block quote, because a reader must never come away believing a
  real change was made when it was not.
- **A failed verification appears above the narrative**, not below it. The report most likely to be
  skim-read by someone assuming success is exactly the one where the fix did not work.
- **Missing evidence gets its own section.** The difference between "the logs showed nothing" and
  "the logs could not be read" changes what every conclusion above is worth.

Evidence from sources that can carry caller-influenced text — log output, CloudTrail descriptions —
is marked `⚠︎` in the table, and pipe characters are escaped so no observation can break out of a
Markdown cell.

## The sample postmortem

[`docs/samples/postmortem-checkout-latency.md`](samples/postmortem-checkout-latency.md) is not
written by hand. It is rendered by the real `IncidentReportRenderer` from a fixture in
`IncidentReportSampleTest`, and the test compares the output against the committed file — so a
renderer change that would alter what a reader sees fails the build rather than quietly leaving the
documentation describing a report the code no longer produces. Regenerate it with:

```bash
./mvnw test -pl commander-application -am -Dsamples.update=true
```

It deliberately shows a **failed** remediation, modelled on simulator scenario 8. A sample of a tidy
success would demonstrate the formatting and none of the engineering.
