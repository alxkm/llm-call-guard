package callguard;

import java.time.Duration;
import java.util.Objects;

/**
 * The reliability contract applied to every call. All limits are per logical call, which may span several attempts.
 *
 * @param maxAttempts attempts including the first; 1 disables retries
 * @param responseTimeout for a non-streaming attempt: the longest wait for the complete response
 * @param firstEventTimeout for a streaming attempt: the longest wait for the response headers and the first event
 * @param idleTimeout for a streaming attempt: the longest silence between two events once the stream has started
 * @param totalBudget the longest a logical call may take; no new attempt starts if its timeout would exceed it
 * @param backoffBase delay before the second attempt when the provider gives no Retry-After; doubled per attempt
 * @param backoffCap upper bound of the computed delay
 * @param retryAfterCap the longest Retry-After the guard will wait; a longer one ends the call at once
 * @param jitter fraction of the computed delay that is randomised, in [0, 1]
 */
public record Policy(int maxAttempts, Duration responseTimeout, Duration firstEventTimeout, Duration idleTimeout, Duration totalBudget,
        Duration backoffBase, Duration backoffCap, Duration retryAfterCap, double jitter) {

    public Policy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        Objects.requireNonNull(responseTimeout);
        Objects.requireNonNull(firstEventTimeout);
        Objects.requireNonNull(idleTimeout);
        Objects.requireNonNull(totalBudget);
        Objects.requireNonNull(backoffBase);
        Objects.requireNonNull(backoffCap);
        Objects.requireNonNull(retryAfterCap);
        if (jitter < 0 || jitter > 1) {
            throw new IllegalArgumentException("jitter must be in [0, 1]");
        }
    }

    /** Defaults for an interactive call: 3 attempts, 60 s per response, 30 s to the first event, 30 s idle, 5 min in all. */
    public static Policy defaults() {
        return new Policy(3, Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofSeconds(30), Duration.ofMinutes(5),
                Duration.ofMillis(500), Duration.ofSeconds(8), Duration.ofSeconds(30), 0.5);
    }

    public Policy withMaxAttempts(int n) {
        return new Policy(n, responseTimeout, firstEventTimeout, idleTimeout, totalBudget, backoffBase, backoffCap, retryAfterCap, jitter);
    }

    public Policy withTimeouts(Duration response, Duration firstEvent, Duration idle, Duration total) {
        return new Policy(maxAttempts, response, firstEvent, idle, total, backoffBase, backoffCap, retryAfterCap, jitter);
    }

    public Policy withBackoff(Duration base, Duration cap, Duration retryAfterMax, double jitterFraction) {
        return new Policy(maxAttempts, responseTimeout, firstEventTimeout, idleTimeout, totalBudget, base, cap, retryAfterMax, jitterFraction);
    }
}
