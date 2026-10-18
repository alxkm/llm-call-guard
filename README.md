# llm-call-guard

Code and results for the paper *One Reliability Contract Beneath Two Frameworks: Uniform Handling of Provider
Failures for Language-Model Calls in Java* (O. Klymenko, 2026). Figures: `python scripts/figures.py` → `results/figures/`.

A reliability layer for calls to OpenAI-compatible model endpoints (OpenAI, Azure OpenAI, Ollama, vLLM and others)
that sits **beneath** the Java integration frameworks, at the HTTP boundary, so that one implementation gives the
same behaviour under **Spring AI**, under **LangChain4j**, and with no framework at all.

It implements a six-clause contract:

| | Clause | What the guard does |
|---|---|---|
| C1 | retry exactly the transient failures | 5xx, 429, 408, no response, and a connection that broke before any byte are retried; other 4xx, a malformed or empty 2xx body, and anything after the first delivered event are not |
| C2 | wait as long as the provider asks | `retry-after-ms` and `Retry-After` (seconds or HTTP date) are honoured; a request to wait longer than `retryAfterCap` ends the call at once |
| C3 | never present an incomplete answer as complete | a stream that ends without `data: [DONE]` or a `finish_reason`, an error event, or a non-JSON event raises an exception after the events that came before it have been delivered; an empty `choices` array is an error |
| C4 | bound silence, not length | separate timeouts for a complete non-streaming response, for the first event of a stream, and for the silence between events; a total budget across attempts |
| C5 | record every outcome as it was | one Micrometer observation `llm.guard.call` per call, ending as success or failure with `error.type` = the failure kind, including truncated, failed and abandoned streams |
| C6 | make every attempt visible | one child observation `llm.guard.attempt` per request, with status, kind and the retry decision |

## Install

The framework's own retries must be off, and its own timeout longer than the guard's total budget.

**Spring AI** (OkHttp application interceptor):

```java
GuardInterceptor guard = new GuardInterceptor(Policy.defaults(), observationRegistry);
OpenAiChatModel model = OpenAiChatModel.builder()
        .options(OpenAiChatOptions.builder().model("gpt-4o-mini").maxRetries(0).timeout(Duration.ofMinutes(10)).build())
        .httpClientBuilderCustomizer(b -> b.interceptor(guard))
        .build();
```

**LangChain4j** (its `HttpClient` SPI):

```java
var model = OpenAiChatModel.builder().modelName("gpt-4o-mini").maxRetries(0)
        .httpClientBuilder(new GuardedHttpClientBuilder(Policy.defaults(), observationRegistry))
        .build();
```

**No framework** (`java.net.http`):

```java
JdkTransport t = new JdkTransport(Policy.defaults(), observationRegistry);
JdkTransport.Response r = t.send(new JdkTransport.Request("POST", uri, headers, body));          // complete and checked, or the provider's final error
JdkTransport.StreamResponse s = t.stream(new JdkTransport.Request("POST", uri, headers, body));  // events checked one by one
```

The core (`callguard`, `callguard.jdk`) depends only on the JDK and `micrometer-observation`; the adapters need
OkHttp or `langchain4j-http-client`, which the frameworks already bring.

## Reproduce

Requirements: JDK 21, Maven 3.9. For the real-server check, a local [Ollama](https://ollama.com) with `llama3.2` and
`qwen2.5:7b`.

```
mvn -q package
CP="target/classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)"
java -cp "$CP" callguard.bench.Study 3          # fault injection, before and after (about 30 min)
python scripts/summarize.py                      # results/summary.md, results/contract.csv
java -cp "$CP" callguard.bench.Overhead 2000     # latency added to a successful call
java -cp "$CP" callguard.bench.OllamaCheck 50 llama3.2
```

| file | content |
|---|---|
| `results/outcomes.csv` | every run: scenario, client, mode, setup, what the application saw, attempts and gaps measured at the provider, what the instrumentation recorded |
| `results/summary.md`, `results/contract.csv` | the six clauses per cell and per client and setup |
| `results/overhead.csv` | median and P95 latency with and without the guard |
| `results/ollama_*.csv` | the guard against a real model server: no call may be rejected when nothing is wrong |

## Layout

```
src/main/java/callguard/              the core: Policy, RetryRules, RetryAfter, FailureKind, WireCheck, GuardedSseStream, CallObservations, Json
src/main/java/callguard/jdk/          the guard over java.net.http
src/main/java/callguard/okhttp/       the guard as an OkHttp interceptor (Spring AI)
src/main/java/callguard/langchain4j/  the guard as a LangChain4j HttpClient
src/main/java/callguard/bench/        evaluation: scripted provider, clients, study, overhead, real-server check
```

## Limits

- The wire checks are for the OpenAI-compatible protocol (chat completions, JSON bodies, server-sent events).
- A failure the guard detects reaches the application inside the framework's own wrapper exception (for example
  `OpenAIIoException` in Spring AI); `GuardException.find(e)` recovers the kind.
- The OkHttp adapter waits for a retry on the calling thread, which for streaming in Spring AI is an OkHttp
  dispatcher thread.
- Whether a request that timed out may be repeated is a policy decision: the provider may have processed it.
  `NO_RESPONSE` is transient by default; set `maxAttempts` to 1 where repetition is unacceptable.

## Licence

MIT.
