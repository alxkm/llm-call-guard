package callguard;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Reads the provider's requested delay from {@code retry-after-ms} (OpenAI) or {@code Retry-After} (RFC 9110). */
public final class RetryAfter {
    private RetryAfter() {
    }

    /** Header names are matched case-insensitively. */
    public static Optional<Duration> parse(Map<String, List<String>> headers) {
        String ms = first(headers, "retry-after-ms");
        if (ms != null) {
            try {
                double v = Double.parseDouble(ms.trim());
                if (v >= 0) {
                    return Optional.of(Duration.ofMillis(Math.round(v)));
                }
            } catch (NumberFormatException ignored) {
                // fall through to Retry-After
            }
        }
        String ra = first(headers, "retry-after");
        if (ra == null) {
            return Optional.empty();
        }
        ra = ra.trim();
        try {
            double seconds = Double.parseDouble(ra);
            return seconds >= 0 ? Optional.of(Duration.ofMillis(Math.round(seconds * 1000))) : Optional.empty();
        } catch (NumberFormatException e) {
            try {
                ZonedDateTime at = ZonedDateTime.parse(ra, DateTimeFormatter.RFC_1123_DATE_TIME);
                Duration d = Duration.between(ZonedDateTime.now(at.getZone()), at);
                return Optional.of(d.isNegative() ? Duration.ZERO : d);
            } catch (RuntimeException ignored) {
                return Optional.empty();
            }
        }
    }

    private static String first(Map<String, List<String>> headers, String name) {
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase(Locale.ROOT).equals(name) && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue().get(0);
            }
        }
        return null;
    }
}
