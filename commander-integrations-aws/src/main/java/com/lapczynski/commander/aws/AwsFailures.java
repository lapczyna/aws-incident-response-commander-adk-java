package com.lapczynski.commander.aws;

import com.lapczynski.commander.application.signal.SignalSourceException;
import com.lapczynski.commander.domain.evidence.EvidenceGap;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * Translates AWS SDK failures into evidence gaps.
 *
 * <p>The distinction this makes is the one the whole investigation rests on: <strong>why</strong>
 * evidence is missing changes what a conclusion is worth. "The query returned nothing" supports an
 * inference; "we were denied permission to run the query" supports none at all. Collapsing both
 * into a generic error would let an investigation quietly treat an outage as an absence of
 * symptoms.
 *
 * <p>Message text is deliberately not passed through verbatim. AWS error messages can carry ARNs,
 * account ids and request context that end up in a model's prompt and in an incident report; the
 * error <em>code</em> is what an investigator needs, and it is safe.
 */
final class AwsFailures {

  private AwsFailures() {}

  /**
   * Maps an exception to a typed source failure.
   *
   * @param source a human-readable name for the thing being read, used in the message
   */
  static SignalSourceException translate(String source, Exception e) {
    if (e instanceof ApiCallTimeoutException || e instanceof ApiCallAttemptTimeoutException) {
      return new SignalSourceException(
          EvidenceGap.Reason.TIMEOUT,
          "%s did not answer within the configured deadline".formatted(source));
    }

    if (e instanceof AwsServiceException awsException) {
      String errorCode =
          awsException.awsErrorDetails() == null
              ? "Unknown"
              : awsException.awsErrorDetails().errorCode();

      // Permission problems are called out specifically. They are a configuration fault rather
      // than an incident symptom, and an investigation that cannot tell the two apart will report
      // a missing IAM statement as though the service were broken.
      if (isAccessDenied(errorCode, awsException.statusCode())) {
        return new SignalSourceException(
            EvidenceGap.Reason.ACCESS_DENIED,
            "%s refused the request (%s). This is a permissions problem, not a symptom of the "
                    .formatted(source, errorCode)
                + "incident under investigation.");
      }

      if (isThrottling(errorCode, awsException.statusCode())) {
        return new SignalSourceException(
            EvidenceGap.Reason.UPSTREAM_ERROR,
            "%s throttled the request (%s) and retries were exhausted"
                .formatted(source, errorCode));
      }

      return new SignalSourceException(
          EvidenceGap.Reason.UPSTREAM_ERROR,
          "%s returned %s (HTTP %d)".formatted(source, errorCode, awsException.statusCode()));
    }

    if (e instanceof SdkClientException) {
      // Never reached the service: DNS, TLS, connection refused.
      return new SignalSourceException(
          EvidenceGap.Reason.UPSTREAM_ERROR, "%s could not be reached".formatted(source));
    }

    return new SignalSourceException(
        EvidenceGap.Reason.UPSTREAM_ERROR,
        "%s failed unexpectedly (%s)".formatted(source, e.getClass().getSimpleName()));
  }

  private static boolean isAccessDenied(String errorCode, int statusCode) {
    return statusCode == 403
        || "AccessDenied".equals(errorCode)
        || "AccessDeniedException".equals(errorCode)
        || "UnauthorizedOperation".equals(errorCode);
  }

  private static boolean isThrottling(String errorCode, int statusCode) {
    return statusCode == 429
        || "Throttling".equals(errorCode)
        || "ThrottlingException".equals(errorCode)
        || "TooManyRequestsException".equals(errorCode)
        || "RequestLimitExceeded".equals(errorCode);
  }
}
