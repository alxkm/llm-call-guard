package callguard;

import java.io.IOException;
import java.time.Duration;

/** The raw bytes of a response body, read with a per-read timeout. Each transport supplies one. */
public interface ChunkSource extends AutoCloseable {

    /** Thrown when no byte arrived within the timeout. */
    final class Silence extends IOException {
        public Silence(Duration timeout) {
            super("no data for " + timeout.toMillis() + " ms");
        }
    }

    /** The next bytes, at least one; {@code null} at the end of the body. */
    byte[] read(Duration timeout) throws IOException;

    @Override
    void close();
}
