package de.spraener.prjxp.mcp.hub.dep;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 02: {@link MavenCentralClient} against a local {@link HttpServer} on an
 * ephemeral port — no real network access.
 */
class MavenCentralClientTest {

    private static final MavenCoordinates COORD = new MavenCoordinates("org.example", "demo", "1.0.0");
    private static final String POM_PATH = "/org/example/demo/1.0.0/demo-1.0.0.pom";
    private static final String SOURCES_PATH = "/org/example/demo/1.0.0/demo-1.0.0-sources.jar";

    private record Route(int status, byte[] body) {
        static Route of(int status, String body) {
            return new Route(status, body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static HttpServer server;
    private static String repoBase;
    private static final Map<String, Route> routes = new ConcurrentHashMap<>();
    private static final AtomicReference<String> lastRequestedPath = new AtomicReference<>();

    @TempDir
    Path tempDir;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            lastRequestedPath.set(path);
            Route route = routes.get(path);
            int status = route == null ? 404 : route.status();
            byte[] body = route == null ? new byte[0] : route.body();
            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                }
            }
            exchange.close();
        });
        server.start();
        repoBase = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void resetRoutes() {
        routes.clear();
        lastRequestedPath.set(null);
    }

    private MavenCentralClient client(long maxBytes) {
        return new MavenCentralClient(repoBase, Duration.ofSeconds(5), Duration.ofSeconds(5), maxBytes);
    }

    @Test
    void fetchPomReturnsBody() throws MavenDependencyException {
        routes.put(POM_PATH, Route.of(200, "<project><artifactId>demo</artifactId></project>"));

        String pom = client(1024).fetchPom(COORD);

        assertThat(pom).isEqualTo("<project><artifactId>demo</artifactId></project>");
    }

    @Test
    void fetchPomThrowsOn404() {
        assertThatThrownBy(() -> client(1024).fetchPom(COORD))
                .isInstanceOf(MavenDependencyException.class)
                .hasMessageContaining("404");
    }

    @Test
    void fetchPomThrowsOn500() {
        routes.put(POM_PATH, Route.of(500, "boom"));

        assertThatThrownBy(() -> client(1024).fetchPom(COORD))
                .isInstanceOf(MavenDependencyException.class)
                .hasMessageContaining("500");
    }

    @Test
    void existsTrueWhenArtifactServed() throws MavenDependencyException {
        routes.put(SOURCES_PATH, Route.of(200, "jar-bytes"));

        assertThat(client(1024).exists(COORD, "sources")).isTrue();
    }

    @Test
    void existsFalseWhenMissing() throws MavenDependencyException {
        assertThat(client(1024).exists(COORD, "sources")).isFalse();
    }

    @Test
    void existsFalseWhenGone() throws MavenDependencyException {
        routes.put(SOURCES_PATH, Route.of(410, ""));

        assertThat(client(1024).exists(COORD, "sources")).isFalse();
    }

    @Test
    void existsThrowsOn500() {
        routes.put(SOURCES_PATH, Route.of(500, "boom"));

        assertThatThrownBy(() -> client(1024).exists(COORD, "sources"))
                .isInstanceOf(MavenDependencyException.class)
                .hasMessageContaining("500");
    }

    @Test
    void downloadWritesBodyToTarget() throws Exception {
        routes.put(SOURCES_PATH, Route.of(200, "jar-content"));
        Path target = tempDir.resolve("org/example/demo/1.0.0/demo-1.0.0-sources.jar");

        client(1024).download(COORD, "sources", target);

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("jar-content");
    }

    @Test
    void downloadDeletesTargetAndThrowsWhenOversized() throws IOException {
        routes.put(SOURCES_PATH, Route.of(200, "x".repeat(100)));
        Path target = tempDir.resolve("org/example/demo/1.0.0/demo-1.0.0-sources.jar");

        assertThatThrownBy(() -> client(10).download(COORD, "sources", target))
                .isInstanceOf(MavenDependencyException.class)
                .hasMessageContaining("size limit");
        assertThat(target).doesNotExist();
    }

    @Test
    void downloadDeletesTargetAndThrowsOn500() throws IOException {
        routes.put(SOURCES_PATH, Route.of(500, "boom"));
        Path target = tempDir.resolve("org/example/demo/1.0.0/demo-1.0.0-sources.jar");

        assertThatThrownBy(() -> client(1024).download(COORD, "sources", target))
                .isInstanceOf(MavenDependencyException.class)
                .hasMessageContaining("500");
        assertThat(target).doesNotExist();
    }

    @Test
    void trailingSlashInRepoBaseIsNormalized() throws MavenDependencyException {
        routes.put(POM_PATH, Route.of(200, "<project/>"));

        new MavenCentralClient(repoBase, Duration.ofSeconds(5), Duration.ofSeconds(5), 1024).fetchPom(COORD);
        String pathWithoutSlash = lastRequestedPath.get();
        new MavenCentralClient(repoBase + "/", Duration.ofSeconds(5), Duration.ofSeconds(5), 1024).fetchPom(COORD);

        assertThat(lastRequestedPath.get()).isEqualTo(pathWithoutSlash);
        assertThat(lastRequestedPath.get()).isEqualTo(POM_PATH);
    }
}
