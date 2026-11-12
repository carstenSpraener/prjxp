package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.common.chat.KIChatModelProvider;
import de.spraener.prjxp.common.chat.ServerTypes;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.config.PrjXPEmbeddingModelReference;
import de.spraener.prjxp.common.model.ModelLister;
import de.spraener.prjxp.mcp.setup.*;
import de.spraener.prjxp.mcp.setup.GlobalsConfigurationService.Versioned;
import dev.langchain4j.exception.LangChain4jException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

@Service
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
public class GlobalsService {
    private final GlobalsConfigurationService configuration;
    private final SetupModelTestService tests;
    private final KIChatModelProvider chats;
    private final ModelLister models;

    public GlobalsService(GlobalsConfigurationService configuration, SetupModelTestService tests,
                          KIChatModelProvider chats, ModelLister models) {
        this.configuration = configuration;
        this.tests = tests;
        this.chats = chats;
        this.models = models;
    }

    public Versioned<EmbeddingConfigRequest> getEmbeddingConfig() { return configuration.embedding(); }
    public Versioned<List<PrjXPChatModelReference>> getChatModels() { return configuration.chats(); }

    public Versioned<EmbeddingConfigRequest> updateEmbeddingConfig(String version, EmbeddingConfigRequest request, String testId) {
        return configuration.updateEmbedding(version, request, testId);
    }

    public Versioned<PrjXPEmbeddingModelReference> getEmbeddingModel() {
        return map(configuration.embedding(), GlobalsService::legacy);
    }

    public Versioned<PrjXPEmbeddingModelReference> updateEmbeddingModel(String version,
            GlobalsController.PrjXPEmbeddingModelRequest request, String testId) {
        return map(configuration.updateEmbedding(version, modern(request), testId), GlobalsService::legacy);
    }

    public Versioned<PrjXPChatModelReference> createChatModel(String version,
            GlobalsController.PrjXPChatModelRequest request, String testId) {
        return configuration.saveChat(version, request.getStereoType(), modern(request), request.getPrompt(),
                request.getArgs(), true, testId);
    }

    public Versioned<PrjXPChatModelReference> updateChatModel(String version, String name,
            GlobalsController.PrjXPChatModelRequest request, String testId) {
        return configuration.saveChat(version, name, modern(request), request.getPrompt(), request.getArgs(), false, testId);
    }

    public Versioned<Void> deleteChatModel(String version, String name) { return configuration.deleteChat(version, name); }

    public List<String> getServerTypes() {
        return Arrays.stream(ServerTypes.values()).map(ServerTypes::serverType).toList();
    }

    public SetupTestResult testEmbeddingConfig(SetupTestRequest<EmbeddingConfigRequest> request) {
        return tests.testEmbedding(request);
    }

    public Versioned<ModelTestResult> testEmbeddingModel(String version, GlobalsController.PrjXPEmbeddingModelRequest request) {
        return legacyResult(tests.testEmbedding(new SetupTestRequest<>(version, modern(request))));
    }

    public Versioned<ModelTestResult> testChatModel(String version, GlobalsController.PrjXPChatModelRequest request) {
        String name = request.getStereoType() == null ? "default" : request.getStereoType();
        if ("default".equals(name)) {
            return legacyResult(tests.testChat(new SetupTestRequest<>(version, modern(request)),
                    request.getPrompt(), request.getArgs()));
        }
        var candidate = configuration.chatCandidate(version, name, modern(request), request.getPrompt(), request.getArgs());
        long started = System.nanoTime();
        ModelTestResult result;
        boolean interrupted = false;
        try {
            String response = chats.createFreshKIChat(candidate.value()).chat("Say hello");
            result = response == null || response.isBlank()
                    ? ModelTestResult.failure("Chat provider returned an invalid response", elapsed(started))
                    : ModelTestResult.success("Chat test succeeded", elapsed(started),
                    redact(response, candidate.value()), null);
        } catch (LangChain4jException | ResourceAccessException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof InterruptedException) interrupted = true;
            }
            result = ModelTestResult.failure(interrupted ? "Chat test interrupted" : "Chat provider request failed", elapsed(started));
        }
        // Recheck the saved snapshot after network activity, without hot-reloading runtime caches.
        try {
            var checked = configuration.chatCandidate(version, name, modern(request), request.getPrompt(), request.getArgs());
            return new Versioned<>(checked.configVersion(), result);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public List<String> listChatModels(String serverType, String providerUrl, String apiKey, String stereoType) {
        return models.listModels(configuration.resolve(serverType), configuration.resolve(providerUrl),
                configuration.resolveListSecret(apiKey, stereoType, false), 30);
    }

    public List<String> listEmbeddingModels(String serverType, String providerUrl, String apiKey) {
        return models.listModels(configuration.resolve(serverType), configuration.resolve(providerUrl),
                configuration.resolveListSecret(apiKey, null, true), 30);
    }

    private static EmbeddingConfigRequest modern(GlobalsController.PrjXPEmbeddingModelRequest request) {
        return new EmbeddingConfigRequest(providerType(request.getServerType()), request.getProviderUrl(),
                request.getApiKey(), request.getModelName(), null, request.getTimeoutSecs(), null, false);
    }

    private static ChatModelRequest modern(GlobalsController.PrjXPChatModelRequest request) {
        return new ChatModelRequest(request.getStereoType(), request.getServerType(), request.getModelName(),
                request.getApiKey(), request.getProviderUrl(), request.getTimeoutSecs(), request.getTemperature());
    }

    private static String providerType(String type) {
        if (type == null) return null;
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "ollama" -> "OLLAMA";
            case "openai", "openapi", "lm-studio" -> "OPEN_AI";
            default -> throw new SetupException("INVALID_CONFIG", "embedding", 400,
                    "Legacy embedding supports ollama/openai/openapi/lm-studio only; migrate gemini/custom to a supported provider");
        };
    }

    private static PrjXPEmbeddingModelReference legacy(EmbeddingConfigRequest source) {
        var target = new PrjXPEmbeddingModelReference();
        target.setServerType("OPEN_AI".equals(source.type()) ? "openai" : "ollama");
        target.setProviderUrl(source.apiBaseURL());
        target.setApiKey(source.apiKey());
        target.setModelName(source.modelName());
        target.setTimeoutSecs(source.timeout());
        return target;
    }

    private static Versioned<ModelTestResult> legacyResult(SetupTestResult result) {
        ModelTestResult legacy = result.success()
                ? ModelTestResult.success(result.message(), result.latencyMs(), result.response(), result.dimensions())
                : ModelTestResult.failure(result.message(), result.latencyMs());
        return new Versioned<>(result.configVersion(), legacy);
    }

    private static <T, R> Versioned<R> map(Versioned<T> source, Function<T, R> mapper) {
        return new Versioned<>(source.configVersion(), mapper.apply(source.value()));
    }

    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }

    private static String redact(String response, PrjXPChatModelReference candidate) {
        if (candidate.getApiKey() != null && !candidate.getApiKey().isEmpty()) {
            response = response.replace(candidate.getApiKey(), "[REDACTED]");
        }
        for (String value : candidate.getArgs().values()) {
            if (value != null && !value.isEmpty()) response = response.replace(value, "[REDACTED]");
        }
        return response;
    }
}
