package callguard.bench;

import callguard.CallObservations;
import callguard.GuardException;
import callguard.Json;
import callguard.Policy;
import callguard.jdk.JdkTransport;
import callguard.langchain4j.GuardedHttpClientBuilder;
import callguard.okhttp.GuardInterceptor;
import dev.langchain4j.micrometer.metrics.listeners.MicrometerMetricsChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * The clients under test, each driven through its public API in the configuration a user would write: Spring AI and
 * LangChain4j with their own retry and timeout settings (baseline), the same two with the guard installed, and the
 * guard alone over {@code java.net.http} (no framework).
 */
public final class Clients {

    /** How a client is configured for one run. */
    public sealed interface Setup permits Native, Guarded {
        String name();
    }

    /** The framework's own settings; {@code null} fields keep its default. */
    public record Native(String name, Integer maxRetries, Duration timeout) implements Setup {
    }

    /** Framework retries off, framework timeouts out of the way, the guard with {@code policy} installed. */
    public record Guarded(String name, Policy policy, long seed) implements Setup {
    }

    public static final Native DEFAULTS = new Native("defaults", null, null);
    public static final Native EXPLICIT = new Native("explicit: maxRetries=2, timeout=2s", 2, Duration.ofSeconds(2));
    /** The policy evaluated in the study; timeouts are short because the mock provider answers at once. */
    public static final Policy STUDY_POLICY = new Policy(3, Duration.ofSeconds(3), Duration.ofSeconds(Long.getLong("guard.firstEventSeconds", 3)),
            Duration.ofSeconds(10), Duration.ofSeconds(60),
            Duration.ofMillis(500), Duration.ofSeconds(8), Duration.ofSeconds(30), 0.5);
    public static final Guarded GUARDED = new Guarded("guard", STUDY_POLICY, 42);
    /** Framework timeout when the guard is installed: longer than the policy's total budget, so it never fires first. */
    static final Duration FRAMEWORK_TIMEOUT_UNDER_GUARD = Duration.ofMinutes(10);

    public interface ClientUnderTest {
        String name();

        boolean supports(Setup s);

        Outcome sync(MockProvider p, Setup s);

        Outcome stream(MockProvider p, Setup s);
    }

    static final String MODEL = "test-model";
    static final String PROMPT = "Summarise the last reading.";
    static final Duration HARNESS_CAP = Duration.ofSeconds(330);
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "call");
        t.setDaemon(true);
        return t;
    });

    private Clients() {
    }

    // ------------------------------------------------------------------ Spring AI

    public static ClientUnderTest springAi(String baseUrlOverride, String model) {
        return new ClientUnderTest() {
            public String name() {
                return "spring-ai-2.0.1";
            }

            public boolean supports(Setup s) {
                return true;
            }

            private OpenAiChatModel build(MockProvider p, Setup s, Probe probe) {
                var b = OpenAiChatOptions.builder().baseUrl(baseUrlOverride != null ? baseUrlOverride : p.baseUrl()).apiKey("test-key")
                        .model(model != null ? model : MODEL);
                var mb = OpenAiChatModel.builder().observationRegistry(probe.registry);
                switch (s) {
                    case Native n -> {
                        if (n.maxRetries() != null) {
                            b.maxRetries(n.maxRetries());
                        }
                        if (n.timeout() != null) {
                            b.timeout(n.timeout());
                        }
                    }
                    case Guarded g -> {
                        b.maxRetries(0).timeout(FRAMEWORK_TIMEOUT_UNDER_GUARD);
                        GuardInterceptor guard = new GuardInterceptor(g.policy(), probe.registry, "spring-ai", random(g));
                        mb.httpClientBuilderCustomizer(hb -> hb.interceptor(guard));
                    }
                }
                return mb.options(b.build()).build();
            }

            public Outcome sync(MockProvider p, Setup s) {
                Probe probe = new Probe();
                OpenAiChatModel m = build(p, s, probe);
                return run(p, probe, () -> {
                    var r = m.call(new Prompt(PROMPT));
                    return new Delivered(r == null || r.getResult() == null ? "" : String.valueOf(r.getResult().getOutput().getText()), 0);
                });
            }

            public Outcome stream(MockProvider p, Setup s) {
                Probe probe = new Probe();
                OpenAiChatModel m = build(p, s, probe);
                StringBuilder text = new StringBuilder();
                AtomicInteger chunks = new AtomicInteger();
                return run(p, probe, () -> {
                    m.stream(new Prompt(PROMPT)).doOnNext(r -> {
                        if (r.getResult() != null && r.getResult().getOutput().getText() != null && !r.getResult().getOutput().getText().isEmpty()) {
                            text.append(r.getResult().getOutput().getText());
                            chunks.incrementAndGet();
                        }
                    }).blockLast(HARNESS_CAP);
                    return new Delivered(text.toString(), chunks.get());
                }, () -> new Delivered(text.toString(), chunks.get()));
            }
        };
    }

    public static final ClientUnderTest SPRING_AI = springAi(null, null);

    // ------------------------------------------------------------------ LangChain4j

    public static ClientUnderTest langChain4j(String baseUrlOverride, String model) {
        return new ClientUnderTest() {
            public String name() {
                return "langchain4j-1.21.0";
            }

            public boolean supports(Setup s) {
                return true;
            }

            public Outcome sync(MockProvider p, Setup s) {
                Probe probe = new Probe();
                var b = dev.langchain4j.model.openai.OpenAiChatModel.builder().baseUrl(baseUrlOverride != null ? baseUrlOverride : p.baseUrl())
                        .apiKey("test-key").modelName(model != null ? model : MODEL)
                        .listeners(List.of(probe.listener, new MicrometerMetricsChatModelListener(probe.meters)));
                switch (s) {
                    case Native n -> {
                        if (n.maxRetries() != null) {
                            b.maxRetries(n.maxRetries());
                        }
                        if (n.timeout() != null) {
                            b.timeout(n.timeout());
                        }
                    }
                    case Guarded g -> b.maxRetries(0).httpClientBuilder(new GuardedHttpClientBuilder(g.policy(), probe.registry, random(g)));
                }
                var m = b.build();
                return run(p, probe, () -> new Delivered(String.valueOf(m.chat(PROMPT)), 0));
            }

            public Outcome stream(MockProvider p, Setup s) {
                Probe probe = new Probe();
                var b = dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder().baseUrl(baseUrlOverride != null ? baseUrlOverride : p.baseUrl())
                        .apiKey("test-key").modelName(model != null ? model : MODEL)
                        .listeners(List.of(probe.listener, new MicrometerMetricsChatModelListener(probe.meters)));
                switch (s) {
                    case Native n -> {
                        // the streaming builder has no maxRetries in 1.21.0
                        if (n.timeout() != null) {
                            b.timeout(n.timeout());
                        }
                    }
                    case Guarded g -> b.httpClientBuilder(new GuardedHttpClientBuilder(g.policy(), probe.registry, random(g)));
                }
                var m = b.build();
                StringBuilder text = new StringBuilder();
                AtomicInteger chunks = new AtomicInteger();
                return run(p, probe, () -> {
                    CompletableFuture<Void> done = new CompletableFuture<>();
                    m.chat(PROMPT, new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String partial) {
                            text.append(partial);
                            chunks.incrementAndGet();
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse response) {
                            done.complete(null);
                        }

                        @Override
                        public void onError(Throwable error) {
                            done.completeExceptionally(error);
                        }
                    });
                    try {
                        done.get(HARNESS_CAP.toSeconds(), TimeUnit.SECONDS);
                    } catch (java.util.concurrent.ExecutionException e) {
                        throw e.getCause() instanceof RuntimeException re ? re : new RuntimeException(e.getCause());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return new Delivered(text.toString(), chunks.get());
                }, () -> new Delivered(text.toString(), chunks.get()));
            }
        };
    }

    public static final ClientUnderTest LANGCHAIN4J = langChain4j(null, null);

    // ------------------------------------------------------------------ no framework

    public static ClientUnderTest plain(String baseUrlOverride, String model) {
        return new ClientUnderTest() {
            public String name() {
                return "plain-java.net.http";
            }

            public boolean supports(Setup s) {
                return s instanceof Guarded;
            }

            private JdkTransport.Request request(MockProvider p, boolean stream) {
                String body = "{\"model\":\"" + (model != null ? model : MODEL) + "\",\"stream\":" + stream
                        + ",\"messages\":[{\"role\":\"user\",\"content\":\"" + PROMPT + "\"}]}";
                return new JdkTransport.Request("POST", URI.create((baseUrlOverride != null ? baseUrlOverride : p.baseUrl()) + "/chat/completions"),
                        Map.of("Content-Type", List.of("application/json"), "Authorization", List.of("Bearer test-key")),
                        body.getBytes(StandardCharsets.UTF_8));
            }

            private JdkTransport transport(Guarded g, Probe probe) {
                return new JdkTransport(java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(10)).build(), g.policy(), probe.registry, "jdk", random(g));
            }

            public Outcome sync(MockProvider p, Setup s) {
                Probe probe = new Probe();
                JdkTransport t = transport((Guarded) s, probe);
                return run(p, probe, () -> {
                    try {
                        JdkTransport.Response r = t.send(request(p, false));
                        if (!r.ok()) {
                            throw new IllegalStateException("provider status " + r.status());
                        }
                        return new Delivered(content(new String(r.body(), StandardCharsets.UTF_8), "message"), 0);
                    } catch (GuardException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            }

            public Outcome stream(MockProvider p, Setup s) {
                Probe probe = new Probe();
                JdkTransport t = transport((Guarded) s, probe);
                StringBuilder text = new StringBuilder();
                AtomicInteger chunks = new AtomicInteger();
                return run(p, probe, () -> {
                    try {
                        JdkTransport.StreamResponse r = t.stream(request(p, true));
                        if (!r.ok()) {
                            throw new IllegalStateException("provider status " + r.status());
                        }
                        try (BufferedReader in = new BufferedReader(new InputStreamReader(r.events(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = in.readLine()) != null) {
                                if (line.startsWith("data:") && !line.contains("[DONE]")) {
                                    String c = content(line.substring(5).strip(), "delta");
                                    if (!c.isEmpty()) {
                                        text.append(c);
                                        chunks.incrementAndGet();
                                    }
                                }
                            }
                        }
                        return new Delivered(text.toString(), chunks.get());
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }, () -> new Delivered(text.toString(), chunks.get()));
            }
        };
    }

    public static final ClientUnderTest PLAIN = plain(null, null);

    /** choices[0].{field}.content, or "" */
    static String content(String json, String field) {
        if (Json.parse(json) instanceof Map<?, ?> m && m.get("choices") instanceof List<?> c && !c.isEmpty()
                && c.get(0) instanceof Map<?, ?> ch && ch.get(field) instanceof Map<?, ?> msg && msg.get("content") instanceof String s) {
            return s;
        }
        return "";
    }

    static RandomGenerator random(Guarded g) {
        return new SplittableRandom(g.seed());
    }

    // ------------------------------------------------------------------ shared

    record Delivered(String text, int chunks) {
    }

    /** Captures framework observations, the guard's observations, listener callbacks (LangChain4j) and meters. */
    static final class Probe {
        final ObservationRegistry registry = ObservationRegistry.create();
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final AtomicInteger stopped = new AtomicInteger();
        final AtomicInteger errored = new AtomicInteger();
        final AtomicInteger guardCalls = new AtomicInteger();
        final AtomicInteger guardAttempts = new AtomicInteger();
        final AtomicReference<String> guardRecord = new AtomicReference<>("-");
        final List<String> names = java.util.Collections.synchronizedList(new ArrayList<>());
        final ChatModelListener listener = new ChatModelListener() {
            @Override
            public void onRequest(ChatModelRequestContext ctx) {
                stopped.incrementAndGet();
                names.add("listener.request");
            }

            @Override
            public void onResponse(ChatModelResponseContext ctx) {
                names.add("listener.response");
            }

            @Override
            public void onError(ChatModelErrorContext ctx) {
                errored.incrementAndGet();
                names.add("listener.error:" + ctx.error().getClass().getSimpleName());
            }
        };

        Probe() {
            registry.observationConfig().observationHandler(new ObservationHandler<>() {
                @Override
                public boolean supportsContext(Observation.Context context) {
                    return true;
                }

                @Override
                public void onStop(Observation.Context c) {
                    String name = c.getName();
                    if (CallObservations.CALL.equals(name)) {
                        guardCalls.incrementAndGet();
                        guardRecord.set(value(c, "outcome") + "/" + value(c, "error.type") + "/" + value(c, "attempts"));
                        names.add("guard.call:" + value(c, "outcome") + ":" + value(c, "error.type"));
                        return;
                    }
                    if (CallObservations.ATTEMPT.equals(name)) {
                        guardAttempts.incrementAndGet();
                        names.add("guard.attempt:" + value(c, "status") + ":" + value(c, "error.type") + ":" + value(c, "decision"));
                        return;
                    }
                    stopped.incrementAndGet();
                    if (c.getError() != null) {
                        errored.incrementAndGet();
                    }
                    names.add(name + (c.getError() != null ? "!" + c.getError().getClass().getSimpleName() : ""));
                }
            });
            registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        }

        private static String value(Observation.Context c, String key) {
            var kv = c.getLowCardinalityKeyValue(key);
            return kv == null ? "?" : kv.getValue();
        }

        /** The error tag on the framework's meters (the guard's meters are excluded). */
        String errorTag() {
            TreeSet<String> values = new TreeSet<>();
            for (Meter m : meters.getMeters()) {
                if (m.getId().getName().startsWith("llm.guard")) {
                    continue;
                }
                for (Tag t : m.getId().getTags()) {
                    if (t.getKey().equals("error") || t.getKey().equals("error.type")) {
                        values.add(t.getValue());
                    }
                }
            }
            return values.isEmpty() ? "-" : String.join("|", values);
        }
    }

    static Outcome run(MockProvider p, Probe probe, Supplier<Delivered> call) {
        return run(p, probe, call, () -> new Delivered("", 0));
    }

    static Outcome run(MockProvider p, Probe probe, Supplier<Delivered> call, Supplier<Delivered> partial) {
        long t0 = System.nanoTime();
        Future<Delivered> f = POOL.submit(call::get);
        String result;
        String error = "";
        String kind = "-";
        Delivered d;
        try {
            d = f.get(HARNESS_CAP.toSeconds() + 5, TimeUnit.SECONDS);
            result = MockProvider.FULL_TEXT.equals(d.text()) ? "OK" : "PARTIAL";
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            d = partial.get();
            result = "TIMEOUT_HARNESS";
        } catch (java.util.concurrent.ExecutionException e) {
            d = partial.get();
            result = "ERROR";
            error = chain(e.getCause());
            kind = GuardException.find(e.getCause()).map(g -> g.kind().tag()).orElse("-");
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        long millis = (System.nanoTime() - t0) / 1_000_000;
        sleepQuietly(300); // let late observation stops and listener callbacks arrive
        List<MockProvider.Attempt> a = p == null ? List.of() : p.attempts();
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < a.size(); i++) {
            gaps.add((a.get(i).nanos() - a.get(i - 1).nanos()) / 1_000_000);
        }
        return new Outcome(result, error, kind, d.text(), d.chunks(), millis, a.size(), gaps, probe.stopped.get(), probe.errored.get(), probe.errorTag(),
                probe.guardCalls.get(), probe.guardRecord.get(), probe.guardAttempts.get(), String.join(" ", probe.names));
    }

    static String chain(Throwable t) {
        List<String> names = new ArrayList<>();
        Throwable cur = t;
        int guard = 0;
        while (cur != null && guard++ < 8) {
            names.add(cur.getClass().getSimpleName());
            if (cur.getCause() == cur) {
                break;
            }
            cur = cur.getCause();
        }
        return String.join(" < ", names);
    }

    static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
