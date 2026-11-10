package de.spraener.prjxp.common.graph;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.spraener.prjxp.common.model.CodeGraph;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Jackson (de)serialization of a {@link CodeGraph} to/from a {@code graph.json} file
 * or stream.
 *
 * <p>The on-disk shape wraps the graph in a versioned envelope so future schema changes
 * can be detected on read. Stateless utility — instantiate per use.
 *
 * <p>Stream overloads never close the passed stream: the caller owns it (e.g. an
 * encrypted stream must be closed by the caller to emit the GCM auth tag).
 */
public class CodeGraphIo {

    public static final int SCHEMA_VERSION = 1;

    private final JsonMapper mapper = JsonMapper.builder()
            .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
            .disable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
            .build();

    /** Writes the graph to the given stream without closing it. */
    public void write(OutputStream out, CodeGraph graph) {
        try {
            mapper.writeValue(out, new GraphFile(SCHEMA_VERSION, graph));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write graph", e);
        }
    }

    /** Reads and returns the graph from the given stream without closing it. */
    public CodeGraph read(InputStream in) {
        try {
            return mapper.readValue(in, GraphFile.class).graph();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read graph", e);
        }
    }

    /** Writes the graph, creating parent directories as needed. */
    public void write(Path path, CodeGraph graph) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(path)) {
                write(out, graph);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write graph to " + path, e);
        }
    }

    /** Reads and returns the graph from the given file. */
    public CodeGraph read(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return read(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read graph from " + path, e);
        }
    }

    /** Versioned on-disk envelope. */
    public record GraphFile(int schemaVersion, CodeGraph graph) {
    }
}
