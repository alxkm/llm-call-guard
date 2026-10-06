package callguard.jdk;

import callguard.CallObservations;
import callguard.ChunkSource;
import callguard.FailureKind;
import callguard.GuardException;
import callguard.GuardedSseStream;
import callguard.Policy;
import callguard.RetryAfter;
import callguard.RetryRules;
import callguard.WireCheck;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.random.RandomGenerator;

/**
 * The guard over {@link java.net.http.HttpClient}: usable directly, without a framework, and the base of the
 * LangChain4j adapter. A call returns either a complete, checked answer, or the provider's final error response
 * (status and body, for the caller to map to its own exception), or throws {@link GuardException}.
 */
public final class JdkTransport {

    public record Request(String method, URI uri, Map<String, List<String>> headers, byte[] body) {
    }

    /** A final response: 2xx with a checked body, or the provider's last error. */
    public record Response(int status, Map<String, List<String>> headers, byte[] body) {
        public boolean ok() {
            return status / 100 == 2;
        }
    }

    /** A final streaming response: 2xx with a guarded event stream, or the provider's last error with its body. */
    public record StreamResponse(int status, Map<String, List<String>> headers, byte[] errorBody, InputStream events) {
        public boolean ok() {
            return status / 100 == 2;
        }
    }

    private static final Set<String> RESTRICTED = Set.of("host", "content-length", "connection", "expect", "upgrade", "accept-encoding");

    private final HttpClient client;
    private final RetryRules rules;
    private final CallObservations observations;

    public JdkTransport(HttpClient client, Policy policy, ObservationRegistry registry, String transportName, RandomGenerator random) {
        this.client = client;
        this.rules = new RetryRules(policy, random);
        this.observations = new CallObservations(registry, transportName);
    }

    public JdkTransport(Policy policy, ObservationRegistry registry) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), policy, registry, "jdk", RandomGenerator.getDefault());
    }

    public Policy policy() {
        return rules.policy();
    }

    public Response send(Request r) throws GuardException {
        Policy p = rules.policy();
        long t0 = System.nanoTime();
        CallObservations.Call call = observations.start(false);
        for (int attempt = 1; ; attempt++) {
            Duration timeout = min(p.responseTimeout(), remaining(p, t0));
            CallObservations.Attempt obs = call.attempt(attempt);
            HttpResponse<byte[]> resp = null;
            FailureKind kind;
            Throwable cause = null;
            CompletableFuture<HttpResponse<byte[]>> f = client.sendAsync(build(r, timeout), HttpResponse.BodyHandlers.ofByteArray());
            try {
                resp = f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (resp.statusCode() / 100 == 2) {
                    FailureKind bad = WireCheck.checkBody(resp.body());
                    if (bad == null) {
                        obs.success(resp.statusCode());
                        call.success(attempt);
                        return new Response(resp.statusCode(), resp.headers().map(), resp.body());
                    }
                    GuardException g = new GuardException(bad, attempt, "status " + resp.statusCode() + " with a body that is not a complete answer", null);
                    obs.failure(resp.statusCode(), bad, false, g);
                    call.failure(bad, g, attempt);
                    throw g;
                }
                kind = FailureKind.ofStatus(resp.statusCode());
            } catch (TimeoutException e) {
                f.cancel(true);
                kind = FailureKind.NO_RESPONSE;
                cause = e;
            } catch (ExecutionException e) {
                cause = e.getCause();
                kind = cause instanceof HttpTimeoutException ? FailureKind.NO_RESPONSE : FailureKind.CONNECTION;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                f.cancel(true);
                GuardException g = new GuardException(FailureKind.CANCELLED, attempt, "interrupted", e);
                obs.failure(-1, FailureKind.CANCELLED, false, g);
                call.failure(FailureKind.CANCELLED, g, attempt);
                throw g;
            }
            Optional<Duration> retryAfter = resp == null ? Optional.empty() : RetryAfter.parse(resp.headers().map());
            RetryRules.Verdict v = rules.afterFailure(attempt, kind, false, retryAfter, elapsed(t0), p.responseTimeout());
            obs.failure(resp == null ? -1 : resp.statusCode(), kind, v.retry(), cause);
            if (!v.retry()) {
                if (resp != null) {
                    call.failure(kind, null, attempt);
                    return new Response(resp.statusCode(), resp.headers().map(), resp.body());
                }
                GuardException g = new GuardException(kind, attempt, cause == null ? "no response" : String.valueOf(cause.getMessage()), cause);
                call.failure(kind, g, attempt);
                throw g;
            }
            sleep(v.delay(), call, attempt);
        }
    }

    public StreamResponse stream(Request r) throws GuardException {
        Policy p = rules.policy();
        long t0 = System.nanoTime();
        CallObservations.Call call = observations.start(true);
        for (int attempt = 1; ; attempt++) {
            Duration timeout = min(p.firstEventTimeout(), remaining(p, t0));
            long attemptStart = System.nanoTime();
            CallObservations.Attempt obs = call.attempt(attempt);
            HttpResponse<InputStream> resp = null;
            int status = -1;
            byte[] errorBody = null;
            FailureKind kind;
            Throwable cause = null;
            CompletableFuture<HttpResponse<InputStream>> f = client.sendAsync(build(r, timeout), HttpResponse.BodyHandlers.ofInputStream());
            try {
                resp = f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                status = resp.statusCode();
                if (resp.statusCode() / 100 == 2) {
                    PumpSource src = new PumpSource(resp.body());
                    GuardedSseStream s = new GuardedSseStream(src, p.idleTimeout(), attempt);
                    try {
                        Duration left = timeout.minus(elapsed(attemptStart));
                        s.awaitFirstEvent(left.isNegative() || left.isZero() ? Duration.ofMillis(1) : left);
                        obs.success(resp.statusCode());
                        final int n = attempt;
                        s.arm((failure, c, events) -> {
                            if (failure == null) {
                                call.success(n);
                            } else {
                                call.failure(failure, c, n);
                            }
                        });
                        return new StreamResponse(resp.statusCode(), resp.headers().map(), null, s);
                    } catch (GuardException g) {
                        s.close();
                        obs.failure(resp.statusCode(), g.kind(), false, g);
                        call.failure(g.kind(), g, attempt);
                        throw g;
                    } catch (ChunkSource.Silence e) {
                        s.close();
                        kind = FailureKind.NO_RESPONSE;
                        cause = e;
                        resp = null;
                    } catch (IOException e) {
                        s.close();
                        kind = FailureKind.CONNECTION;
                        cause = e;
                        resp = null;
                    }
                } else {
                    kind = FailureKind.ofStatus(resp.statusCode());
                    try (InputStream in = resp.body()) {
                        errorBody = in.readAllBytes();
                    } catch (IOException e) {
                        errorBody = new byte[0];
                    }
                }
            } catch (TimeoutException e) {
                f.cancel(true);
                kind = FailureKind.NO_RESPONSE;
                cause = e;
            } catch (ExecutionException e) {
                cause = e.getCause();
                kind = cause instanceof HttpTimeoutException ? FailureKind.NO_RESPONSE : FailureKind.CONNECTION;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                f.cancel(true);
                GuardException g = new GuardException(FailureKind.CANCELLED, attempt, "interrupted", e);
                obs.failure(-1, FailureKind.CANCELLED, false, g);
                call.failure(FailureKind.CANCELLED, g, attempt);
                throw g;
            }
            Optional<Duration> retryAfter = resp == null ? Optional.empty() : RetryAfter.parse(resp.headers().map());
            RetryRules.Verdict v = rules.afterFailure(attempt, kind, false, retryAfter, elapsed(t0), p.firstEventTimeout());
            obs.failure(status, kind, v.retry(), cause);
            if (!v.retry()) {
                if (resp != null) {
                    call.failure(kind, null, attempt);
                    return new StreamResponse(resp.statusCode(), resp.headers().map(), errorBody, null);
                }
                GuardException g = new GuardException(kind, attempt, cause == null ? "no response" : String.valueOf(cause.getMessage()), cause);
                call.failure(kind, g, attempt);
                throw g;
            }
            sleep(v.delay(), call, attempt);
        }
    }

    private static HttpRequest build(Request r, Duration timeout) {
        HttpRequest.Builder b = HttpRequest.newBuilder(r.uri()).timeout(timeout)
                .method(r.method(), r.body() == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(r.body()));
        r.headers().forEach((k, vs) -> {
            if (!RESTRICTED.contains(k.toLowerCase(Locale.ROOT))) {
                vs.forEach(v -> b.header(k, v));
            }
        });
        return b.build();
    }

    private static Duration remaining(Policy p, long t0) {
        Duration left = p.totalBudget().minus(elapsed(t0));
        return left.isNegative() || left.isZero() ? Duration.ofMillis(1) : left;
    }

    private static Duration elapsed(long t0) {
        return Duration.ofNanos(System.nanoTime() - t0);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static void sleep(Duration d, CallObservations.Call call, int attempt) throws GuardException {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            GuardException g = new GuardException(FailureKind.CANCELLED, attempt, "interrupted while waiting to retry", e);
            call.failure(FailureKind.CANCELLED, g, attempt);
            throw g;
        }
    }

    /**
     * Reads the JDK body stream on a virtual thread into a queue, so that the consumer can wait with a timeout: the
     * JDK client has no read timeout for a body once the headers have arrived.
     */
    static final class PumpSource implements ChunkSource {
        private static final Object EOF = new Object();
        private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private final InputStream in;
        private final Thread pump;
        private boolean done;

        PumpSource(InputStream in) {
            this.in = in;
            this.pump = Thread.ofVirtual().name("callguard-pump").start(() -> {
                byte[] buf = new byte[8192];
                try {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (n > 0) {
                            queue.add(java.util.Arrays.copyOf(buf, n));
                        }
                    }
                    queue.add(EOF);
                } catch (IOException e) {
                    queue.add(e);
                }
            });
        }

        @Override
        public byte[] read(Duration timeout) throws IOException {
            if (done) {
                return null;
            }
            Object o;
            try {
                o = queue.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("interrupted");
            }
            if (o == null) {
                throw new Silence(timeout);
            }
            if (o == EOF) {
                done = true;
                return null;
            }
            if (o instanceof IOException e) {
                done = true;
                throw e;
            }
            return (byte[]) o;
        }

        @Override
        public void close() {
            done = true;
            try {
                in.close();
            } catch (IOException ignored) {
                // closing a body that already failed
            }
            pump.interrupt();
        }
    }
}
