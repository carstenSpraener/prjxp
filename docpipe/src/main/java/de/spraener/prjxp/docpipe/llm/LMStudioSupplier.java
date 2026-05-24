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
/**
 * Supplier for LM Studio-based chat models.
 * <p>
 * This class implements {@link ChatModelSupplier} to provide chat models that are compatible 
 * with the OpenAI API provided by LM Studio. It configures a custom HTTP client to ensure 
 * compatibility with the LM Studio server.
 * </p>
 */
public class LMStudioSupplier implements ChatModelSupplier{
    /**
     * Checks if this supplier can provide a chat model for the given reference.
     *
     * @param cmRef the chat model reference to check
     * @return true if this supplier can provide a matching chat model, false otherwise
     */
    @Override
    public boolean canProvide(PrjXPChatModelReference cmRef) {
        return cmRef.getServerType().equals(ServerTypes.LM_STUDIO.serverType());
    }

    /**
     * Provides an LM Studio-compatible chat model based on the given reference.
     *
     * @param cmRef the chat model reference to provide a model for
     * @return the configured {@link ChatModel} instance
     */
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
