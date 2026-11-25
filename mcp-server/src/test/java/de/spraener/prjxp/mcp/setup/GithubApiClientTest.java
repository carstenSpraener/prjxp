package de.spraener.prjxp.mcp.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.mcp.hub.github.GithubApiClient;
import de.spraener.prjxp.mcp.hub.github.GithubTokenResolver;
import de.spraener.prjxp.mcp.hub.github.TokenCipher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GithubApiClientTest {
    @TempDir Path directory;
    HttpServer server;
    ConfigurationStore store;
    GithubTokenResolver resolver;
    URI base;
    AtomicReference<String> body = new AtomicReference<>("{\"login\":\"alice\"}");
    AtomicInteger status = new AtomicInteger(200), requests = new AtomicInteger(), redirected = new AtomicInteger();
    AtomicReference<String> authorization = new AtomicReference<>(), accept = new AtomicReference<>();
    boolean rateLimited;

    @BeforeEach
    void start() throws Exception {
        Files.writeString(directory.resolve("application.yaml"),
                "prjxp: {hub: {enabled: true, github-token-key-env: PHASE04_KEY}}\n");
        Files.writeString(directory.resolve(".env"), "PHASE04_KEY=passphrase\n");
        store = new ConfigurationStore(directory.resolve("application.yaml"), directory.resolve(".env"),
                new MockEnvironment(), Clock.systemUTC());
        resolver = new GithubTokenResolver(store, new TokenCipher(new HubProperties(), new MockEnvironment(), store));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/user", exchange -> {
            requests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            if (rateLimited) exchange.getResponseHeaders().set("X-RateLimit-Remaining", "0");
            exchange.getResponseHeaders().set("Location", base.resolve("redirected").toString());
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status.get(), bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            } finally { exchange.close(); }
        });
        server.createContext("/redirected", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach
    void stop() { server.stop(0); }

    GithubApiClient client() {
        return new GithubApiClient(resolver, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                base, Duration.ofSeconds(2), 1024, new ObjectMapper());
    }

    @Test
    void sendsExactHeadersAndReturnsOnlyValidatedIdentity() {
        var result = client().testToken(new GithubConfigRequest(null, "phase04-test-pat"));
        assertThat(result.valid()).isTrue();
        assertThat(result.login()).isEqualTo("alice");
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(authorization.get()).isEqualTo("Bearer phase04-test-pat");
        assertThat(accept.get()).isEqualTo("application/vnd.github+json");
        assertThat(result.toString()).doesNotContain("phase04-test-pat");
    }

    @Test
    void savedDefaultIsUsedByExistingClientWithoutRestart() {
        var cipher = new TokenCipher(new HubProperties(), new MockEnvironment(), store);
        var persistence = new SetupPersistenceService(store, new SetupTestEvidenceService(), java.util.List.of(),
                () -> cipher, () -> false);
        GithubApiClient client = client();
        for (String token : new String[]{"saved-default-one", "saved-default-two"}) {
            persistence.save(new SetupSaveRequest(persistence.readStatus().configVersion(), "github", false, null,
                    null, null, new GithubConfigRequest("PHASE04_KEY", token), null));
            assertThat(client.testToken(new GithubConfigRequest("UNSAVED_KEY", "********")).valid()).isTrue();
            assertThat(authorization.get()).isEqualTo("Bearer " + token);
        }
    }

    @Test
    void rejectsMalformedMissingBlankAndSecretBearingIdentitiesAndBoundsBodies() {
        for (String value : new String[]{"", "{", "[]", "{}", "{\"login\":null}", "{\"login\":1}",
                "{\"login\":\" \"}", "{\"login\":\"phase04-test-pat\"}", "{\"login\":\"" + "x".repeat(2048) + "\"}"}) {
            body.set(value);
            var result = client().testToken(new GithubConfigRequest(null, "phase04-test-pat"));
            assertThat(result.valid()).as(value.substring(0, Math.min(value.length(), 30))).isFalse();
            assertThat(result.login()).isNull();
            assertThat(result.message()).doesNotContain("phase04-test-pat");
        }
    }

    @Test
    void distinguishesCredentialPermissionRateLimitAndServerErrorsWithoutFollowingRedirects() {
        for (int code : new int[]{401, 403, 429, 500, 302}) {
            status.set(code);
            body.set("provider body containing phase04-test-pat");
            var result = client().testToken(new GithubConfigRequest(null, "phase04-test-pat"));
            assertThat(result.valid()).isFalse();
            assertThat(result.message()).contains(Integer.toString(code)).doesNotContain("phase04-test-pat");
        }
        rateLimited = true;
        status.set(403);
        assertThat(client().testToken(new GithubConfigRequest(null, "phase04-test-pat")).message()).contains("rate limit");
        assertThat(redirected.get()).isZero();
    }

    @Test
    void missingAndInvalidSecretsNeverTriggerNetworkRequests() {
        assertThat(client().testToken(new GithubConfigRequest(null, "")).valid()).isFalse();
        assertThat(client().testToken(new GithubConfigRequest(null, null)).valid()).isFalse();
        assertThat(client().testToken(new GithubConfigRequest(null, "${MISSING_PHASE04_PAT}")).message())
                .contains("MISSING_SECRET");
        assertThat(client().testToken(new GithubConfigRequest(null, "v1:bad-sensitive-token")).message())
                .contains("CRYPTO_FAILED").doesNotContain("bad-sensitive-token");
        assertThat(requests.get()).isZero();
    }

    @Test
    void transportErrorsAndInterruptsAreSafeAndRedirectingClientsAreRejected() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        doThrow(new IOException("phase04-test-pat")).when(http).send(any(), any());
        GithubApiClient client = new GithubApiClient(resolver, http, base, Duration.ofSeconds(1), 1024, new ObjectMapper());
        assertThat(client.testToken(new GithubConfigRequest(null, "phase04-test-pat")).message())
                .doesNotContain("phase04-test-pat");
        doThrow(new InterruptedException("phase04-test-pat")).when(http).send(any(), any());
        try {
            assertThat(client.testToken(new GithubConfigRequest(null, "phase04-test-pat")).valid()).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        when(http.followRedirects()).thenReturn(HttpClient.Redirect.ALWAYS);
        assertThatThrownBy(() -> new GithubApiClient(resolver, http, base, Duration.ofSeconds(1), 1024, new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timeoutAlsoBoundsAResponseThatStallsAfterHeaders() throws Exception {
        CountDownLatch headers = new CountDownLatch(1), release = new CountDownLatch(1);
        server.removeContext("/user");
        server.createContext("/user", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write('{');
                exchange.getResponseBody().flush();
                headers.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            } finally { exchange.close(); }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var client = new GithubApiClient(resolver, HttpClient.newHttpClient(), base,
                    Duration.ofMillis(500), 1024, new ObjectMapper());
            var pending = executor.submit(() -> client.testToken(new GithubConfigRequest(null, "phase04-test-pat")));
            assertThat(headers.await(2, TimeUnit.SECONDS)).isTrue();
            var result = pending.get(2, TimeUnit.SECONDS);
            assertThat(result.valid()).isFalse();
            assertThat(result.message()).contains("timeout").doesNotContain("phase04-test-pat");
            assertThat(result.latencyMs()).isLessThan(2000);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void prematurelyClosedBodyIsReportedWithoutProviderDetails() {
        server.removeContext("/user");
        server.createContext("/user", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 100);
                exchange.getResponseBody().write('{');
            } finally { exchange.close(); }
        });
        assertThat(client().testToken(new GithubConfigRequest(null, "phase04-test-pat")).valid()).isFalse();
    }

    @Test
    void invalidTransportConfigurationIsRejected() {
        HttpClient http = HttpClient.newHttpClient();
        for (URI uri : new URI[]{URI.create("file:///tmp/"), URI.create("http://secret@localhost/"),
                URI.create("ftp://localhost/")}) {
            assertThatThrownBy(() -> new GithubApiClient(resolver, http, uri, Duration.ofSeconds(1), 1024, new ObjectMapper()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (Duration duration : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1)}) {
            assertThatThrownBy(() -> new GithubApiClient(resolver, http, base, duration, 1024, new ObjectMapper()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new GithubApiClient(resolver, http, base, Duration.ofSeconds(1), 0, new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new GithubApiClient(resolver, new ObjectMapper())).isNotNull();
    }
}
