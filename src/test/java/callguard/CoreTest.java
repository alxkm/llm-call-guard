package callguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CoreTest {

    // ------------------------------------------------------------------ retry rules

    private final Policy policy = new Policy(3, Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(10), Duration.ofSeconds(60),
            Duration.ofMillis(500), Duration.ofSeconds(8), Duration.ofSeconds(30), 0.5);
    private final RetryRules rules = new RetryRules(policy, new SplittableRandom(1));

    @Test
    void permanentFailuresAreNotRetried() {
        for (FailureKind k : List.of(FailureKind.CLIENT_ERROR, FailureKind.AUTHENTICATION, FailureKind.MALFORMED_RESPONSE, FailureKind.EMPTY_RESPONSE,
                FailureKind.STREAM_TRUNCATED, FailureKind.STREAM_ERROR_EVENT)) {
            assertFalse(rules.afterFailure(1, k, false, Optional.empty(), Duration.ZERO, Duration.ofSeconds(3)).retry(), k.name());
        }
    }

    @Test
    void transientFailuresAreRetriedUntilTheLastAttempt() {
        assertTrue(rules.afterFailure(1, FailureKind.SERVER_ERROR, false, Optional.empty(), Duration.ZERO, Duration.ofSeconds(3)).retry());
        assertTrue(rules.afterFailure(2, FailureKind.SERVER_ERROR, false, Optional.empty(), Duration.ZERO, Duration.ofSeconds(3)).retry());
        assertFalse(rules.afterFailure(3, FailureKind.SERVER_ERROR, false, Optional.empty(), Duration.ZERO, Duration.ofSeconds(3)).retry());
    }

    @Test
    void nothingIsRetriedOnceDelivered() {
        assertFalse(rules.afterFailure(1, FailureKind.CONNECTION, true, Optional.empty(), Duration.ZERO, Duration.ofSeconds(3)).retry());
    }

    @Test
    void retryAfterIsHonouredAndAnExcessiveOneEndsTheCall() {
        var v = rules.afterFailure(1, FailureKind.RATE_LIMITED, false, Optional.of(Duration.ofSeconds(3)), Duration.ZERO, Duration.ofSeconds(3));
        assertTrue(v.retry());
        assertEquals(Duration.ofSeconds(3), v.delay());
        assertFalse(rules.afterFailure(1, FailureKind.RATE_LIMITED, false, Optional.of(Duration.ofSeconds(31)), Duration.ZERO, Duration.ofSeconds(3)).retry());
    }

    @Test
    void theBudgetStopsAnAttemptThatCouldNotFinish() {
        assertFalse(rules.afterFailure(1, FailureKind.SERVER_ERROR, false, Optional.empty(), Duration.ofSeconds(57), Duration.ofSeconds(3)).retry());
    }

    @Test
    void backoffDoublesWithinItsJitterBand() {
        for (int a = 1; a <= 6; a++) {
            long d = Math.min(8000, 500L << (a - 1));
            long got = rules.backoff(a).toMillis();
            assertTrue(got >= d / 2 && got <= d, "attempt " + a + ": " + got);
        }
    }

    @Test
    void retryAfterHeaders() {
        assertEquals(Optional.of(Duration.ofSeconds(3)), RetryAfter.parse(Map.of("Retry-After", List.of("3"))));
        assertEquals(Optional.of(Duration.ofMillis(1500)), RetryAfter.parse(Map.of("retry-after-ms", List.of("1500"), "retry-after", List.of("9"))));
        assertEquals(Optional.empty(), RetryAfter.parse(Map.of("Retry-After", List.of("soon"))));
        assertTrue(RetryAfter.parse(Map.of("Retry-After", List.of("Wed, 21 Oct 2015 07:28:00 GMT"))).orElseThrow().isZero());
    }

    @Test
    void statusClasses() {
        assertEquals(FailureKind.RATE_LIMITED, FailureKind.ofStatus(429));
        assertEquals(FailureKind.SERVER_ERROR, FailureKind.ofStatus(503));
        assertEquals(FailureKind.AUTHENTICATION, FailureKind.ofStatus(403));
        assertEquals(FailureKind.CLIENT_ERROR, FailureKind.ofStatus(422));
        assertEquals(FailureKind.REQUEST_TIMEOUT, FailureKind.ofStatus(408));
    }

    // ------------------------------------------------------------------ wire checks

    @Test
    void bodies() {
        assertNull(WireCheck.checkBody(b("{\"choices\":[{\"message\":{\"content\":\"x\"}}]}")));
        assertNull(WireCheck.checkBody(b("{\"data\":[{\"embedding\":[0.1]}]}")));
        assertEquals(FailureKind.EMPTY_RESPONSE, WireCheck.checkBody(b("{\"choices\":[]}")));
        assertEquals(FailureKind.MALFORMED_RESPONSE, WireCheck.checkBody(b("{\"choices\":[{\"message\":")));
        assertEquals(FailureKind.MALFORMED_RESPONSE, WireCheck.checkBody(b("[1,2]")));
        assertEquals(FailureKind.MALFORMED_RESPONSE, WireCheck.checkBody(b("{\"error\":{\"message\":\"x\"}}")));
    }

    @Test
    void events() {
        assertEquals(WireCheck.Event.DONE, WireCheck.classify("[DONE]"));
        assertEquals(WireCheck.Event.FINISH, WireCheck.classify("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"));
        assertEquals(WireCheck.Event.DATA, WireCheck.classify("{\"choices\":[{\"delta\":{\"content\":\"a\"},\"finish_reason\":null}]}"));
        assertEquals(WireCheck.Event.ERROR, WireCheck.classify("{\"error\":{\"message\":\"overloaded\"}}"));
        assertEquals(WireCheck.Event.MALFORMED, WireCheck.classify("{\"choices\":"));
        assertEquals(WireCheck.Event.NONE, WireCheck.classify(null));
    }

    @Test
    void json() {
        Object v = Json.parse("{\"a\":[1,-2.5e3,true,false,null,\"x\\n\\u00e9\"],\"b\":{}}");
        assertEquals("x\né", ((List<?>) ((Map<?, ?>) v).get("a")).get(5));
        assertEquals(Json.NULL, ((List<?>) ((Map<?, ?>) v).get("a")).get(4));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1} x"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{'a':1}"));
    }

    // ------------------------------------------------------------------ the guarded stream

    private static final String D1 = "data: {\"choices\":[{\"delta\":{\"content\":\"a\"},\"finish_reason\":null}]}";
    private static final String FIN = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}";

    @Test
    void aCompleteStreamPassesThroughUnchangedEvenWhenSplitAnywhere() throws IOException {
        String body = D1 + "\n\n" + D1 + "\r\n\r\n: keep-alive\n\n" + FIN + "\n\ndata: [DONE]\n\n";
        for (int split = 1; split < body.length(); split += 7) {
            AtomicReference<String> end = new AtomicReference<>();
            GuardedSseStream s = new GuardedSseStream(source(body.substring(0, split), body.substring(split)), Duration.ofSeconds(1), 1);
            s.awaitFirstEvent(Duration.ofSeconds(1));
            s.arm((f, c, n) -> end.set(f == null ? "ok:" + n : f.tag()));
            assertEquals(body, readAll(s));
            assertEquals("ok:4", end.get());
        }
    }

    @Test
    void aStreamWithoutTerminalEventIsTruncated() throws IOException {
        AtomicReference<String> end = new AtomicReference<>();
        GuardedSseStream s = new GuardedSseStream(source(D1 + "\n\n", D1 + "\n\n"), Duration.ofSeconds(1), 1);
        s.awaitFirstEvent(Duration.ofSeconds(1));
        s.arm((f, c, n) -> end.set(f == null ? "ok" : f.tag() + ":" + n));
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        GuardException g = assertThrows(GuardException.class, () -> drain(s, got));
        assertEquals(FailureKind.STREAM_TRUNCATED, g.kind());
        assertEquals(D1 + "\n\n" + D1 + "\n\n", got.toString(StandardCharsets.UTF_8)); // what came is delivered before the failure
        assertEquals("stream-truncated:2", end.get());
    }

    @Test
    void anErrorEventIsNotDelivered() throws IOException {
        GuardedSseStream s = new GuardedSseStream(source(D1 + "\n\ndata: {\"error\":{\"message\":\"boom\"}}\n\n" + FIN + "\n\n"), Duration.ofSeconds(1), 1);
        s.awaitFirstEvent(Duration.ofSeconds(1));
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        GuardException g = assertThrows(GuardException.class, () -> drain(s, got));
        assertEquals(FailureKind.STREAM_ERROR_EVENT, g.kind());
        assertEquals(D1 + "\n\n", got.toString(StandardCharsets.UTF_8));
    }

    @Test
    void silenceAfterTheFirstEventIsIdle() throws IOException {
        Deque<Object> q = new ArrayDeque<>(List.of(b(D1 + "\n\n"), "silence"));
        GuardedSseStream s = new GuardedSseStream(scripted(q), Duration.ofMillis(50), 1);
        s.awaitFirstEvent(Duration.ofSeconds(1));
        GuardException g = assertThrows(GuardException.class, () -> drain(s, new ByteArrayOutputStream()));
        assertEquals(FailureKind.STREAM_IDLE, g.kind());
    }

    @Test
    void closingEarlyIsRecordedAsCancelled() throws IOException {
        AtomicReference<String> end = new AtomicReference<>();
        GuardedSseStream s = new GuardedSseStream(source(D1 + "\n\n", D1 + "\n\n"), Duration.ofSeconds(1), 1);
        s.awaitFirstEvent(Duration.ofSeconds(1));
        s.arm((f, c, n) -> end.set(f == null ? "ok" : f.tag()));
        s.close();
        assertEquals("cancelled", end.get());
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static ChunkSource source(String... parts) {
        Deque<Object> q = new ArrayDeque<>();
        for (String p : parts) {
            if (!p.isEmpty()) {
                q.add(b(p));
            }
        }
        return scripted(q);
    }

    /** byte[] items are returned in order; "silence" throws {@link ChunkSource.Silence}; an empty queue is the end. */
    private static ChunkSource scripted(Deque<Object> q) {
        return new ChunkSource() {
            @Override
            public byte[] read(Duration timeout) throws IOException {
                Object o = q.poll();
                if (o == null) {
                    return null;
                }
                if ("silence".equals(o)) {
                    throw new Silence(timeout);
                }
                return (byte[]) o;
            }

            @Override
            public void close() {
            }
        };
    }

    private static String readAll(GuardedSseStream s) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        drain(s, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void drain(GuardedSseStream s, ByteArrayOutputStream out) throws IOException {
        byte[] buf = new byte[5];
        int n;
        while ((n = s.read(buf, 0, buf.length)) >= 0) {
            out.write(buf, 0, n);
        }
    }
}
