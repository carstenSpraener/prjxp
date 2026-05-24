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
public class ChatModelFactory {
    private final List<ChatModelSupplier> chatModelSuppliers;
    private Map<String, ChatModel> chatModels = new ConcurrentHashMap<>();

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
