package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class LMStudioSupplier implements ChatModelSupplier{
    @Override
    public boolean canProvide(PrjXPChatModelReference cmRef) {
        return cmRef.getServerType().equals(ServerTypes.LM_STUDIO.serverType());
    }

    @Override
    public ChatModel provide(PrjXPChatModelReference cmRef) {
        HttpClient.Builder javaHttpClientBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1);

        // 2. Den LangChain4j JdkHttpClient-Builder damit füttern
        JdkHttpClientBuilder langchainHttpClientBuilder = JdkHttpClient.builder()
                .httpClientBuilder(javaHttpClientBuilder);

        return OpenAiChatModel.builder()
                .baseUrl(cmRef.getProviderUrl())
                .modelName(cmRef.getModelName()) // Name des Modells in LM-Studio
                .apiKey("lm-studio")
                .timeout(Duration.ofSeconds(cmRef.getTimeoutSecs()))
                // In 1.13 wird die Factory mit dem angepassten Client übergeben
                .httpClientBuilder(langchainHttpClientBuilder)
                .build();
    }
}
