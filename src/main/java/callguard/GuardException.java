package callguard;

import java.io.IOException;

/**
 * The failure the guard reports when it ends a call itself. It extends {@link IOException} because the adapters
 * raise it from inside an HTTP client, where the frameworks expect I/O failures; {@link #kind()} says what actually
 * happened, and {@link #attempts()} how many requests were sent.
 */
public final class GuardException extends IOException {
    private final FailureKind kind;
    private final int attempts;

    public GuardException(FailureKind kind, int attempts, String message, Throwable cause) {
        super(kind.tag() + " after " + attempts + (attempts == 1 ? " attempt" : " attempts") + ": " + message, cause);
        this.kind = kind;
        this.attempts = attempts;
    }

    public FailureKind kind() {
        return kind;
    }

    public int attempts() {
        return attempts;
    }

    /** The guard's failure in a cause chain, if there is one. */
    public static java.util.Optional<GuardException> find(Throwable t) {
        int guard = 0;
        while (t != null && guard++ < 12) {
            if (t instanceof GuardException g) {
                return java.util.Optional.of(g);
            }
            t = t.getCause();
        }
        return java.util.Optional.empty();
    }
}
