package callguard.langchain4j;

import callguard.FailureKind;
import callguard.GuardException;
import callguard.jdk.JdkTransport;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The guard as a LangChain4j {@link HttpClient}, installed with {@code .httpClientBuilder(new GuardedHttpClientBuilder(...))}
 * on any LangChain4j model that talks to an OpenAI-compatible endpoint. Errors are raised the way LangChain4j's own
 * JDK client raises them ({@link HttpException} for a status, {@link TimeoutException} for no response), so the
 * model's exception mapping is unchanged; the model's own {@code maxRetries} must be 0.
 */
public final class GuardedHttpClient implements HttpClient {
    private final JdkTransport transport;
    private final Executor executor;

    public GuardedHttpClient(JdkTransport transport, Executor executor) {
        this.transport = transport;
        this.executor = executor;
    }

    @Override
    public SuccessfulHttpResponse execute(HttpRequest request) {
        JdkTransport.Response r;
        try {
            r = transport.send(map(request));
        } catch (GuardException g) {
            throw translate(g);
        }
        if (!r.ok()) {
            throw new HttpException(r.status(), new String(r.body(), StandardCharsets.UTF_8));
        }
        return SuccessfulHttpResponse.builder().statusCode(r.status()).headers(r.headers()).body(r.body()).build();
    }

    @Override
    public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        executor.execute(() -> {
            JdkTransport.StreamResponse r;
            try {
                r = transport.stream(map(request));
            } catch (GuardException g) {
                listener.onError(translate(g));
                return;
            } catch (RuntimeException e) {
                listener.onError(e);
                return;
            }
            if (!r.ok()) {
                listener.onError(new HttpException(r.status(), new String(r.errorBody(), StandardCharsets.UTF_8)));
                return;
            }
            // the same sequence as LangChain4j's own JDK client: onOpen, parse (which reports a failed read to onError), onClose
            try (InputStream in = r.events()) {
                quietly(() -> listener.onOpen(SuccessfulHttpResponse.builder().statusCode(r.status()).headers(r.headers()).build()));
                parser.parse(in, listener);
                quietly(listener::onClose);
            } catch (Exception e) {
                quietly(() -> listener.onError(e));
            }
        });
    }

    private static void quietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // a listener's own failure must not end the stream handling, as in LangChain4j's clients
        }
    }

    private static RuntimeException translate(GuardException g) {
        if (g.kind() == FailureKind.NO_RESPONSE) {
            return new TimeoutException(g);
        }
        return new RuntimeException(g);
    }

    private static JdkTransport.Request map(HttpRequest r) {
        Map<String, List<String>> headers = r.headers() == null ? Map.of() : r.headers();
        byte[] body = r.body() == null ? null : r.body().getBytes(StandardCharsets.UTF_8);
        return new JdkTransport.Request(r.method().name(), URI.create(r.url()), headers, body);
    }
}
