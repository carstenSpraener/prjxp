package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.errorlog.PxLogService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Component
@RequiredArgsConstructor
/**
 * Supplier for OpenAI-compatible chat models.
 * <p>
 * This class implements {@link ChatModelSupplier} to provide {@link OpenAiChatModel}
 * instances. It supports custom base URLs and ensures they are correctly formatted with the {@code /v1} suffix.
 * </p>
 */
public class AzureOpenAPISupplier implements ChatModelSupplier {
    private final PxLogService logService;
    /**
     * Checks if this supplier can provide a chat model for the given reference.
     *
     * @param cmRef the chat model reference to check
     * @return true if this supplier can provide a matching chat model, false otherwise
     */
    @Override
    public boolean canProvide(PrjXPChatModelReference cmRef) {
        Map<String, String> args = cmRef.getArgs();
        return cmRef.getServerType().equals(
                    ServerTypes.CUSTOM.serverType()
                ) &&
                args.containsKey("isAzure")
        ;
    }

    /**
     * Provides an OpenAI-compatible chat model based on the given reference.
     *
     * @param cmRef the chat model reference to provide a model for
     * @return the configured {@link ChatModel} instance
     */
    @Override
    public ChatModel provide(PrjXPChatModelReference cmRef) {
        if( cmRef.getArgs().containsKey("trust-all-certs") && cmRef.getArgs().get("trust-all-certs").equals("true") ) {
            enableInsecureSSL();
        }
        String apiKey = readArg(cmRef,"api-key");
        return OpenAiChatModel.builder()
                .baseUrl(cmRef.getProviderUrl()+"/"+cmRef.getModelName()+"/")
                .apiKey(apiKey)
                .customHeaders(Map.of("api-key",apiKey))
                .customQueryParams(Map.of("api-version", cmRef.getArgs().get("api-version")))
                .timeout(Duration.of(15, ChronoUnit.MINUTES))
                .build();
    }

    // TODO: Move this to a more global application initializer
    private void enableInsecureSSL() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            SSLContext.setDefault(sslContext);
        } catch( NoSuchAlgorithmException | KeyManagementException e) {
            logService.error(e,"Failed to enable insecure SSL for Azure OpenAI API: %s", e.getMessage());
        }
    }

    private String readArg(PrjXPChatModelReference cmRef, String key) {
        String value = cmRef.getArgs().get(key);
        if (value != null && value.startsWith("${") && value.endsWith("}")) {
            String propertyName = value.substring(2, value.length() - 1);
            value = System.getProperty(propertyName);
            return value;
        }
        return value;
    }
}
