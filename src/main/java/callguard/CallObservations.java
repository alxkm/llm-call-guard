package callguard;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * One Micrometer observation per logical call ({@value #CALL}) and a child observation per attempt ({@value #ATTEMPT}).
 * Every call ends in exactly one of success or failure, including a stream that is truncated, fails mid-way or is
 * abandoned by the caller, and the failure carries {@code error.type} = the {@link FailureKind} tag rather than a
 * wrapper exception's class.
 *
 * <p>Low-cardinality keys: {@code transport}, {@code mode} (sync | stream), {@code outcome} (success | failure),
 * {@code error.type} (none | kind), {@code attempts} (1 … maxAttempts) on the call; {@code status} (HTTP status or
 * none), {@code error.type}, {@code decision} (none | retry | stop) on the attempt.
 */
public final class CallObservations {
    public static final String CALL = "llm.guard.call";
    public static final String ATTEMPT = "llm.guard.attempt";

    private final ObservationRegistry registry;
    private final String transport;

    public CallObservations(ObservationRegistry registry, String transport) {
        this.registry = registry == null ? ObservationRegistry.NOOP : registry;
        this.transport = transport;
    }

    public Call start(boolean stream) {
        return new Call(stream ? "stream" : "sync");
    }

    /** One logical call. */
    public final class Call {
        private final Observation observation;
        private final String mode;
        private boolean stopped;

        private Call(String mode) {
            this.mode = mode;
            this.observation = Observation.createNotStarted(CALL, registry)
                    .lowCardinalityKeyValue("transport", transport)
                    .lowCardinalityKeyValue("mode", mode)
                    .start();
        }

        public Attempt attempt(int n) {
            return new Attempt(this, n);
        }

        public synchronized void success(int attempts) {
            if (stopped) {
                return;
            }
            stopped = true;
            observation.lowCardinalityKeyValue(KeyValue.of("outcome", "success"))
                    .lowCardinalityKeyValue("error.type", "none")
                    .lowCardinalityKeyValue("attempts", Integer.toString(attempts))
                    .stop();
        }

        public synchronized void failure(FailureKind kind, Throwable cause, int attempts) {
            if (stopped) {
                return;
            }
            stopped = true;
            observation.lowCardinalityKeyValue("outcome", "failure")
                    .lowCardinalityKeyValue("error.type", kind.tag())
                    .lowCardinalityKeyValue("attempts", Integer.toString(attempts))
                    .error(cause instanceof GuardException ? cause : new GuardException(kind, attempts, describe(cause), cause))
                    .stop();
        }

        private static String describe(Throwable cause) {
            return cause == null ? "no further detail" : cause.getMessage();
        }
    }

    /** One HTTP request within a call. */
    public final class Attempt {
        private final Observation observation;
        private final int n;

        private Attempt(Call call, int n) {
            this.n = n;
            this.observation = Observation.createNotStarted(ATTEMPT, registry)
                    .parentObservation(call.observation)
                    .lowCardinalityKeyValue("transport", transport)
                    .lowCardinalityKeyValue("mode", call.mode)
                    .start();
        }

        public void success(int status) {
            observation.lowCardinalityKeyValue("status", Integer.toString(status))
                    .lowCardinalityKeyValue("error.type", "none")
                    .lowCardinalityKeyValue("decision", "none")
                    .stop();
        }

        /** @param status the HTTP status, or -1 if there was no response */
        public void failure(int status, FailureKind kind, boolean retry, Throwable cause) {
            observation.lowCardinalityKeyValue("status", status < 0 ? "none" : Integer.toString(status))
                    .lowCardinalityKeyValue("error.type", kind.tag())
                    .lowCardinalityKeyValue("decision", retry ? "retry" : "stop")
                    .error(cause instanceof GuardException ? cause : new GuardException(kind, n, cause == null ? "status " + status : cause.getMessage(), cause))
                    .stop();
        }
    }
}
