package callguard;

import java.util.Locale;

/**
 * Why an attempt or a call failed. The kind decides whether another attempt may be made and is the value of the
 * {@code error.type} key on the guard's observations.
 */
public enum FailureKind {
    /** 429. Transient: the provider asks the client to slow down. */
    RATE_LIMITED(true),
    /** 5xx. Transient. */
    SERVER_ERROR(true),
    /** 408. Transient. */
    REQUEST_TIMEOUT(true),
    /** The connection could not be established or broke before any response byte. Transient. */
    CONNECTION(true),
    /** No response (non-streaming) or no first event (streaming) within the attempt timeout. Transient. */
    NO_RESPONSE(true),
    /** 401 or 403. Permanent: the same request will fail again. */
    AUTHENTICATION(false),
    /** Other 4xx. Permanent. */
    CLIENT_ERROR(false),
    /** 2xx whose body is not a well-formed response. Permanent: the provider has processed the request. */
    MALFORMED_RESPONSE(false),
    /** 2xx with a well-formed body that carries no answer (an empty {@code choices} array). Permanent. */
    EMPTY_RESPONSE(false),
    /** The stream ended without a terminal event: the answer is incomplete. */
    STREAM_TRUNCATED(false),
    /** The stream carried an error event. */
    STREAM_ERROR_EVENT(false),
    /** The stream fell silent for longer than the idle timeout. */
    STREAM_IDLE(false),
    /** The caller closed the stream before its end. Not a provider failure; recorded so the call is not counted as a success. */
    CANCELLED(false);

    private final boolean transientFailure;

    FailureKind(boolean transientFailure) {
        this.transientFailure = transientFailure;
    }

    /** Whether a new attempt can succeed where this one failed, provided nothing was delivered to the caller yet. */
    public boolean isTransient() {
        return transientFailure;
    }

    /** The kind for an HTTP status that is not 2xx. */
    public static FailureKind ofStatus(int status) {
        if (status == 429) {
            return RATE_LIMITED;
        }
        if (status == 408) {
            return REQUEST_TIMEOUT;
        }
        if (status == 401 || status == 403) {
            return AUTHENTICATION;
        }
        if (status >= 500) {
            return SERVER_ERROR;
        }
        return CLIENT_ERROR;
    }

    /** The tag value: lower case with dashes. */
    public String tag() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
