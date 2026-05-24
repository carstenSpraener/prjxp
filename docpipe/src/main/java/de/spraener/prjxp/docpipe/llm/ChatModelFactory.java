package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
/**
 * Factory for creating and caching {@link ChatModel} instances.
 * <p>
 * This factory uses a set of {@link ChatModelSupplier}s to instantiate the appropriate chat model
 * based on the provided configuration. Created models are cached in a {@link ConcurrentHashMap}
 * to avoid redundant instantiation for the same provider and model name.
 * </p>
 */
public class ChatModelFactory {
    private final List<ChatModelSupplier> chatModelSuppliers;
    private Map<String, ChatModel> chatModels = new ConcurrentHashMap<>();

    /**
     * Creates or retrieves a cached {@link ChatModel} based on the provided model reference.
     * <p>
     * The method generates a unique key based on the provider URL and model name. If a model
     * for this key already exists in the cache, it is returned; otherwise, the factory iterates
     * through available {@link ChatModelSupplier}s to find one that can provide the requested model.
     * </p>
     *
     * @param cmRef the reference containing model and provider configuration
     * @return a configured {@link ChatModel} instance
     * @throws IllegalStateException if no suitable supplier is found for the given model configuration
     */
    public ChatModel create(PrjXPChatModelReference cmRef) {
        String modelKey = cmRef.getProviderUrl() + ":" + cmRef.getModelName();
        return chatModels.computeIfAbsent(modelKey, (k) -> {
                for (ChatModelSupplier cms : chatModelSuppliers) {
                    if (cms.canProvide(cmRef)) {
                        ChatModel cm = cms.provide(cmRef);
                        return cm;
                    }
                }
                throw new IllegalStateException("There is no supplier for server model stereotype " + cmRef.getServerType() + ". Please check configuration.");
            }
        );
    }
}
