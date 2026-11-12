package de.spraener.prjxp.mcp.setup;

import com.fasterxml.jackson.databind.*;
import de.spraener.prjxp.common.chat.KIChatModelProvider;
import de.spraener.prjxp.common.config.*;
import de.spraener.prjxp.common.embedding.EmbeddingModelFactory;
import de.spraener.prjxp.mcp.hub.github.GithubApiClient;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Service
public class SetupModelTestService {
    private final SetupPersistenceService persistence;
    private final EmbeddingModelFactory factory;
    private final KIChatModelProvider chats;
    private final ObjectProvider<GithubApiClient> github;
    private final ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);

    public SetupModelTestService(SetupPersistenceService persistence, EmbeddingModelFactory factory,
                                 KIChatModelProvider chats, ObjectProvider<GithubApiClient> github) {
        this.persistence = persistence; this.factory = factory; this.chats = chats; this.github = github;
    }

    public SetupTestResult testEmbedding(SetupTestRequest<EmbeddingConfigRequest> request) {
        var prepared = persistence.prepareEmbeddingTest(request);
        return run(prepared, start -> {
            var candidate = mapper.convertValue(prepared.effective(), PrjXPConfig.EmbeddingConfig.class);
            EmbeddingModel model = new ValidatingModel(factory.create(candidate), candidate.getDimensions());
            int measured = model.embed("test").content().vector().length;
            var validation = persistence.verifyEmbeddingIndex(prepared, model);
            return record(prepared, result(true, "Embedding test succeeded", start, null, measured), validation);
        });
    }

    public SetupTestResult testChat(SetupTestRequest<ChatModelRequest> request) {
        return testChat(request, null, null);
    }

    public SetupTestResult testChat(SetupTestRequest<ChatModelRequest> request,
                                    String prompt, Map<String, String> args) {
        var prepared = persistence.prepareChatTest(request, prompt, args);
        return run(prepared, start -> {
            var candidate = mapper.convertValue(prepared.effective(), PrjXPChatModelReference.class);
            String response = chats.createFreshKIChat(candidate).chat("Say hello");
            if (response == null || response.isBlank()) throw new InvalidResponse();
            response = redact(response, prepared.effective().get("api-key"));
            response = redact(response, prepared.effective().get("args"));
            response = redact(response, prepared.effective().get("body-properties"));
            return record(prepared, result(true, "Chat test succeeded", start, response, null), null);
        });
    }

    public SetupTestResult testGithub(SetupTestRequest<GithubConfigRequest> request) {
        var prepared = persistence.prepareGithubTest(request);
        GithubApiClient client = github.getIfAvailable();
        if (client == null) throw new SetupException("HUB_DISABLED", "github", 409, "GitHub hub is disabled");
        long start = System.nanoTime();
        var response = client.testToken(new GithubConfigRequest((String) prepared.effective().get("key-name"),
                (String) prepared.effective().get("token")));
        return record(prepared, result(response.valid(), response.message(), start, response.login(), null), null);
    }

    private SetupTestResult run(SetupPersistenceService.PreparedTest prepared, Function<Long, SetupTestResult> operation) {
        long start = System.nanoTime();
        try { return operation.apply(start); }
        catch (RuntimeException error) {
            boolean interrupted = false, known = error instanceof InvalidResponse;
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof InterruptedException) { interrupted = true; Thread.currentThread().interrupt(); }
                if (cause instanceof IOException || cause instanceof LangChain4jException
                        || cause instanceof java.util.concurrent.TimeoutException) known = true;
            }
            if (!known && !interrupted) throw error;
            org.slf4j.LoggerFactory.getLogger(getClass()).info("Setup {} test failed ({})",
                    prepared.step(), error.getClass().getSimpleName());
            return record(prepared, result(false, interrupted ? "Model test interrupted"
                    : "Model test failed (provider, transport or invalid response)", start, null, null), null);
        }
    }

    private SetupTestResult record(SetupPersistenceService.PreparedTest prepared, SetupTestResult result,
                                   SetupIndexGuard.Validation validation) {
        // FileChannel locking is interruptible; publish the failed observation before restoring cancellation.
        boolean interrupted = Thread.interrupted();
        try { return persistence.recordTest(prepared, result, validation); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static SetupTestResult result(boolean success, String message, long start, String response, Integer dimensions) {
        return new SetupTestResult(success, message, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                response, dimensions, null, null);
    }

    private static String redact(String response, Object secret) {
        if (secret instanceof String value && !value.isEmpty()) return response.replace(value, "[REDACTED]");
        if (secret instanceof Map<?, ?> map)
            for (Object value : map.values()) response = redact(response, value);
        if (secret instanceof Iterable<?> values)
            for (Object value : values) response = redact(response, value);
        return response;
    }

    static final class ValidatingModel implements EmbeddingModel {
        private final EmbeddingModel delegate;
        private Integer expected;
        ValidatingModel(EmbeddingModel delegate, Integer expected) { this.delegate = delegate; this.expected = expected; }
        @Override public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            var response = delegate.embedAll(segments);
            if (response == null || response.content() == null || response.content().size() != segments.size()) throw new InvalidResponse();
            for (Embedding embedding : response.content()) {
                if (embedding == null) throw new InvalidResponse();
                float[] vector = embedding.vector();
                if (vector == null || vector.length == 0 || expected != null && vector.length != expected) throw new InvalidResponse();
                double norm = 0;
                for (float value : vector) {
                    if (!Float.isFinite(value)) throw new InvalidResponse();
                    norm += (double) value * value;
                }
                if (norm == 0) throw new InvalidResponse();
                if (expected == null) expected = vector.length;
            }
            return response;
        }
    }

    private static final class InvalidResponse extends RuntimeException { }
}
