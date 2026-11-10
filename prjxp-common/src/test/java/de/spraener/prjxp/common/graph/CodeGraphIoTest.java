package de.spraener.prjxp.common.graph;

import de.spraener.prjxp.common.model.CodeGraph;
import de.spraener.prjxp.common.model.EdgeType;
import de.spraener.prjxp.common.model.GraphEdge;
import de.spraener.prjxp.common.model.GraphNode;
import de.spraener.prjxp.common.transfer.TransferCrypto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeGraphIoTest {

    private final CodeGraphIo io = new CodeGraphIo();

    @Test
    void writeReadRoundtrip(@TempDir Path tmp) {
        CodeGraph graph = new CodeGraph(
                List.of(
                        GraphNode.type("de.spraener.Foo", "src/Foo.java", "java").withRating("c0", 0.8),
                        GraphNode.method("de.spraener.Foo#bar", "src/Foo.java", "java").withRating("c0", 0.2)),
                List.of(
                        new GraphEdge("de.spraener.Foo", "de.spraener.Foo#bar", EdgeType.CALL, false),
                        new GraphEdge("de.spraener.Foo", "java.lang.String", EdgeType.IMPORT, true)));

        Path file = tmp.resolve("graph.json");
        io.write(file, graph);

        CodeGraph read = io.read(file);

        assertThat(read.nodes()).hasSize(2);
        assertThat(read.edges()).hasSize(2);
        assertThat(read.nodeById().get("de.spraener.Foo").clusterId()).isEqualTo("c0");
        assertThat(read.nodeById().get("de.spraener.Foo").weight()).isEqualTo(0.8);
        assertThat(read.nodeById().get("de.spraener.Foo#bar").isMethod()).isTrue();
        assertThat(read.edges().get(1).external()).isTrue();
        assertThat(read.edges().get(1).type()).isEqualTo(EdgeType.IMPORT);
    }

    @Test
    void emptyGraphRoundtrip(@TempDir Path tmp) {
        Path file = tmp.resolve("empty.json");
        io.write(file, CodeGraph.empty());
        CodeGraph read = io.read(file);
        assertThat(read.nodes()).isEmpty();
        assertThat(read.edges()).isEmpty();
    }

    @Test
    void writeCreatesParentDirectories(@TempDir Path tmp) {
        Path file = tmp.resolve("a/b/c/graph.json");
        io.write(file, CodeGraph.empty());
        assertThat(file).exists();
    }

    @Test
    void streamRoundtripDoesNotCloseCallerStreams() throws Exception {
        CodeGraph graph = new CodeGraph(
                List.of(GraphNode.type("de.spraener.Foo", "src/Foo.java", "java").withRating("c0", 0.8)),
                List.of(new GraphEdge("de.spraener.Foo", "java.lang.String", EdgeType.IMPORT, true)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        io.write(out, graph);
        out.write(0); // must not throw: the caller still owns the stream
        byte[] bytes = out.toByteArray();
        assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("\"schemaVersion\":1");

        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        CodeGraph read = io.read(in);
        in.read(); // must not throw: the caller still owns the stream

        assertThat(read.nodes()).hasSize(1);
        assertThat(read.edges()).hasSize(1);
        assertThat(read.nodeById().get("de.spraener.Foo").clusterId()).isEqualTo("c0");
        assertThat(read.nodeById().get("de.spraener.Foo").weight()).isEqualTo(0.8);
    }

    @Test
    void encryptedStreamRoundtrip() throws Exception {
        char[] password = "geheim".toCharArray();
        CodeGraph graph = new CodeGraph(
                List.of(
                        GraphNode.type("de.spraener.Foo", "src/Foo.java", "java").withRating("c0", 0.8),
                        GraphNode.method("de.spraener.Foo#bar", "src/Foo.java", "java").withRating("c0", 0.2)),
                List.of(
                        new GraphEdge("de.spraener.Foo", "de.spraener.Foo#bar", EdgeType.CALL, false),
                        new GraphEdge("de.spraener.Foo", "java.lang.String", EdgeType.IMPORT, true)));

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        OutputStream encrypted = TransferCrypto.openEncryptedOutputStream(raw, password);
        io.write(encrypted, graph);
        encrypted.close(); // emits the GCM auth tag

        byte[] bytes = raw.toByteArray();
        InputStream decrypted = TransferCrypto.openAuto(new ByteArrayInputStream(bytes), password);
        CodeGraph read = io.read(decrypted);

        assertThat(read.nodes()).hasSize(2);
        assertThat(read.edges()).hasSize(2);
        assertThat(read.nodeById().get("de.spraener.Foo").clusterId()).isEqualTo("c0");
        assertThat(read.nodeById().get("de.spraener.Foo").weight()).isEqualTo(0.8);
        assertThat(read.nodeById().get("de.spraener.Foo#bar").isMethod()).isTrue();
        assertThat(read.edges().get(1).external()).isTrue();
    }

    @Test
    void readMissingPathThrowsUncheckedIOException(@TempDir Path tmp) {
        assertThatThrownBy(() -> io.read(tmp.resolve("nope.json")))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void writeToUnwritablePathThrowsUncheckedIOException(@TempDir Path tmp) throws Exception {
        Path blocker = tmp.resolve("blocker");
        Files.createFile(blocker); // parent is a regular file -> Files.createDirectories fails
        assertThatThrownBy(() -> io.write(blocker.resolve("graph.json"), CodeGraph.empty()))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void readFromThrowingStreamThrowsUncheckedIOException() {
        InputStream in = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("boom");
            }
        };
        assertThatThrownBy(() -> io.read(in))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void writeToThrowingStreamThrowsUncheckedIOException() {
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("boom");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("boom");
            }
        };
        assertThatThrownBy(() -> io.write(out, CodeGraph.empty()))
                .isInstanceOf(UncheckedIOException.class);
    }
}
