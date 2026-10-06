package callguard.langchain4j;

import callguard.Policy;
import callguard.jdk.JdkTransport;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.random.RandomGenerator;

/**
 * Builds a {@link GuardedHttpClient}. The connect timeout LangChain4j passes in is applied to the connection; the read
 * timeout is ignored, because the policy's response, first-event and idle timeouts replace it.
 */
public final class GuardedHttpClientBuilder implements HttpClientBuilder {
    private final Policy policy;
    private final ObservationRegistry registry;
    private final RandomGenerator random;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout;
    private Executor executor = Executors.newVirtualThreadPerTaskExecutor();

    public GuardedHttpClientBuilder(Policy policy, ObservationRegistry registry, RandomGenerator random) {
        this.policy = policy;
        this.registry = registry;
        this.random = random;
    }

    public GuardedHttpClientBuilder(Policy policy, ObservationRegistry registry) {
        this(policy, registry, RandomGenerator.getDefault());
    }

    public GuardedHttpClientBuilder executor(Executor e) {
        this.executor = e;
        return this;
    }

    @Override
    public Duration connectTimeout() {
        return connectTimeout;
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration d) {
        if (d != null) {
            this.connectTimeout = d;
        }
        return this;
    }

    @Override
    public Duration readTimeout() {
        return readTimeout;
    }

    @Override
    public HttpClientBuilder readTimeout(Duration d) {
        this.readTimeout = d;
        return this;
    }

    @Override
    public HttpClient build() {
        java.net.http.HttpClient jdk = java.net.http.HttpClient.newBuilder().connectTimeout(connectTimeout)
                .version(java.net.http.HttpClient.Version.HTTP_1_1).build();
        return new GuardedHttpClient(new JdkTransport(jdk, policy, registry, "langchain4j", random), executor);
    }
}
