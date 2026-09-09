# Architecture diagrams

All diagrams are Mermaid, so they render directly on GitHub and stay diffable in review.

---

## 1. Component view

The dependency rule runs strictly inwards. Each outbound port has two implementations — real AWS and
the deterministic simulator — which is what makes the full demo work with no AWS account.

```mermaid
graph TB
    subgraph API["commander-api  (Spring Boot composition root)"]
        REST["REST + OpenAPI"]
        UI["Thymeleaf + HTMX console"]
        SSE["SSE progress stream"]
        SEC["Spring Security<br/>viewer / investigator / approver"]
    end

    subgraph ADAPTERS["Adapters"]
        ADK["commander-adk<br/>agents, tools, plugins,<br/>model profiles, Rx bridge"]
        PG["commander-persistence-postgres<br/>Flyway, repositories,<br/>ADK Session/Memory/Artifact"]
        AWS["commander-integrations-aws<br/>CloudWatch, Logs, ECS, CloudTrail"]
        SIM["commander-simulator<br/>deterministic fixtures"]
    end

    subgraph APP["commander-application"]
        UC["Use cases"]
        PORTS["Outbound ports<br/>SignalSource, RemediationExecutor,<br/>IncidentRepository, ApprovalStore"]
    end

    subgraph DOM["commander-domain  (no framework dependencies)"]
        AGG["Incident aggregate"]
        FSM["State machine"]
        POL["Policy engine<br/>allowlists, risk, scope"]
        FP["Action fingerprint"]
    end

    REST --> UC
    UI --> UC
    SSE --> ADK
    SEC --> UC

    ADK --> PORTS
    PG --> PORTS
    AWS -.implements.-> PORTS
    SIM -.implements.-> PORTS

    UC --> AGG
    UC --> FSM
    UC --> POL
    UC --> FP

    EXT1["Gemini API"] -.-> ADK
    EXT2["Ollama<br/>local"] -.-> ADK
    EXT3["Bedrock<br/>optional"] -.-> ADK
    DB[("PostgreSQL")] --- PG
    TGT["demo-target-service<br/>fault injection"] -.observed by.-> AWS
    TGT -.observed by.-> SIM

    classDef domain fill:#1f4e5f,stroke:#0d2b35,color:#fff
    classDef app fill:#2d6a7a,stroke:#0d2b35,color:#fff
    classDef adapter fill:#4a8fa3,stroke:#0d2b35,color:#fff
    classDef api fill:#7ab8c8,stroke:#0d2b35,color:#000
    class AGG,FSM,POL,FP domain
    class UC,PORTS app
    class ADK,PG,AWS,SIM adapter
    class REST,UI,SSE,SEC api
```

---

## 2. Agent topology

Deterministic stages are square; LLM stages are rounded. Note that the coordinator and the policy
gate are both plain Java — the model never decides what happens next. See ADR-0003.

```mermaid
graph TD
    START["Incident received"] --> SEQ

    subgraph SEQ["SequentialAgent: incident_commander"]
        direction TB
        CLS("intake_classifier<br/><i>outputKey: classification</i>")

        subgraph PAR["ParallelAgent: evidence_collection"]
            direction LR
            M("metrics_investigator")
            L("logs_investigator")
            E("ecs_investigator")
            C("change_investigator")
        end

        subgraph LOOP["LoopAgent: hypothesis_refinement — maxIterations = 3"]
            direction TB
            H("hypothesis_agent") --> CRIT("hypothesis_critic<br/>ExitLoopTool")
            CRIT -->|"escalate = refine again"| H
        end

        PLAN("remediation_planner")
        GATE["PolicyGateAgent<br/><b>plain Java, no LLM</b><br/>allowlist, scope, risk, budget"]
        EXEC("remediation_executor<br/>LongRunningFunctionTool<br/>requireConfirmation = true")
        VER("recovery_verifier")
        RPT("incident_report_agent")

        CLS --> PAR --> LOOP --> PLAN --> GATE
        GATE -->|ALLOW| EXEC
        EXEC --> VER --> RPT
    end

    GATE -->|DENY: escalate| REJ["Recorded, no action taken"]
    EXEC -.->|"emits adk_request_confirmation<br/>invocation ends"| WAIT[["AWAITING_APPROVAL<br/>durable — process may restart"]]
    WAIT -.->|"approved: FunctionResponse replayed"| EXEC
    RPT --> DONE["Postmortem"]

    classDef llm fill:#7ab8c8,stroke:#0d2b35,color:#000
    classDef det fill:#1f4e5f,stroke:#0d2b35,color:#fff
    class CLS,M,L,E,C,H,CRIT,PLAN,EXEC,VER,RPT llm
    class GATE,WAIT,REJ det
```

---

## 3. Approval sequence, including restart

The load-bearing detail: the policy engine runs **twice** — once at the gate, and again inside the
tool body at execution time. Approval is necessary but never sufficient. See ADR-0007.

```mermaid
sequenceDiagram
    autonumber
    participant U as Approver
    participant API as commander-api
    participant R as ADK Runner
    participant T as Action tool
    participant P as Policy engine
    participant DB as PostgreSQL

    R->>T: call remediation tool
    T->>P: evaluate (allowlist, scope, risk)
    P-->>T: ALLOW, approval required
    T->>R: requestConfirmation(hint, payload)
    R-->>API: adk_request_confirmation — invocation ends
    API->>DB: persist ApprovalRequest + fingerprint<br/>SHA-256(action, args, arn, incidentId, version)
    API->>DB: incident -> AWAITING_APPROVAL

    Note over API,DB: Process may be killed here.<br/>All state is durable.

    U->>API: POST /approvals/{id}/approve
    API->>DB: load incident + approval
    API->>API: re-derive fingerprint from current state
    alt fingerprint differs
        API-->>U: 409 APPROVAL_STALE
    else fingerprint matches
        API->>DB: record decision + approver identity (append-only audit)
        API->>R: replay FunctionResponse<br/>adk_request_confirmation, confirmed = true
        R->>T: resume the original tool call
        T->>P: re-check policy at execution time
        T->>DB: check idempotency_keys[fingerprint]
        alt already executed
            DB-->>T: prior result
            T-->>R: prior result, no second action
        else first execution
            T->>T: execute (dry-run unless explicitly enabled)
            T->>DB: store result under fingerprint
        end
        R->>R: recovery_verifier, then incident_report_agent
    end
```

---

## 4. Incident state machine

Every transition is explicit; anything absent from this diagram throws. Invalid transitions are
tested exhaustively over all state pairs.

```mermaid
stateDiagram-v2
    [*] --> RECEIVED
    RECEIVED --> INVESTIGATING
    INVESTIGATING --> FORMING_HYPOTHESIS
    FORMING_HYPOTHESIS --> PLANNING_REMEDIATION
    FORMING_HYPOTHESIS --> RESOLVED: no actionable incident
    PLANNING_REMEDIATION --> AWAITING_APPROVAL
    AWAITING_APPROVAL --> REMEDIATING: approved
    AWAITING_APPROVAL --> REJECTED: rejected or expired
    REMEDIATING --> VERIFYING
    VERIFYING --> RESOLVED: recovery confirmed
    VERIFYING --> FAILED: verification failed

    INVESTIGATING --> CANCELLED
    FORMING_HYPOTHESIS --> CANCELLED
    PLANNING_REMEDIATION --> CANCELLED
    AWAITING_APPROVAL --> CANCELLED

    INVESTIGATING --> FAILED
    FORMING_HYPOTHESIS --> FAILED
    PLANNING_REMEDIATION --> FAILED
    REMEDIATING --> FAILED

    RESOLVED --> [*]
    REJECTED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]
```

---

## 5. Deployment view

Cost-conscious by default: no NAT Gateway, single-AZ RDS, one task per service, short log retention.
Everything expensive sits behind an `enable_*` variable that defaults to off.

```mermaid
graph TB
    subgraph AWS["AWS account — demo environment"]
        subgraph VPC["VPC"]
            subgraph PUB["Public subnets — egress-capable Fargate"]
                C1["ECS Fargate<br/>commander-api<br/>1 task"]
                C2["ECS Fargate<br/>demo-target-service<br/>1 task"]
                ALB["ALB<br/><i>optional</i>"]
            end
            subgraph PRIV["Isolated DB subnets — no NAT"]
                RDS[("RDS PostgreSQL<br/>single-AZ, not HA")]
            end
        end
        ECR["ECR"]
        SM["Secrets Manager<br/>model API key, DB password"]
        CW["CloudWatch<br/>logs, metrics, alarms, dashboard"]
        BUD["AWS Budget alert"]
    end

    DEV["Developer"] -->|"terraform apply<br/>manual workflow_dispatch"| AWS
    GH["GitHub Actions<br/>OIDC, no static keys"] -->|push image| ECR
    ECR --> C1
    ECR --> C2
    C1 --- RDS
    C1 --> SM
    C1 -->|read-only:<br/>CloudWatch, ECS, CloudTrail| CW
    C1 -->|"investigates"| C2
    C1 --> CW
    C2 --> CW
    ALB -.-> C1
    GEM["Gemini API"] -.->|"egress via public subnet"| C1

    classDef optional stroke-dasharray: 5 5
    class ALB optional
```
