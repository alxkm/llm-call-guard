package callguard;

import java.time.Duration;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * The single place where the guard decides whether to make another attempt and how long to wait. Every transport
 * calls it, so the contract is the same whichever framework sits above.
 */
public final class RetryRules {
    /** Another attempt after {@code delay}, or none. */
    public record Verdict(boolean retry, Duration delay) {
        static final Verdict STOP = new Verdict(false, Duration.ZERO);
    }

    private final Policy policy;
    private final RandomGenerator random;

    public RetryRules(Policy policy, RandomGenerator random) {
        this.policy = policy;
        this.random = random;
    }

    public Policy policy() {
        return policy;
    }

    /**
     * @param attempt the attempt that just failed, from 1
     * @param kind why it failed
     * @param delivered whether any part of the response reached the caller (a new attempt would then duplicate it)
     * @param retryAfter the delay the provider asked for, if any
     * @param elapsed time since the call started
     * @param nextAttemptTimeout the timeout the next attempt would get
     */
    public Verdict afterFailure(int attempt, FailureKind kind, boolean delivered, Optional<Duration> retryAfter, Duration elapsed,
            Duration nextAttemptTimeout) {
        if (!kind.isTransient() || delivered || attempt >= policy.maxAttempts()) {
            return Verdict.STOP;
        }
        Duration delay;
        if (retryAfter.isPresent()) {
            if (retryAfter.get().compareTo(policy.retryAfterCap()) > 0) {
                return Verdict.STOP; // waiting less than asked only produces another refusal
            }
            delay = retryAfter.get();
        } else {
            delay = backoff(attempt);
        }
        if (elapsed.plus(delay).plus(nextAttemptTimeout).compareTo(policy.totalBudget()) > 0) {
            return Verdict.STOP;
        }
        return new Verdict(true, delay);
    }

    /** base·2^(attempt−1), capped; the fraction {@code jitter} of it is drawn uniformly. */
    Duration backoff(int attempt) {
        long base = policy.backoffBase().toMillis();
        long cap = policy.backoffCap().toMillis();
        long d = Math.min(cap, base << Math.min(attempt - 1, 20));
        long fixed = Math.round(d * (1 - policy.jitter()));
        long drawn = d - fixed > 0 ? random.nextLong(d - fixed + 1) : 0;
        return Duration.ofMillis(fixed + drawn);
    }
}
