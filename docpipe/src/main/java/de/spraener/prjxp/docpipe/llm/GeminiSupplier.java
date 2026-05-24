package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import lombok.ToString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class GeminiSupplier implements ChatModelSupplier {
    @Value("${chat.gemini.apikey:NONE-SPECIFIED}")
    @ToString.Exclude
    private String apiKey;
    @Override
    public boolean canProvide(PrjXPChatModelReference cmRef) {
        return cmRef.getServerType().equals(ServerTypes.GEMINI.serverType());
    }

    @Override
    public ChatModel provide(PrjXPChatModelReference cmRef) {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(cmRef.getModelName())
                .temperature(cmRef.getTemperature())
                .timeout(Duration.ofSeconds(cmRef.getTimeoutSecs()))
                .build();
    }
}
