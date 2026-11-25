package de.spraener.prjxp.mcp.hub.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.mcp.setup.GithubConfigRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
public class GithubApiClient {
    private final GithubTokenResolver tokens;
    private final HttpClient http;
    private final URI endpoint;
    private final Duration timeout;
    private final int maxBytes;
    private final ObjectMapper mapper;

    @Autowired
    public GithubApiClient(GithubTokenResolver tokens, ObjectMapper mapper) {
        this(tokens, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("https://api.github.com/"), Duration.ofSeconds(15), 65536, mapper);
    }

    public GithubApiClient(GithubTokenResolver tokens, HttpClient http, URI base,
                           Duration timeout, int maxBytes, ObjectMapper mapper) {
        if (http.followRedirects() != HttpClient.Redirect.NEVER || maxBytes <= 0 || timeout.isZero() || timeout.isNegative()
                || base.getHost() == null || base.getUserInfo() != null
                || !List.of("http", "https").contains(base.getScheme())) {
            throw new IllegalArgumentException("GitHub client requires HTTP(S), bounded responses and no redirects");
        }
        this.tokens = tokens; this.http = http; this.endpoint = base.resolve("user");
        this.timeout = timeout; this.maxBytes = maxBytes; this.mapper = mapper;
    }

    public GithubUserResult testToken(GithubConfigRequest request) {
        long start = System.nanoTime();
        try {
            String token = tokens.forTest(request);
            if (token == null) return failure("No GitHub token configured", start);
            HttpRequest call = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json").GET().build();
            // The JDK request timeout stops at response headers; also bound body delivery.
            long deadline = System.nanoTime() + timeout.toNanos();
            HttpResponse<byte[]> response = http.send(call, info -> new LimitedBody(maxBytes, deadline));
            int status = response.statusCode();
            if (status == 401) return failure("GitHub credentials rejected (401)", start);
            if (status == 429 || status == 403 && response.headers().firstValue("X-RateLimit-Remaining").orElse("").equals("0"))
                return failure("GitHub rate limit exceeded (" + status + ")", start);
            if (status == 403) return failure("GitHub permissions denied (403)", start);
            if (status != 200) return failure("GitHub returned HTTP " + status, start);
            var json = mapper.readTree(response.body());
            var login = json == null ? null : json.get("login");
            if (login == null || !login.isTextual() || login.textValue().isBlank() || login.textValue().contains(token))
                return failure("GitHub returned an invalid identity response", start);
            return new GithubUserResult(true, login.textValue(), "GitHub token verified", latency(start));
        } catch (TokenCipherException error) {
            return failure(error.code() + ": " + error.getMessage(), start);
        } catch (JsonProcessingException error) {
            return failure("GitHub returned invalid JSON", start);
        } catch (IOException error) {
            return failure("GitHub request failed (network, timeout or response size limit)", start);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failure("GitHub request interrupted", start);
        }
    }

    private static GithubUserResult failure(String message, long start) {
        return new GithubUserResult(false, null, message, latency(start));
    }
    private static long latency(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final long deadline;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private volatile Flow.Subscription subscription;
        LimitedBody(int limit, long deadline) {
            this.limit = limit;
            this.deadline = deadline;
            result.whenComplete((body, error) -> {
                Flow.Subscription active = subscription;
                if (error != null && active != null) active.cancel();
            });
        }
        @Override public CompletionStage<byte[]> getBody() {
            return result.orTimeout(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (result.isDone()) subscription.cancel();
            else subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            long incoming = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (incoming > limit - bytes.size()) {
                result.completeExceptionally(new IOException("GitHub response exceeds size limit"));
                return;
            }
            for (ByteBuffer buffer : buffers) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
