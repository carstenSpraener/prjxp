package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OllamaSupplier implements ChatModelSupplier {
    @Override
    public boolean canProvide(PrjXPChatModelReference cmRef) {
        return cmRef.getServerType().equals(ServerTypes.OLLAMA.serverType());
    }

    @Override
    public ChatModel provide(PrjXPChatModelReference cmRef) {
        return OllamaChatModel.builder()
                .modelName(cmRef.getModelName())
                .baseUrl(cmRef.getProviderUrl())
                .temperature(cmRef.getTemperature())
                .timeout(Duration.ofSeconds(cmRef.getTimeoutSecs()))
                .build();
    }
}
