package callguard.okhttp;

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
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.Okio;

/**
 * The guard as an OkHttp application interceptor. It is what the Spring AI adapter installs (through
 * {@code OpenAiChatModel.builder().httpClientBuilderCustomizer(b -> b.interceptor(...))}), and it works for any
 * other OkHttp-based client of an OpenAI-compatible endpoint. The client's own retries must be off
 * ({@code maxRetries = 0}) and its call timeout at least the policy's total budget, so that the guard alone decides.
 *
 * <p>Final error responses are returned unchanged, so the client maps them to its own exception types; failures the
 * guard detects itself (no response, a malformed or empty answer, a truncated stream, an error event, silence) are
 * thrown as {@link GuardException}.
 */
public final class GuardInterceptor implements Interceptor {
    private final RetryRules rules;
    private final CallObservations observations;

    public GuardInterceptor(Policy policy, ObservationRegistry registry, String transportName, RandomGenerator random) {
        this.rules = new RetryRules(policy, random);
        this.observations = new CallObservations(registry, transportName);
    }

    public GuardInterceptor(Policy policy, ObservationRegistry registry) {
        this(policy, registry, "okhttp", RandomGenerator.getDefault());
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        boolean stream = isStreaming(request);
        Policy p = rules.policy();
        long t0 = System.nanoTime();
        CallObservations.Call call = observations.start(stream);
        for (int attempt = 1; ; attempt++) {
            Duration timeout = min(stream ? p.firstEventTimeout() : p.responseTimeout(), remaining(p, t0));
            int ms = (int) Math.max(1, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
            CallObservations.Attempt obs = call.attempt(attempt);
            Response resp = null;
            int status = -1;
            FailureKind kind;
            Throwable cause = null;
            try {
                resp = chain.withReadTimeout(ms, TimeUnit.MILLISECONDS).withWriteTimeout(ms, TimeUnit.MILLISECONDS).proceed(request);
                status = resp.code();
                if (resp.isSuccessful()) {
                    if (!stream) {
                        byte[] body = resp.body() == null ? new byte[0] : resp.body().bytes();
                        FailureKind bad = WireCheck.checkBody(body);
                        if (bad != null) {
                            GuardException g = new GuardException(bad, attempt, "status " + status + " with a body that is not a complete answer", null);
                            obs.failure(status, bad, false, g);
                            call.failure(bad, g, attempt);
                            throw g;
                        }
                        obs.success(status);
                        call.success(attempt);
                        MediaType type = resp.body() == null ? null : resp.body().contentType();
                        return resp.newBuilder().body(ResponseBody.create(body, type)).build();
                    }
                    GuardedSseStream s = new GuardedSseStream(new OkSource(resp.body().source(), resp), p.idleTimeout(), attempt);
                    try {
                        s.awaitFirstEvent(timeout);
                    } catch (GuardException g) {
                        s.close();
                        obs.failure(status, g.kind(), false, g);
                        call.failure(g.kind(), g, attempt);
                        throw g;
                    } catch (ChunkSource.Silence | SocketTimeoutException e) {
                        s.close();
                        throw new FirstEventMissing(FailureKind.NO_RESPONSE, e);
                    } catch (IOException e) {
                        s.close();
                        throw new FirstEventMissing(FailureKind.CONNECTION, e);
                    }
                    obs.success(status);
                    final int n = attempt;
                    s.arm((failure, c, events) -> {
                        if (failure == null) {
                            call.success(n);
                        } else {
                            call.failure(failure, c, n);
                        }
                    });
                    MediaType type = resp.body().contentType();
                    BufferedSource guarded = Okio.buffer(Okio.source(s));
                    return resp.newBuilder().body(ResponseBody.create(guarded, type, -1)).build();
                }
                kind = FailureKind.ofStatus(status);
            } catch (GuardException g) {
                throw g;
            } catch (FirstEventMissing e) {
                kind = e.kind;
                cause = e.getCause();
                resp = null;
            } catch (SocketTimeoutException e) {
                kind = FailureKind.NO_RESPONSE;
                cause = e;
                closeQuietly(resp);
                resp = null;
            } catch (InterruptedIOException e) {
                if (chain.call().isCanceled()) {
                    call.failure(FailureKind.CANCELLED, e, attempt);
                    throw e;
                }
                kind = FailureKind.NO_RESPONSE;
                cause = e;
                closeQuietly(resp);
                resp = null;
            } catch (IOException e) {
                if (chain.call().isCanceled()) {
                    call.failure(FailureKind.CANCELLED, e, attempt);
                    throw e;
                }
                kind = FailureKind.CONNECTION;
                cause = e;
                closeQuietly(resp);
                resp = null;
            }
            Optional<Duration> retryAfter = resp == null ? Optional.empty() : RetryAfter.parse(resp.headers().toMultimap());
            RetryRules.Verdict v = rules.afterFailure(attempt, kind, false, retryAfter, elapsed(t0), stream ? p.firstEventTimeout() : p.responseTimeout());
            obs.failure(status, kind, v.retry(), cause);
            if (!v.retry()) {
                if (resp != null) {
                    call.failure(kind, null, attempt);
                    return resp; // the provider's own error, for the client to map
                }
                GuardException g = new GuardException(kind, attempt, cause == null ? "no response" : String.valueOf(cause.getMessage()), cause);
                call.failure(kind, g, attempt);
                throw g;
            }
            closeQuietly(resp);
            try {
                Thread.sleep(v.delay().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                GuardException g = new GuardException(FailureKind.CANCELLED, attempt, "interrupted while waiting to retry", e);
                call.failure(FailureKind.CANCELLED, g, attempt);
                throw g;
            }
        }
    }

    /** {@code "stream": true} in a JSON request body. */
    static boolean isStreaming(Request request) throws IOException {
        if (request.body() == null) {
            return false;
        }
        Buffer b = new Buffer();
        request.body().writeTo(b);
        return b.readString(StandardCharsets.UTF_8).replace(" ", "").replace("\n", "").contains("\"stream\":true");
    }

    private static void closeQuietly(Response r) {
        if (r != null) {
            r.close();
        }
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

    private static final class FirstEventMissing extends IOException {
        final FailureKind kind;

        FirstEventMissing(FailureKind kind, Throwable cause) {
            super(cause);
            this.kind = kind;
        }
    }

    /** The response body as a {@link ChunkSource}; the per-read timeout is set on the socket's timeout before each read. */
    static final class OkSource implements ChunkSource {
        private final BufferedSource source;
        private final Response response;

        OkSource(BufferedSource source, Response response) {
            this.source = source;
            this.response = response;
        }

        @Override
        public byte[] read(Duration timeout) throws IOException {
            source.timeout().timeout(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            Buffer b = new Buffer();
            long n;
            try {
                n = source.read(b, 8192);
            } catch (SocketTimeoutException e) {
                throw new Silence(timeout);
            }
            if (n < 0) {
                return null;
            }
            return b.readByteArray();
        }

        @Override
        public void close() {
            response.close();
        }
    }
}
