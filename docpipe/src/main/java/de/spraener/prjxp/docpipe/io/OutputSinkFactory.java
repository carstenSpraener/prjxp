package de.spraener.prjxp.docpipe.io;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Factory for creating {@link OutputSink} instances.
 * <p>
 * This factory abstracts the creation of output mechanisms, allowing for easy substitution 
 * with mock or in-memory implementations during testing to avoid actual file I/O.
 * </p>
 */
@Service
public class OutputSinkFactory {

    /**
     * Creates an OutputSink that writes to the specified file path.
     */
    public OutputSink createSink(Path path) throws IOException {
        return new FileOutputSink(path);
    }

    /**
     * Creates an OutputSink that writes to the specified file path (String variant).
     */
    public OutputSink createSink(String path) throws IOException {
        return createSink(Path.of(path));
    }
}

