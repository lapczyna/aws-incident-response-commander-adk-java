# Dependency version matrix

Every version below was **resolved by the build** (`./mvnw dependency:tree`) on 2026-09-08 and
verified to compile together on Java 25, not copied from documentation. Where a platform BOM manages
a version, the BOM wins and this table records what actually resolved — which is not always the
newest release on Maven Central.

## Toolchain

| Component | Version | Notes |
|---|---|---|
| Java | **25** (LTS) | `maven.compiler.release=25`, enforced by maven-enforcer. Verified with BellSoft Liberica 25.0.2. |
| Maven | **3.9.16** | Pinned by the wrapper. 4.0.0-rc-6 exists but is a release candidate and was rejected. |
| Maven Wrapper | **3.3.4** | `distributionType=only-script`, so **no `maven-wrapper.jar` is committed** — the repository stays binary-free. |

## Platform BOMs

| BOM | Version | Why this one |
|---|---|---|
| `org.springframework.boot:spring-boot-dependencies` | **4.1.1** | Latest GA. 4.0.x reaches end of support 2026-12-31; 4.2.0-M1 is a milestone. Imported **first**, so it wins for everything it manages. |
| `org.springframework.ai:spring-ai-bom` | **2.0.1** | Documented as supporting Spring Boot 4.0.x and 4.1.x, and aligned with 4.1.0 dependencies. |
| `software.amazon.awssdk:bom` | **2.54.13** | Latest GA. |

## Direct dependencies

| Artifact | Resolved | Managed by | Notes |
|---|---|---|---|
| `com.google.adk:google-adk` | **1.9.0** | pinned here | Pre-2.0 and moving fast — deliberately pinned, never auto-bumped. See ADR-0005. |
| `com.google.adk:google-adk-spring-ai` | **1.9.0** | pinned here | Bridges Spring AI `ChatModel` onto ADK's `BaseLlm`. Powers the Ollama and Bedrock profiles. |
| `org.springframework.ai:spring-ai-ollama` | 2.0.1 | spring-ai-bom | `optional` — only on the classpath for the local profile. |
| `org.springframework.ai:spring-ai-bedrock-converse` | 2.0.1 | spring-ai-bom | `optional` — Bedrock is off by default. |
| `org.flywaydb:flyway-core` | **12.4.0** | spring-boot | Maven Central's latest is 13.5.0, but Boot 4.1.1 manages 12.4.0. **We follow the BOM**: matching Boot's tested combination is worth more than a major-version bump. |
| `org.postgresql:postgresql` | 42.7.13 | spring-boot | |
| `org.testcontainers:*` | **2.0.5** | spring-boot | Boot 4.1.1 imports `testcontainers-bom` 2.0.5. **Testcontainers 2.0 renamed every module** — the coordinates are now `testcontainers-postgresql`, `testcontainers-junit-jupiter`, `testcontainers-ollama`, not the 1.x `postgresql` / `junit-jupiter`. |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 3.1.1 | pinned here | springdoc 3.x is the Spring Boot 4 line (2.x targets Boot 3). |
| `com.tngtech.archunit:archunit-junit5` | 1.5.0 | pinned here | |
| `org.wiremock:wiremock` | 3.13.2 | pinned here | 4.0.0 is still beta; 3.13.2 is the newest GA. |
| `net.logstash.logback:logstash-logback-encoder` | 9.0 | pinned here | Structured JSON logging. |
| `io.micrometer:micrometer-core` | 1.17.1 | spring-boot | |
| `io.opentelemetry:opentelemetry-api` | 1.62.0 | spring-boot | |

## Transitive versions worth knowing

| Artifact | Resolved | Comes from | Notes |
|---|---|---|---|
| `io.reactivex.rxjava3:rxjava` | 3.1.12 | ADK | ADK's `Runner` returns `Flowable<Event>`. This is why a Reactor bridge is needed (ADR-0008). |
| `com.google.genai:google-genai` | 1.58.0 | ADK | Native Gemini client. |
| `com.anthropic:anthropic-java` | 2.15.0 | ADK | Backs ADK's `models.Claude`. Central has 2.61.0; we take ADK's tested version. |
| `com.google.guava:guava` | 33.0.0-jre (compile) / 33.5.0-jre (test) | ADK / WireMock | Harmless divergence: the two never share a classpath. WireMock's copy is test-scoped and only in commander-integrations-aws, which does not depend on ADK. Not force-pinned, because forcing Guava above what ADK was tested against is the riskier choice. |

## Spring Boot 4 moved Flyway auto-configuration out of `spring-boot-autoconfigure`

Worth writing down because it fails silently. In Boot 3, putting `flyway-core` on the classpath was
enough — auto-configuration picked it up and ran migrations at startup. In Boot 4, auto-configuration
was split into per-technology modules, and Flyway's lives in `spring-boot-flyway`, surfaced through
`spring-boot-starter-flyway`.

With only `flyway-core` declared, the application starts perfectly happily, logs nothing about
Flyway, and leaves the schema uncreated. The first symptom is `relation "incidents" does not exist`
from unrelated code. The fix:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-flyway</artifactId>
</dependency>
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-database-postgresql</artifactId>
</dependency>
```

This project hit it during Phase 1. The same applies to other technologies whose auto-configuration
moved in Boot 4 — when something that "just worked" in Boot 3 produces no log output at all, check
whether it now needs its own starter.

## Jackson 2 and Jackson 3 coexist — by design, not by accident

This surprised me enough to be worth writing down. Spring Boot 4 moved to **Jackson 3**, which
changed its Maven coordinates from `com.fasterxml.jackson.*` to `tools.jackson.*`. ADK 1.9.0 still
uses **Jackson 2**. Because the groupIds differ, both resolve and load side by side:

```
com.fasterxml.jackson.core:jackson-databind  2.21.5   <- ADK
tools.jackson.core:jackson-databind          3.1.5    <- Spring Boot 4
```

There is no shading, no exclusion and no conflict to resolve. The one rule to follow: **never mix
the two in a single class.** Code that serialises ADK types uses Jackson 2; code that serialises
REST payloads uses Jackson 3 through Spring's `ObjectMapper`. Conversion happens at the module
boundary, in commander-adk.

## Reproducing this table

```bash
./mvnw dependency:tree -Dscope=test
```

## Update policy

ADK, Spring Boot, Spring AI and the AWS SDK are pinned as explicit properties in the root `pom.xml`.
Bumping any of them is a deliberate change with a full `./mvnw verify` behind it — ADK in particular
is pre-2.0, and its `ResumabilityConfig` (which this project depends on for approval resumption) is
already deprecated. See ADR-0005.
