package callguard.bench;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local OpenAI-compatible chat-completions endpoint whose behaviour is scripted per attempt. Every request is
 * recorded with its arrival time and headers, so the number of attempts and the spacing between them are measured
 * at the server, independently of what the client reports.
 */
public final class MockProvider implements AutoCloseable {

    /** What the provider does for one attempt. */
    public sealed interface Behaviour permits Ok, Status, Hang, Malformed, EmptyChoices, StreamTruncate, StreamErrorEvent, SlowStream {
    }

    /** A normal response (a complete SSE stream when the request asks for streaming). */
    public record Ok() implements Behaviour {
    }

    /** An HTTP error with an OpenAI-style error body and optional headers (e.g. Retry-After). */
    public record Status(int code, Map<String, String> headers) implements Behaviour {
    }

    /** Accept the request and send nothing for the given time. */
    public record Hang(long millis) implements Behaviour {
    }

    /** 200 with a body that is not valid JSON. */
    public record Malformed() implements Behaviour {
    }

    /** 200 with a valid completion object whose choices array is empty. */
    public record EmptyChoices() implements Behaviour {
    }

    /** A stream that delivers {@code chunks} content chunks and then closes without a finish reason or [DONE]. */
    public record StreamTruncate(int chunks) implements Behaviour {
    }

    /** A stream that delivers {@code chunks} content chunks and then an error event. */
    public record StreamErrorEvent(int chunks) implements Behaviour {
    }

    /** A complete stream with a delay between chunks. */
    public record SlowStream(long millisPerChunk) implements Behaviour {
    }

    /** One recorded request. */
    public record Attempt(long nanos, String path, boolean stream, Map<String, List<String>> headers) {
    }

    public static final String[] TOKENS = {"The", " reading", " is", " within", " the", " expected", " range", "."};
    public static final String FULL_TEXT = String.join("", TOKENS);

    private final HttpServer server;
    private final List<Attempt> attempts = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger counter = new AtomicInteger();
    private volatile List<Behaviour> script = List.of(new Ok());

    public MockProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mock-provider");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        server.start();
    }

    /** The base URL clients are pointed at; requests go to {@code <base>/chat/completions}. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** Sets the behaviour of attempts 1, 2, …; the last entry repeats for every later attempt. Clears the record. */
    public void script(Behaviour... behaviours) {
        attempts.clear();
        counter.set(0);
        script = List.of(behaviours);
    }

    public List<Attempt> attempts() {
        synchronized (attempts) {
            return List.copyOf(attempts);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        boolean stream = body.replace(" ", "").contains("\"stream\":true");
        attempts.add(new Attempt(System.nanoTime(), ex.getRequestURI().getPath(), stream, Map.copyOf(ex.getRequestHeaders())));
        int n = counter.getAndIncrement();
        List<Behaviour> s = script;
        Behaviour b = s.get(Math.min(n, s.size() - 1));
        try {
            switch (b) {
                case Ok ok -> {
                    if (stream) {
                        stream(ex, TOKENS.length, 0, true, false);
                    } else {
                        json(ex, 200, completion(FULL_TEXT), Map.of());
                    }
                }
                case Status st -> json(ex, st.code(), error(st.code()), st.headers());
                case Hang h -> {
                    sleep(h.millis());
                    ex.close();
                }
                case Malformed m -> json(ex, 200, "{\"id\":\"chatcmpl-x\",\"choices\":[{\"message\":", Map.of());
                case EmptyChoices e -> json(ex, 200, "{\"id\":\"chatcmpl-x\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test-model\",\"choices\":[]}", Map.of());
                case StreamTruncate t -> stream(ex, t.chunks(), 0, false, false);
                case StreamErrorEvent e -> stream(ex, e.chunks(), 0, false, true);
                case SlowStream sl -> stream(ex, TOKENS.length, sl.millisPerChunk(), true, false);
            }
        } catch (IOException ignored) {
            // the client gave up (timeout); nothing to do
        } finally {
            ex.close();
        }
    }

    private static void json(HttpExchange ex, int code, String body, Map<String, String> headers) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void stream(HttpExchange ex, int chunks, long delay, boolean finish, boolean errorEvent) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        for (int i = 0; i < chunks; i++) {
            if (delay > 0) {
                sleep(delay);
            }
            String delta = "{\"content\":\"" + TOKENS[i % TOKENS.length] + "\"}";
            event(os, "{\"id\":\"chatcmpl-s\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":null}]}");
        }
        if (errorEvent) {
            event(os, "{\"error\":{\"message\":\"The server had an error while processing your request.\",\"type\":\"server_error\",\"param\":null,\"code\":null}}");
        }
        if (finish) {
            event(os, "{\"id\":\"chatcmpl-s\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
            os.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        }
        os.flush();
        os.close();
    }

    private static void event(OutputStream os, String json) throws IOException {
        os.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static String completion(String text) {
        return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test-model\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":8,\"total_tokens\":13}}";
    }

    private static String error(int code) {
        String type = switch (code) {
            case 400 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 429 -> "rate_limit_exceeded";
            default -> "server_error";
        };
        return String.format(Locale.ROOT, "{\"error\":{\"message\":\"Mock provider returned %d\",\"type\":\"%s\",\"param\":null,\"code\":null}}", code, type);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
