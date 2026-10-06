package callguard.bench;

import callguard.Policy;
import callguard.jdk.JdkTransport;
import callguard.langchain4j.GuardedHttpClientBuilder;
import callguard.okhttp.GuardInterceptor;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.micrometer.observation.ObservationRegistry;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Latency the guard adds to a successful call: the same client, built once, with and without the guard, called
 * repeatedly against the local mock provider (which answers at once, so the difference is the guard's own work).
 * Calls alternate between the two configurations in blocks so that drift affects both. Writes
 * {@code results/overhead.csv}.
 *
 * <pre>java -cp &lt;classpath&gt; callguard.bench.Overhead [calls per configuration]</pre>
 */
public final class Overhead {
    interface Call {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        Policy policy = Clients.STUDY_POLICY;
        ObservationRegistry reg = ObservationRegistry.create(); // a registry without handlers, as an application without tracing would have
        StringBuilder csv = new StringBuilder("client,mode,calls,native_median_us,guard_median_us,native_p95_us,guard_p95_us,added_median_us,added_p95_us\n");
        try (MockProvider p = new MockProvider()) {
            p.script(new MockProvider.Ok());
            for (String mode : List.of("sync", "stream")) {
                boolean stream = mode.equals("stream");
                // Spring AI
                OpenAiChatModel saNative = OpenAiChatModel.builder().options(OpenAiChatOptions.builder().baseUrl(p.baseUrl()).apiKey("k").model("m").build()).build();
                GuardInterceptor gi = new GuardInterceptor(policy, reg, "spring-ai", new SplittableRandom(1));
                OpenAiChatModel saGuard = OpenAiChatModel.builder().options(OpenAiChatOptions.builder().baseUrl(p.baseUrl()).apiKey("k").model("m").maxRetries(0)
                        .timeout(Duration.ofMinutes(10)).build()).httpClientBuilderCustomizer(b -> b.interceptor(gi)).build();
                row(csv, p, "spring-ai-2.0.1", mode, n, springAi(saNative, stream), springAi(saGuard, stream));
                // LangChain4j
                Call lcNative;
                Call lcGuard;
                if (stream) {
                    var a = dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder().baseUrl(p.baseUrl()).apiKey("k").modelName("m").build();
                    var b = dev.langchain4j.model.openai.OpenAiStreamingChatModel.builder().baseUrl(p.baseUrl()).apiKey("k").modelName("m")
                            .httpClientBuilder(new GuardedHttpClientBuilder(policy, reg, new SplittableRandom(1))).build();
                    lcNative = () -> lcStream(a);
                    lcGuard = () -> lcStream(b);
                } else {
                    var a = dev.langchain4j.model.openai.OpenAiChatModel.builder().baseUrl(p.baseUrl()).apiKey("k").modelName("m").build();
                    var b = dev.langchain4j.model.openai.OpenAiChatModel.builder().baseUrl(p.baseUrl()).apiKey("k").modelName("m").maxRetries(0)
                            .httpClientBuilder(new GuardedHttpClientBuilder(policy, reg, new SplittableRandom(1))).build();
                    lcNative = () -> a.chat(Clients.PROMPT);
                    lcGuard = () -> b.chat(Clients.PROMPT);
                }
                row(csv, p, "langchain4j-1.21.0", mode, n, lcNative, lcGuard);
                // java.net.http without and with the guard
                java.net.http.HttpClient jdk = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build();
                JdkTransport t = new JdkTransport(jdk, policy, reg, "jdk", new SplittableRandom(1));
                String body = "{\"model\":\"m\",\"stream\":" + stream + ",\"messages\":[{\"role\":\"user\",\"content\":\"x\"}]}";
                URI uri = URI.create(p.baseUrl() + "/chat/completions");
                Call plainNative = () -> {
                    var r = jdk.send(java.net.http.HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                    try (InputStream in = r.body()) {
                        in.transferTo(java.io.OutputStream.nullOutputStream());
                    }
                };
                JdkTransport.Request req = new JdkTransport.Request("POST", uri, Map.of("Content-Type", List.of("application/json")),
                        body.getBytes(StandardCharsets.UTF_8));
                Call plainGuard = stream ? () -> {
                    try (InputStream in = t.stream(req).events()) {
                        in.transferTo(java.io.OutputStream.nullOutputStream());
                    }
                } : () -> t.send(req);
                row(csv, p, "plain-java.net.http", mode, n, plainNative, plainGuard);
            }
        }
        Files.createDirectories(Path.of("results"));
        Files.writeString(Path.of("results/overhead.csv"), csv.toString(), StandardCharsets.UTF_8);
        System.out.print(csv);
        System.exit(0);
    }

    private static Call springAi(OpenAiChatModel m, boolean stream) {
        return stream ? () -> m.stream(new Prompt(Clients.PROMPT)).blockLast(Duration.ofSeconds(30)) : () -> m.call(new Prompt(Clients.PROMPT));
    }

    private static void lcStream(dev.langchain4j.model.openai.OpenAiStreamingChatModel m) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        m.chat(Clients.PROMPT, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String s) {
            }

            @Override
            public void onCompleteResponse(ChatResponse r) {
                done.complete(null);
            }

            @Override
            public void onError(Throwable e) {
                done.completeExceptionally(e);
            }
        });
        done.get();
    }

    private static void row(StringBuilder csv, MockProvider p, String client, String mode, int n, Call nat, Call guard) throws Exception {
        for (int i = 0; i < 300; i++) { // warm-up
            nat.run();
            guard.run();
        }
        long[] a = new long[n];
        long[] b = new long[n];
        int block = 50;
        for (int i = 0; i < n; i += block) {
            for (int j = i; j < Math.min(n, i + block); j++) {
                a[j] = time(nat);
            }
            for (int j = i; j < Math.min(n, i + block); j++) {
                b[j] = time(guard);
            }
        }
        p.script(new MockProvider.Ok()); // clears the attempt record
        Arrays.sort(a);
        Arrays.sort(b);
        long ma = a[n / 2] / 1000;
        long mb = b[n / 2] / 1000;
        long pa = a[(int) (n * 0.95)] / 1000;
        long pb = b[(int) (n * 0.95)] / 1000;
        String line = String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%d,%d", client, mode, n, ma, mb, pa, pb, mb - ma, pb - pa);
        System.err.println(line);
        csv.append(line).append('\n');
    }

    private static long time(Call c) throws Exception {
        long t = System.nanoTime();
        c.run();
        return System.nanoTime() - t;
    }
}
