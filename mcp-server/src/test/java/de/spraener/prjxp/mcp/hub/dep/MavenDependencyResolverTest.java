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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 03: {@link MavenDependencyResolver} — bounded BFS over a fake POM graph
 * served by a local {@link HttpServer} on an ephemeral port (no real network).
 */
class MavenDependencyResolverTest {

    private record Route(int status, byte[] body) {
        static Route of(int status, String body) {
            return new Route(status, body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final MavenCoordinates LIB_A = new MavenCoordinates("org.example", "lib-a", "1.0.0");
    private static final MavenCoordinates LIB_B = new MavenCoordinates("org.example", "lib-b", "2.0.0");
    private static final MavenCoordinates LIB_C = new MavenCoordinates("org.example", "lib-c", "3.0.0");

    private static final String LIB_A_POM = """
            <project>
              <groupId>org.example</groupId>
              <artifactId>lib-a</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>lib-c</artifactId>
                  <version>3.0.0</version>
                </dependency>
              </dependencies>
            </project>
            """;

    private static final String LIB_B_POM = """
            <project>
              <groupId>org.example</groupId>
              <artifactId>lib-b</artifactId>
              <version>2.0.0</version>
            </project>
            """;

    private static final String ROOT_POM = """
            <project>
              <groupId>org.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>lib-a</artifactId>
                  <version>1.0.0</version>
                </dependency>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>lib-b</artifactId>
                  <version>2.0.0</version>
                </dependency>
              </dependencies>
            </project>
            """;

    private static final String ROOT_POM_WITH_TEST_DEP = """
            <project>
              <groupId>org.example</groupId>
              <artifactId>root</artifactId>
              <version>1.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>lib-a</artifactId>
                  <version>1.0.0</version>
                </dependency>
                <dependency>
                  <groupId>org.example</groupId>
                  <artifactId>lib-b</artifactId>
                  <version>2.0.0</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>
            """;

    private static HttpServer server;
    private static String repoBase;
    private static final Map<String, Route> routes = new ConcurrentHashMap<>();

    @TempDir
    Path tempDir;

    private MavenDependencyResolver resolver;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
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
    void setUp() {
        routes.clear();
        resolver = new MavenDependencyResolver(
                new MavenCentralClient(repoBase, Duration.ofSeconds(5), Duration.ofSeconds(5), 1024 * 1024),
                new MavenPomParser());
    }

    private void writeRootPom(String pomXml) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), pomXml);
    }

    private static DependencyResolveConfig config(int maxDepth) {
        return new DependencyResolveConfig(maxDepth, List.of());
    }

    @Test
    void supportsTrueWhenPomXmlExists() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");

        assertThat(resolver.supports(tempDir)).isTrue();
    }

    @Test
    void supportsFalseWithoutPomXml() {
        assertThat(resolver.supports(tempDir)).isFalse();
    }

    @Test
    void resolveReturnsEmptyWhenPomXmlMissing() throws MavenDependencyException {
        DependencyResolution res = resolver.resolve(tempDir, config(2));

        assertThat(res.raw()).isEmpty();
        assertThat(res.config()).isEqualTo(config(2));
    }

    @Test
    void resolveWalksTransitiveDependenciesWithDepths() throws Exception {
        routes.put("/org/example/lib-a/1.0.0/lib-a-1.0.0.pom", Route.of(200, LIB_A_POM));
        routes.put("/org/example/lib-b/2.0.0/lib-b-2.0.0.pom", Route.of(200, LIB_B_POM));
        writeRootPom(ROOT_POM);

        DependencyResolution res = resolver.resolve(tempDir, config(2));

        assertThat(res.raw()).containsExactly(
                new RawDependency(LIB_A, 1),
                new RawDependency(LIB_B, 1),
                new RawDependency(LIB_C, 2));
    }

    @Test
    void resolveStopsExpandingAtMaxDepth() throws Exception {
        routes.put("/org/example/lib-a/1.0.0/lib-a-1.0.0.pom", Route.of(200, LIB_A_POM));
        routes.put("/org/example/lib-b/2.0.0/lib-b-2.0.0.pom", Route.of(200, LIB_B_POM));
        writeRootPom(ROOT_POM);

        DependencyResolution res = resolver.resolve(tempDir, config(1));

        assertThat(res.raw()).containsExactly(
                new RawDependency(LIB_A, 1),
                new RawDependency(LIB_B, 1));
    }

    @Test
    void resolveExcludesTestScopedDependencies() throws Exception {
        routes.put("/org/example/lib-a/1.0.0/lib-a-1.0.0.pom", Route.of(200, LIB_A_POM));
        writeRootPom(ROOT_POM_WITH_TEST_DEP);

        DependencyResolution res = resolver.resolve(tempDir, config(2));

        // lib-b (test scope) is excluded; lib-a is expanded and brings in lib-c.
        assertThat(res.raw()).containsExactly(
                new RawDependency(LIB_A, 1),
                new RawDependency(LIB_C, 2));
    }

    @Test
    void resolveResolvesVersionFromDependencyManagement() throws Exception {
        writeRootPom("""
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>root</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>managed-lib</artifactId>
                        <version>4.0.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>managed-lib</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        DependencyResolution res = resolver.resolve(tempDir, config(1));

        assertThat(res.raw()).containsExactly(
                new RawDependency(new MavenCoordinates("org.example", "managed-lib", "4.0.0"), 1));
    }

    @Test
    void resolveSkipsDependencyWithUnresolvableVersion() throws Exception {
        routes.put("/org/example/lib-a/1.0.0/lib-a-1.0.0.pom", Route.of(200, LIB_A_POM));
        writeRootPom("""
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>root</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>lib-a</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>no-version-lib</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        DependencyResolution res = resolver.resolve(tempDir, config(1));

        assertThat(res.raw()).containsExactly(new RawDependency(LIB_A, 1));
    }

    @Test
    void resolveSkipsSubtreeWhenPomFetchFails() throws Exception {
        // lib-a's POM 404s (no route); lib-b is served.
        routes.put("/org/example/lib-b/2.0.0/lib-b-2.0.0.pom", Route.of(200, LIB_B_POM));
        writeRootPom(ROOT_POM);

        DependencyResolution res = resolver.resolve(tempDir, config(2));

        assertThat(res.raw()).containsExactly(
                new RawDependency(LIB_A, 1),
                new RawDependency(LIB_B, 1));
    }

    @Test
    void resolveResolvesVersionFromParentDependencyManagement() throws Exception {
        routes.put("/org/example/parent-pom/3.0.0/parent-pom-3.0.0.pom", Route.of(200, """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>parent-pom</artifactId>
                  <version>3.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>parent-managed</artifactId>
                        <version>5.0.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """));
        writeRootPom("""
                <project>
                  <parent>
                    <groupId>org.example</groupId>
                    <artifactId>parent-pom</artifactId>
                    <version>3.0.0</version>
                  </parent>
                  <artifactId>child</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>parent-managed</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        DependencyResolution res = resolver.resolve(tempDir, config(1));

        assertThat(res.raw()).containsExactly(
                new RawDependency(new MavenCoordinates("org.example", "parent-managed", "5.0.0"), 1));
    }

    @Test
    void resolveSkipsDependencyWhenParentPomUnfetchable() throws Exception {
        // Parent POM 404s (no route) → version lookup fails → dependency skipped, no exception.
        writeRootPom("""
                <project>
                  <parent>
                    <groupId>org.example</groupId>
                    <artifactId>missing-parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>child</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>parent-managed</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);

        DependencyResolution res = resolver.resolve(tempDir, config(1));

        assertThat(res.raw()).isEmpty();
    }
}
