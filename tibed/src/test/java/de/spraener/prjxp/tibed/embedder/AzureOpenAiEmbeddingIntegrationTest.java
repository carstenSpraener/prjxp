package de.spraener.prjxp.tibed.embedder;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import io.github.cdimascio.dotenv.Dotenv;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.http.HttpClient;
import java.net.URI;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Calls the Azure OpenAI v1-compatible embeddings endpoint.
 *
 * <p>Required entries in the module-root {@code .env}:</p>
 * <pre>
 * EMBEDDING_API_KEY=...
 * AZURE_OPENAI_ENDPOINT={@code https://<gateway>}
 * </pre>
 * The test derives the Azure deployment base URL for {@code text-embedding-3-large} and
 * LangChain4j appends {@code /embeddings} to it.
 * On Windows, the test trusts the managed Windows root-certificate store so it can validate
 * the internal Azure gateway certificate chain. The test is skipped when
 * either setting is absent, so regular unit-test runs neither require credentials nor
 * generate embedding costs.
 */
@Tag("integration")
class AzureOpenAiEmbeddingIntegrationTest {

    private static final String MODEL_NAME = "text-embedding-3-large";
    private static final int VECTOR_DIMENSION = 3_072;

    @Test
    void embedsTextWithTextEmbedding3Large() throws Exception {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String apiKey = dotenv.get("EMBEDDING_API_KEY");
        String endpoint = dotenv.get("AZURE_OPENAI_ENDPOINT");

        assumeTrue(hasUsableValue(apiKey), "EMBEDDING_API_KEY must be configured in .env");
        assumeTrue(hasUsableValue(endpoint), "AZURE_OPENAI_ENDPOINT must be configured in .env");

        EmbeddingModel model = OpenAiEmbeddingModel.builder()
                .httpClientBuilder(JdkHttpClient.builder()
                        .httpClientBuilder(HttpClient.newBuilder().sslContext(sslContext())))
                .baseUrl(toAzureEmbeddingDeploymentBaseUrl(endpoint))
                .apiKey(apiKey)
                .customHeaders(Map.of(
                        "api-key", apiKey,
                        "Ocp-Apim-Subscription-Key", apiKey))
                .customQueryParams(Map.of("api-version", "2024-02-01"))
                .modelName(MODEL_NAME)
                .timeout(Duration.ofSeconds(30))
                .build();

        List<Embedding> embeddings = model.embedAll(List.of(
                TextSegment.from("Azure OpenAI erzeugt für diesen kurzen deutschen Testsatz ein Embedding.")))
                .content();

        assertThat(embeddings).hasSize(1);
        float[] vector = embeddings.getFirst().vector();
        assertThat(vector).hasSize(VECTOR_DIMENSION);
        boolean hasNonZeroValue = false;
        for (float value : vector) {
            assertThat(Float.isFinite(value)).isTrue();
            hasNonZeroValue |= value != 0f;
        }
        assertThat(hasNonZeroValue).isTrue();
    }

    private static boolean hasUsableValue(String value) {
        return value != null && !value.isBlank() && !"none".equalsIgnoreCase(value);
    }

    private static String toAzureEmbeddingDeploymentBaseUrl(String endpoint) {
        URI gateway = URI.create(endpoint);
        return gateway.getScheme() + "://" + gateway.getAuthority()
                + "/openai/deployments/" + MODEL_NAME;
    }

    private static SSLContext sslContext() throws Exception {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return SSLContext.getDefault();
        }

        KeyStore windowsRootStore = KeyStore.getInstance("Windows-ROOT");
        windowsRootStore.load(null, null);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(windowsRootStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagers.getTrustManagers(), null);
        return sslContext;
    }
}



