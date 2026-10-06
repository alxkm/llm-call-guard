package callguard;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

/**
 * A server-sent-event body that is passed to the caller event by event, and only after each event has been checked.
 * The caller reads the same bytes the provider sent, except that an error event or a malformed event is never handed
 * over: the read that would return it throws a {@link GuardException} instead. The end of the body is reported as
 * the end of the stream only if a terminal event ({@code [DONE]} or a {@code finish_reason}) was seen; otherwise the
 * read throws {@link FailureKind#STREAM_TRUNCATED}. Silence longer than the idle timeout throws
 * {@link FailureKind#STREAM_IDLE}.
 *
 * <p>Before {@link #arm()}, failures are only thrown, so that the transport can still decide to retry; after it, every
 * end of the stream is reported once to the {@link Listener}.
 */
public final class GuardedSseStream extends InputStream {

    /** Receives the end of the stream exactly once after {@link #arm()}. */
    public interface Listener {
        /** @param failure {@code null} for a complete stream */
        void onEnd(FailureKind failure, Throwable cause, int events);
    }

    private final ChunkSource source;
    private final Duration idle;
    private final int attempt;
    private Listener listener;
    private byte[] pending = new byte[0];
    private byte[] out = new byte[0];
    private int outPos;
    private int events;
    private boolean terminal;
    private boolean ended;
    private GuardException deferred;

    public GuardedSseStream(ChunkSource source, Duration idle, int attempt) {
        this.source = source;
        this.idle = idle;
        this.attempt = attempt;
    }

    /**
     * Reads until the first event that carries data. Nothing is reported to a listener.
     *
     * @throws ChunkSource.Silence if nothing arrived in time
     * @throws EOFException if the body ended before any event
     * @throws GuardException if the first event is an error or malformed
     */
    public void awaitFirstEvent(Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (events == 0 && deferred == null) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new ChunkSource.Silence(timeout);
            }
            if (!fill(Duration.ofNanos(left))) {
                throw new EOFException("the body ended before the first event");
            }
        }
        if (deferred != null && events == 0) {
            throw deferred;
        }
    }

    /** From now on, the end of the stream is reported to {@code l}. */
    public void arm(Listener l) {
        this.listener = l;
    }

    /** Data events seen so far. */
    public int events() {
        return events;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        while (outPos >= out.length) {
            if (deferred != null) {
                throw report(deferred);
            }
            if (ended) {
                return -1;
            }
            boolean more;
            try {
                more = fill(idle);
            } catch (ChunkSource.Silence s) {
                throw report(new GuardException(FailureKind.STREAM_IDLE, attempt, s.getMessage(), s));
            } catch (GuardException g) {
                throw report(g);
            } catch (IOException e) {
                throw report(new GuardException(FailureKind.CONNECTION, attempt, "the stream broke: " + e.getMessage(), e));
            }
            if (!more) {
                endOfBody();
            }
        }
        int n = Math.min(len, out.length - outPos);
        System.arraycopy(out, outPos, b, off, n);
        outPos += n;
        return n;
    }

    @Override
    public void close() {
        if (listener != null && !ended) {
            ended = true;
            listener.onEnd(FailureKind.CANCELLED, null, events);
        }
        ended = true;
        source.close();
    }

    private void endOfBody() throws GuardException {
        if (pending.length > 0 && !new String(pending, StandardCharsets.UTF_8).isBlank()) {
            byte[] last = pending;
            pending = new byte[0];
            accept(last);
        }
        if (deferred != null) {
            return; // thrown by the next read once the events before it are delivered
        }
        if (!terminal) {
            deferred = new GuardException(FailureKind.STREAM_TRUNCATED, attempt,
                    "the stream ended after " + events + " events without [DONE] or a finish_reason", null);
            return;
        }
        if (!ended) {
            ended = true;
            if (listener != null) {
                listener.onEnd(null, null, events);
            }
        }
    }

    private GuardException report(GuardException g) {
        if (listener != null && !ended) {
            ended = true;
            listener.onEnd(g.kind(), g, events);
        }
        ended = true;
        return g;
    }

    /** Reads one chunk and moves every complete, valid event to {@code out}. Returns false at the end of the body. */
    private boolean fill(Duration timeout) throws IOException {
        byte[] chunk = source.read(timeout);
        if (chunk == null) {
            return false;
        }
        byte[] buf = Arrays.copyOf(pending, pending.length + chunk.length);
        System.arraycopy(chunk, 0, buf, pending.length, chunk.length);
        int start = 0;
        int end;
        while (deferred == null && (end = eventEnd(buf, start)) > 0) {
            accept(Arrays.copyOfRange(buf, start, end));
            start = end;
        }
        pending = Arrays.copyOfRange(buf, start, buf.length);
        return true;
    }

    private void accept(byte[] event) {
        String data = data(new String(event, StandardCharsets.UTF_8));
        switch (WireCheck.classify(data)) {
            case ERROR -> deferred = new GuardException(FailureKind.STREAM_ERROR_EVENT, attempt, "the provider sent an error event: " + abbreviate(data), null);
            case MALFORMED -> deferred = new GuardException(FailureKind.MALFORMED_RESPONSE, attempt, "an event is not JSON: " + abbreviate(data), null);
            case DONE, FINISH -> {
                terminal = true;
                events++;
                append(event);
            }
            case DATA -> {
                events++;
                append(event);
            }
            case NONE -> append(event);
        }
    }

    private void append(byte[] event) {
        byte[] rest = Arrays.copyOfRange(out, outPos, out.length);
        out = Arrays.copyOf(rest, rest.length + event.length);
        System.arraycopy(event, 0, out, rest.length, event.length);
        outPos = 0;
    }

    /** Index just past the blank line that ends the next event at or after {@code from}, or -1. */
    static int eventEnd(byte[] b, int from) {
        for (int i = from + 1; i < b.length; i++) {
            if (b[i] == '\n' && b[i - 1] == '\n') {
                return i + 1;
            }
            if (b[i] == '\r' && b[i - 1] == '\r') {
                return i + 1;
            }
            if (b[i] == '\n' && i >= from + 3 && b[i - 1] == '\r' && b[i - 2] == '\n' && b[i - 3] == '\r') {
                return i + 1;
            }
        }
        return -1;
    }

    /** The {@code data:} lines of one event joined by newlines, or {@code null} if it has none. */
    static String data(String event) {
        StringBuilder d = null;
        for (String line : event.split("\r\n|\r|\n")) {
            if (line.startsWith("data:")) {
                String v = line.substring(5);
                if (v.startsWith(" ")) {
                    v = v.substring(1);
                }
                if (d == null) {
                    d = new StringBuilder(v);
                } else {
                    d.append('\n').append(v);
                }
            }
        }
        return d == null ? null : d.toString();
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() <= 160 ? s : s.substring(0, 160) + "…";
    }
}
