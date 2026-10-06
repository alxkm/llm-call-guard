package callguard.bench;

import java.util.List;

/**
 * What the application saw for one call, and what the instrumentation recorded.
 *
 * @param result OK (a complete answer), ERROR (an exception reached the application), PARTIAL (the call completed
 *     normally but delivered less than the provider sent or no answer at all), or TIMEOUT_HARNESS (the harness gave up)
 * @param error the exception type chain reaching the application, outermost first; empty when none
 * @param guardKind the {@code FailureKind} found in the exception chain, or "-"
 * @param text the text delivered to the application
 * @param chunks streaming chunks delivered to the application before completion or failure
 * @param millis wall-clock time from the call to its return or failure
 * @param attempts HTTP requests that reached the provider
 * @param gapsMillis time between consecutive attempts, measured at the provider
 * @param observations framework observations stopped (Spring AI) or listener request callbacks (LangChain4j)
 * @param observationErrors framework observations stopped with an error, or listener error callbacks
 * @param meterErrorTag the value of the {@code error} tag on the framework's timers, or of the error type attribute; "-" if none
 * @param guardCalls {@code llm.guard.call} observations stopped (0 without the guard)
 * @param guardRecord outcome/error.type/attempts of the guard's call observation, or "-"
 * @param guardAttempts {@code llm.guard.attempt} observations stopped
 * @param instrumentation observation names stopped and listener callbacks, in order
 */
public record Outcome(String result, String error, String guardKind, String text, int chunks, long millis, int attempts, List<Long> gapsMillis,
        int observations, int observationErrors, String meterErrorTag, int guardCalls, String guardRecord, int guardAttempts,
        String instrumentation) {
}
