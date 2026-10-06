package callguard.bench;

import callguard.Policy;
import callguard.bench.Clients.ClientUnderTest;
import callguard.bench.Clients.Setup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * The guard against a real model server: a local Ollama (OpenAI-compatible endpoint), every client, both modes,
 * without and with the guard. The question is whether the guard's checks reject anything a real server sends when
 * nothing is wrong (they must not), and what the guard costs relative to real generation time. Writes
 * {@code results/ollama.csv}.
 *
 * <pre>java -cp &lt;classpath&gt; callguard.bench.OllamaCheck [calls] [model] [base url]</pre>
 */
public final class OllamaCheck {
    public static void main(String[] args) throws Exception {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 25;
        String model = args.length > 1 ? args[1] : "llama3.2";
        String base = args.length > 2 ? args[2] : "http://localhost:11434/v1";
        Setup guard = new Clients.Guarded("guard-defaults", Policy.defaults(), 7);
        StringBuilder csv = new StringBuilder("client,mode,setup,rep,result,error,guard_record,millis,chunks,text_chars\n");
        for (ClientUnderTest c : List.of(Clients.springAi(base, model), Clients.langChain4j(base, model), Clients.plain(base, model))) {
            for (String mode : List.of("sync", "stream")) {
                for (Setup s : List.of(Clients.DEFAULTS, guard)) {
                    if (!c.supports(s)) {
                        continue;
                    }
                    for (int rep = 1; rep <= n; rep++) {
                        Outcome o = mode.equals("sync") ? c.sync(null, s) : c.stream(null, s);
                        String result = o.error().isEmpty() && !o.text().isBlank() ? "OK" : o.error().isEmpty() ? "EMPTY" : "ERROR";
                        String label = s instanceof Clients.Guarded ? "guard" : "defaults";
                        String line = String.join(",", c.name(), mode, label, Integer.toString(rep), result, "\"" + o.error() + "\"", o.guardRecord(),
                                Long.toString(o.millis()), Integer.toString(o.chunks()), Integer.toString(o.text().length()));
                        System.out.println(line);
                        csv.append(line).append('\n');
                    }
                }
            }
        }
        Files.createDirectories(Path.of("results"));
        Files.writeString(Path.of("results/ollama.csv"), csv.toString(), StandardCharsets.UTF_8);
        Files.writeString(Path.of("results/ollama_environment.txt"), "run: " + ZonedDateTime.now() + "\nmodel: " + model + "\nbase: " + base
                + "\ncalls per cell: " + n + "\nguard policy: " + Policy.defaults() + "\n", StandardCharsets.UTF_8);
        System.exit(0);
    }
}
