package de.spraener.prjxp.common.embedding;

import de.spraener.prjxp.common.config.*;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.stereotype.Component;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.http.HttpClient;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;

@Component
public class EmbeddingModelFactory {
    private final ConfigSecretResolver secrets;

    public EmbeddingModelFactory() {
        this(new ConfigSecretResolver(new ConfigFileLocator().getEnvFilePath()));
    }

    public EmbeddingModelFactory(ConfigSecretResolver secrets) { this.secrets = secrets; }

    public EmbeddingModel create(PrjXPConfig.EmbeddingConfig candidate) {
        if (candidate == null || candidate.getType() == null || candidate.getTimeout() <= 0
                || candidate.getMaxSegmentsPerBatch() <= 0) throw new IllegalArgumentException("Invalid embedding configuration");
        String url = resolve(candidate.getApiBaseURL()), key = resolve(candidate.getApiKey());
        String name = resolve(candidate.getModelName());
        int cap = candidate.getMaxSegmentsPerBatch();
        EmbeddingModel model = switch (candidate.getType()) {
            case OPEN_AI -> OpenAiEmbeddingModel.builder().apiKey(key).baseUrl(url).modelName(name)
                    .dimensions(candidate.getDimensions()).timeout(Duration.ofSeconds(candidate.getTimeout()))
                    .customHeaders(resolve(candidate.getCustomHeaders()))
                    .customQueryParams(resolve(candidate.getCustomQueryParams()))
                    .httpClientBuilder(JdkHttpClient.builder().httpClientBuilder(HttpClient.newBuilder()
                            .sslContext(sslContext(candidate.isTrustAllCerts()))))
                    .maxSegmentsPerBatch(cap).build();
            case OLLAMA -> OllamaEmbeddingModel.builder().baseUrl(url).modelName(name)
                    .timeout(Duration.ofSeconds(candidate.getTimeout())).build();
        };
        return new CappedEmbeddingModel(model, cap);
    }

    private String resolve(String value) {
        try { return secrets.resolve(value); }
        catch (IOException error) { throw new IllegalStateException("Cannot read embedding configuration secrets"); }
    }

    private Map<String, String> resolve(Map<String, String> values) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (values != null) values.forEach((key, value) -> resolved.put(key, resolve(value)));
        return resolved;
    }

    private SSLContext sslContext(boolean insecure) {
        try {
            if (insecure) {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[]{new TrustAllCertificates()}, new SecureRandom());
                return context;
            }
            if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) return SSLContext.getDefault();
            KeyStore roots = KeyStore.getInstance("Windows-ROOT");
            roots.load(null, null);
            TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(roots);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, managers.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException | IOException error) {
            throw new IllegalStateException("Unable to initialize the embedding TLS truststore", error);
        }
    }

    static final class TrustAllCertificates implements X509TrustManager {
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
    }
}
