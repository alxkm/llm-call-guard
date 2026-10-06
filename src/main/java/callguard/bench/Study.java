package callguard.bench;

import callguard.bench.Clients.ClientUnderTest;
import callguard.bench.Clients.Setup;
import callguard.bench.MockProvider.Behaviour;
import callguard.bench.MockProvider.EmptyChoices;
import callguard.bench.MockProvider.Hang;
import callguard.bench.MockProvider.Malformed;
import callguard.bench.MockProvider.Ok;
import callguard.bench.MockProvider.SlowStream;
import callguard.bench.MockProvider.Status;
import callguard.bench.MockProvider.StreamErrorEvent;
import callguard.bench.MockProvider.StreamTruncate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Before and after: every fault scenario of the failure study, against Spring AI and LangChain4j with their own
 * settings (defaults, and an explicit configuration) and with the guard installed, and against the guard alone over
 * {@code java.net.http}. Writes {@code results/outcomes.csv}.
 *
 * <pre>java -cp &lt;classpath&gt; callguard.bench.Study [repetitions] [--quick] [--only S01,S02] [--setups guard] [--out file.csv]</pre>
 *
 * {@code -Dguard.firstEventSeconds=N} changes the policy's first-event timeout (default 3 s).
 */
public final class Study {
    record Scenario(String id, String description, boolean sync, boolean stream, List<Setup> baseline, Behaviour... script) {
    }

    static final List<Setup> BOTH = List.of(Clients.DEFAULTS, Clients.EXPLICIT);
    static final List<Setup> EXPLICIT_ONLY = List.of(Clients.EXPLICIT);
    static final List<Setup> DEFAULTS_ONLY = List.of(Clients.DEFAULTS);

    static List<Scenario> scenarios(boolean quick) {
        List<Scenario> s = new ArrayList<>(List.of(
                new Scenario("S01", "normal response", true, true, BOTH, new Ok()),
                new Scenario("S02", "500 once, then normal", true, true, BOTH, new Status(500, Map.of()), new Ok()),
                new Scenario("S03", "503 on every attempt", true, true, BOTH, new Status(503, Map.of())),
                new Scenario("S04", "429 with Retry-After: 3 once, then normal", true, true, BOTH, new Status(429, Map.of("Retry-After", "3")), new Ok()),
                new Scenario("S05", "429 with Retry-After: 3 on every attempt", true, false, BOTH, new Status(429, Map.of("Retry-After", "3"))),
                new Scenario("S06", "400 on every attempt", true, true, BOTH, new Status(400, Map.of())),
                new Scenario("S07", "401 on every attempt", true, false, BOTH, new Status(401, Map.of())),
                new Scenario("S08", "no response for 5 s once, then normal", true, true, EXPLICIT_ONLY, new Hang(5_000), new Ok()),
                new Scenario("S09", "200 with a body that is not JSON", true, false, BOTH, new Malformed()),
                new Scenario("S10", "200 with an empty choices array", true, false, BOTH, new EmptyChoices()),
                new Scenario("S11", "stream closed after 3 of 8 chunks, no finish reason or [DONE]", false, true, BOTH, new StreamTruncate(3)),
                new Scenario("S12", "stream with an error event after 3 chunks", false, true, BOTH, new StreamErrorEvent(3)),
                new Scenario("S13", "stream with 1.5 s between chunks", false, true, EXPLICIT_ONLY, new SlowStream(1_500))));
        if (!quick) {
            s.add(new Scenario("S14", "no response for 70 s on every attempt", true, false, DEFAULTS_ONLY, new Hang(70_000)));
            s.add(new Scenario("S15", "stream lasting 68 s, 8.5 s between chunks", false, true, DEFAULTS_ONLY, new SlowStream(8_500)));
        }
        return s;
    }

    public static void main(String[] args) throws IOException {
        int reps = args.length > 0 && !args[0].startsWith("--") ? Integer.parseInt(args[0]) : 3;
        boolean quick = List.of(args).contains("--quick");
        String only = option(args, "--only");
        String setups = option(args, "--setups"); // e.g. "guard" to run only the guarded rows
        Path out = Path.of("results");
        Files.createDirectories(out);
        String header = "client,mode,scenario,description,setup,rep,result,error,guard_kind,text_delivered,chunks,millis,attempts,gaps_ms,"
                + "observations,observation_errors,meter_error_tag,guard_calls,guard_record,guard_attempts,instrumentation\n";
        StringBuilder csv = new StringBuilder();
        try (MockProvider p = new MockProvider()) {
            for (Scenario sc : scenarios(quick)) {
                if (only != null && !List.of(only.split(",")).contains(sc.id())) {
                    continue;
                }
                List<Setup> all = new ArrayList<>(sc.baseline());
                all.add(Clients.GUARDED);
                for (ClientUnderTest c : List.of(Clients.SPRING_AI, Clients.LANGCHAIN4J, Clients.PLAIN)) {
                    for (String mode : List.of("sync", "stream")) {
                        if (mode.equals("sync") ? !sc.sync() : !sc.stream()) {
                            continue;
                        }
                        for (Setup setup : all) {
                            if (!c.supports(setup) || (setups != null && !List.of(setups.split(",")).contains(label(setup)))) {
                                continue;
                            }
                            int n = sc.id().equals("S14") || sc.id().equals("S15") ? 1 : reps;
                            for (int rep = 1; rep <= n; rep++) {
                                p.script(sc.script());
                                Outcome o = mode.equals("sync") ? c.sync(p, setup) : c.stream(p, setup);
                                System.out.printf("%-4s %-19s %-6s %-8s #%d %-7s att=%d gaps=%s guard=%s kind=%s %s%n", sc.id(), c.name(), mode, label(setup), rep,
                                        o.result(), o.attempts(), o.gapsMillis(), o.guardRecord(), o.guardKind(), o.error());
                                csv.append(String.join(",", c.name(), mode, sc.id(), q(sc.description()), label(setup), Integer.toString(rep), o.result(),
                                        q(o.error()), o.guardKind(), q(o.text()), Integer.toString(o.chunks()), Long.toString(o.millis()),
                                        Integer.toString(o.attempts()), q(o.gapsMillis().stream().map(String::valueOf).collect(Collectors.joining(" "))),
                                        Integer.toString(o.observations()), Integer.toString(o.observationErrors()), q(o.meterErrorTag()),
                                        Integer.toString(o.guardCalls()), q(o.guardRecord()), Integer.toString(o.guardAttempts()), q(o.instrumentation())))
                                        .append('\n');
                            }
                        }
                    }
                }
            }
        }
        String name = option(args, "--out") != null ? option(args, "--out") : quick ? "outcomes_quick.csv" : "outcomes.csv";
        Path file = out.resolve(name);
        if (only != null && Files.exists(file) && option(args, "--out") == null) {
            Files.writeString(file, csv.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            System.out.println("appended " + only + " to " + file);
        } else {
            Files.writeString(file, header + csv, StandardCharsets.UTF_8);
            Files.writeString(out.resolve(name.equals("outcomes.csv") ? "environment.txt" : "environment_" + name.replace(".csv", "") + ".txt"), "run: " + ZonedDateTime.now() + "\njava: " + System.getProperty("java.vm.name") + " "
                    + System.getProperty("java.version") + "\nos: " + System.getProperty("os.name") + "\nspring-ai 2.0.1, openai-java-core 4.49.0, "
                    + "langchain4j 1.21.0, langchain4j-micrometer-metrics 1.21.0-beta31, micrometer 1.17.1, okhttp 4.12.0\nrepetitions: " + reps
                    + "\nguard policy: " + Clients.STUDY_POLICY + "\n", StandardCharsets.UTF_8);
            System.out.println("wrote " + file);
        }
        System.exit(0);
    }

    static String label(Setup s) {
        return switch (s) {
            case Clients.Native n -> n == Clients.DEFAULTS ? "defaults" : "explicit";
            case Clients.Guarded g -> "guard";
        };
    }

    private static String option(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return null;
    }

    private static String q(String s) {
        return "\"" + s.replace("\"", "'").replace("\n", " ") + "\"";
    }
}
