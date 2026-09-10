package com.lapczynski.commander.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The seam between the application and the infrastructure that deploys it.
 *
 * <p>Everything here is a fact that is stated in two files and has to agree in both. The tag the
 * policy engine requires is the tag Terraform attaches. The environment the policy engine compares
 * against is the environment the deployment sets. The placeholders in the IAM documents are the
 * ones Terraform substitutes. None of these are checked by the compiler, by ArchUnit, or by {@code
 * terraform validate} — they fail at deployment time, or worse, they do not fail at all and quietly
 * disable a control.
 *
 * <p>The IAM placeholder check earns its place on its own. {@code templatefile()} fails on an
 * undefined placeholder, which means adding {@code $&#123;cluster&#125;} to a policy document is a
 * change that passes every test in this repository and then breaks an apply halfway through
 * creating a VPC. Here it breaks in seconds instead.
 *
 * <p>These are text assertions rather than parsed HCL or JSON, deliberately. A parser would need a
 * dependency for each format and would tie the test to a grammar; the substrings being matched are
 * the exact strings a reader would search for, and a reformatting that breaks the match is a
 * reformatting worth noticing.
 */
class DeploymentContractTest {

  private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();
  private static final Path IAM = REPO_ROOT.resolve("infra/iam");
  private static final Path TERRAFORM = REPO_ROOT.resolve("infra/terraform");
  private static final Path APPLICATION_YAML =
      REPO_ROOT.resolve("commander-api/src/main/resources/application.yaml");

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-z_]+)}");

  private static String read(Path path) throws IOException {
    assertThat(path).as("%s must exist; the deployment is part of the repository", path).exists();
    return Files.readString(path);
  }

  private static Set<String> matches(Pattern pattern, String text) {
    return pattern.matcher(text).results().map(m -> m.group(1)).collect(Collectors.toSet());
  }

  /** The value of a Terraform variable's {@code default}, read out of variables.tf. */
  private static String terraformDefault(String tf, String variable) {
    Matcher matcher =
        Pattern.compile(
                "variable\\s+\""
                    + Pattern.quote(variable)
                    + "\"\\s*\\{.*?default\\s*=\\s*([^\\n]+)",
                Pattern.DOTALL)
            .matcher(tf);

    assertThat(matcher.find())
        .as("variable %s must exist and declare a default", variable)
        .isTrue();
    return matcher.group(1).trim().replace("\"", "");
  }

  @Test
  @DisplayName("every placeholder in the IAM documents is one Terraform substitutes")
  void iamPlaceholdersAreAllSupplied() throws IOException {
    // The authority is the map Terraform actually passes, read from iam.tf rather than restated
    // here. A placeholder added to a policy without a matching entry in that map fails
    // templatefile() at apply time; a variable added to the map and never used is harmless.
    String iamTf = read(TERRAFORM.resolve("iam.tf"));
    Matcher block =
        Pattern.compile("iam_template_vars\\s*=\\s*\\{(.*?)\\n  }", Pattern.DOTALL).matcher(iamTf);
    assertThat(block.find()).as("iam.tf must define iam_template_vars").isTrue();

    Set<String> supplied = matches(Pattern.compile("(?m)^\\s*([a-z_]+)\\s*="), block.group(1));
    assertThat(supplied)
        .as("the substitution map is the contract; if this changes, so does the check below")
        .containsExactlyInAnyOrder("region", "account_id", "project", "project_tag", "environment");

    for (String policy : new String[] {"commander-task-role-read", "commander-task-role-actions"}) {
      Set<String> used = matches(PLACEHOLDER, read(IAM.resolve(policy + ".json")));

      assertThat(used)
          .as(
              "%s.json uses placeholders Terraform does not supply, so templatefile() would fail "
                  + "during apply",
              policy)
          .isSubsetOf(supplied);
    }
  }

  @Test
  @DisplayName("the tag the policy engine requires is the tag Terraform attaches")
  void requiredTagMatchesTheDeployedTag() throws IOException {
    String yaml = read(APPLICATION_YAML);
    String tf = read(TERRAFORM.resolve("variables.tf"));

    Matcher required =
        Pattern.compile("required-tag:\\s*\\n\\s*key:\\s*(\\S+)\\s*\\n\\s*value:\\s*(\\S+)")
            .matcher(yaml);
    assertThat(required.find()).as("application.yaml must declare the required tag").isTrue();

    // Three independent layers enforce this tag: the policy engine, the executor reading live AWS
    // state, and the IAM condition. All three compare against a value that is configured, and the
    // resources only carry it because Terraform put it there. If these two drift apart, every one
    // of those layers refuses every action, and the failure looks like a policy bug.
    assertThat(terraformDefault(tf, "project_tag"))
        .as("commander.policy.required-tag.value and the project_tag variable must agree")
        .isEqualTo(required.group(2));

    assertThat(required.group(1))
        .as("the IAM condition keys are aws:ResourceTag/Project and aws:ResourceTag/Environment")
        .isEqualTo("Project");
  }

  @Test
  @DisplayName("the environment the policy engine compares against is the one deployed")
  void environmentMatches() throws IOException {
    String yaml = read(APPLICATION_YAML);
    String tf = read(TERRAFORM.resolve("variables.tf"));

    Matcher configured =
        Pattern.compile("environment:\\s*\\$\\{COMMANDER_ENVIRONMENT:([^}]*)}").matcher(yaml);
    assertThat(configured.find()).as("application.yaml must default the environment").isTrue();

    assertThat(terraformDefault(tf, "environment")).isEqualTo(configured.group(1));
  }

  @Test
  @DisplayName("the deployment defaults are the safe ones")
  void deploymentCannotActWithoutBeingToldTo() throws IOException {
    String tf = read(TERRAFORM.resolve("variables.tf"));

    // The same three switches the application defaults safely, defaulted safely again one layer
    // out. This is not redundancy for its own sake: a deployment inherits nothing from
    // application.yaml, and a tfvars file is where "just for the demo" changes get made.
    assertThat(terraformDefault(tf, "enable_remediation_actions"))
        .as("the task role must not carry ecs:UpdateService unless someone asked for it")
        .isEqualTo("false");
    assertThat(terraformDefault(tf, "dry_run"))
        .as("approved actions must be simulated unless someone asked for them to be real")
        .isEqualTo("true");
    assertThat(terraformDefault(tf, "signal_source"))
        .as(
            "the simulator makes no AWS calls, which is the right default for both cost and blast radius")
        .isEqualTo("simulator");
    assertThat(terraformDefault(tf, "model_profile"))
        .as("no default that reaches a paid provider")
        .isEqualTo("fake");
    assertThat(terraformDefault(tf, "admin_cidr"))
        .as("the stack comes up reachable by nothing; opening it is an explicit act")
        .isEmpty();
  }

  @Test
  @DisplayName("the log groups match the pattern the read policy is scoped to")
  void logGroupsAreWithinTheGrantedScope() throws IOException {
    String readPolicy = read(IAM.resolve("commander-task-role-read.json"));
    String locals = read(TERRAFORM.resolve("locals.tf"));

    assertThat(readPolicy)
        .as("logs:FilterLogEvents is scoped by log-group ARN, not by cluster")
        .contains("log-group:/aws/ecs/${project}-*");

    // local.name is var.project, so a log group outside /aws/ecs/<project>- is a log group the
    // Commander is not permitted to read. The symptom would be an investigation that reports a log
    // evidence gap on every incident, which reads like a broken adapter rather than a policy scope.
    Set<String> groups =
        matches(Pattern.compile("(?m)^\\s*\\w+_log_group\\s*=\\s*\"([^\"]+)\""), locals);

    assertThat(groups).as("locals.tf must define the log group names").isNotEmpty();
    assertThat(groups).allSatisfy(group -> assertThat(group).startsWith("/aws/ecs/${local.name}-"));
  }
}
