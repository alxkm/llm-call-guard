package callguard;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * What a complete answer looks like on the OpenAI-compatible wire protocol (OpenAI, Azure OpenAI, Ollama, vLLM, and
 * others). A non-streaming body must be one JSON object, and if it has {@code choices}, at least one. A stream must
 * end with {@code data: [DONE]} or with a chunk that carries a {@code finish_reason}; a {@code data:} event that is
 * an {@code error} object, or not JSON, ends it with a failure.
 */
public final class WireCheck {
    private WireCheck() {
    }

    /** The meaning of one server-sent event. */
    public enum Event {
        /** Content or metadata; the stream continues. */
        DATA,
        /** A chunk with a non-null {@code finish_reason}: the answer is complete. */
        FINISH,
        /** {@code [DONE]}. */
        DONE,
        /** An {@code error} object. */
        ERROR,
        /** Not JSON. */
        MALFORMED,
        /** No data field (a comment or keep-alive). */
        NONE
    }

    /** {@code null} if the body is a complete answer, otherwise why not. */
    public static FailureKind checkBody(byte[] body) {
        Object v;
        try {
            v = Json.parse(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return FailureKind.MALFORMED_RESPONSE;
        }
        if (!(v instanceof Map<?, ?> m)) {
            return FailureKind.MALFORMED_RESPONSE;
        }
        if (m.get("error") instanceof Map<?, ?>) {
            return FailureKind.MALFORMED_RESPONSE;
        }
        if (m.containsKey("choices") && !(m.get("choices") instanceof List<?> c && !c.isEmpty())) {
            return FailureKind.EMPTY_RESPONSE;
        }
        return null;
    }

    /** @param data the concatenated {@code data:} lines of one event, or {@code null} if it had none */
    public static Event classify(String data) {
        if (data == null) {
            return Event.NONE;
        }
        String d = data.strip();
        if (d.equals("[DONE]")) {
            return Event.DONE;
        }
        Object v;
        try {
            v = Json.parse(d);
        } catch (IllegalArgumentException e) {
            return Event.MALFORMED;
        }
        if (!(v instanceof Map<?, ?> m)) {
            return Event.MALFORMED;
        }
        if (m.get("error") instanceof Map<?, ?>) {
            return Event.ERROR;
        }
        if (m.get("choices") instanceof List<?> choices) {
            for (Object c : choices) {
                if (c instanceof Map<?, ?> cm && cm.get("finish_reason") instanceof String) {
                    return Event.FINISH;
                }
            }
        }
        return Event.DATA;
    }
}
