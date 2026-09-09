/**
 * Read-only AWS adapters (CloudWatch, Logs Insights, alarms, ECS, CloudTrail) and the narrowly
 * allowlisted action adapters.
 *
 * <p>Every action adapter is dry-run by default and refuses any resource outside the configured
 * account, region, tag set and ARN allowlist.
 */
package com.lapczynski.commander.aws;
