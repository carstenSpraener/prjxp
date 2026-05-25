package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Log
public class KIChatModelProvider {
    private final List<ChatModelSupplier> chatModelSuppliers;
    private final Map<String, KIChat> cache = new ConcurrentHashMap<>();

    public KIChat createKIChat(PrjXPChatModelReference cmRef) {
        String modelKey = cmRef.getProviderUrl() + ":" + cmRef.getModelName();
        return cache.computeIfAbsent(modelKey, k -> {
            ChatModel chatModel = null;
            for (ChatModelSupplier supplier : chatModelSuppliers) {
                if (supplier.canProvide(cmRef)) {
                    chatModel = supplier.provide(cmRef);
                    break;
                }
            }
            if (chatModel == null) {
                throw new IllegalStateException("There is no supplier for server model stereotype " + cmRef.getServerType() + ". Please check configuration.");
            }
            log.fine("Created KIChat for model: " + cmRef.getModelName() + " with provider: " + cmRef.getServerType());
            return new KIChatModelWrapper(chatModel, cmRef);
        });
    }
}
