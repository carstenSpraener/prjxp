package de.spraener.prjxp.mcp.hub.github;

import de.spraener.prjxp.common.config.ConfigFileLocator;
import de.spraener.prjxp.common.config.ConfigSecretResolver;
import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.mcp.setup.ConfigurationStore;
import de.spraener.prjxp.mcp.setup.GithubConfigRequest;
import de.spraener.prjxp.mcp.setup.SetupException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.function.Supplier;

@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
public class GithubTokenResolver {
    private final ConfigurationStore store;
    private final Supplier<TokenCipher> cipher;
    private final HubProperties legacy;

    @Autowired
    public GithubTokenResolver(ConfigurationStore store, TokenCipher cipher) {
        this(store, () -> cipher);
    }

    public GithubTokenResolver(ConfigurationStore store, Supplier<TokenCipher> cipher) {
        this.store = store;
        this.cipher = cipher;
        this.legacy = null;
    }

    GithubTokenResolver(HubProperties properties, TokenCipher cipher) {
        this.store = null;
        this.cipher = () -> cipher;
        this.legacy = properties;
    }

    public String forImport(String token) throws TokenCipherException {
        return current(hub -> resolve(token == null ? hub.getGithubDefaultPat() : token, hub.getGithubTokenKeyEnv()));
    }

    public String forProject(String token) throws TokenCipherException {
        return forImport(token == null || token.isEmpty() ? null : token);
    }

    public String forTest(GithubConfigRequest request) throws TokenCipherException {
        return current(hub -> forTest(request, hub));
    }

    public String forTest(GithubConfigRequest request, HubProperties hub) throws TokenCipherException {
        if (request == null || request.tokenKeyEnv() != null && !request.tokenKeyEnv().matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new SetupException("INVALID_REQUEST", "github", 400, "Invalid GitHub token request");
        }
            if (!hub.isEnabled()) throw new SetupException("HUB_DISABLED", "github", 409, "GitHub hub is disabled");
            String token = request.defaultPat();
            if (token == null || "********".equals(token)) {
                return resolve(hub.getGithubDefaultPat(), hub.getGithubTokenKeyEnv());
            }
            return resolve(token, request.tokenKeyEnv() == null ? hub.getGithubTokenKeyEnv() : request.tokenKeyEnv());
    }

    public String resolve(String token, String keyName) throws TokenCipherException {
        if (token == null || token.isEmpty()) return null;
        if (token.contains("${")) {
            if (!token.matches("\\$\\{[A-Za-z_][A-Za-z0-9_]*}")) throw invalid();
            try {
                token = store == null
                        ? new ConfigSecretResolver(new ConfigFileLocator().getEnvFilePath()).resolve(token) : store.resolve(token);
            } catch (IllegalArgumentException error) {
                throw new TokenCipherException("MISSING_SECRET", "Cannot resolve GitHub token reference", null);
            } catch (IOException error) {
                throw new TokenCipherException("MISSING_SECRET", "Cannot read GitHub token environment", null);
            }
            if (token == null || token.isEmpty()) throw new TokenCipherException("MISSING_SECRET", "GitHub token reference is empty", null);
        }
        if (token.startsWith("v1:")) {
            if (keyName == null || !keyName.matches("[A-Za-z_][A-Za-z0-9_]*")) throw invalid();
            TokenCipher active = cipher.get();
            if (active == null) throw new SetupException("HUB_DISABLED", "github", 409, "GitHub token encryption is unavailable");
            try { token = store == null ? active.decrypt(token) : active.decrypt(token, keyName); }
            catch (TokenCipherException error) {
                throw new TokenCipherException(error.code(), "Cannot decrypt GitHub token using " + keyName, null);
            }
        }
        if ("********".equals(token) || !token.matches("[!-~]+")) throw invalid();
        return token;
    }

    private <T> T current(Action<T> action) throws TokenCipherException {
        if (store == null) return action.apply(legacy);
        try {
            return store.read(snapshot -> {
                var hub = store.bindForDisplay(snapshot.root(), "prjxp.hub", HubProperties.class);
                try { return action.apply(hub); }
                catch (TokenCipherException error) { throw new ResolutionFailure(error); }
            });
        } catch (ResolutionFailure error) { throw error.failure; }
    }

    private static TokenCipherException invalid() {
        return new TokenCipherException("GitHub token is not usable");
    }

    private interface Action<T> { T apply(HubProperties hub) throws TokenCipherException; }
    private static final class ResolutionFailure extends RuntimeException {
        private final TokenCipherException failure;
        ResolutionFailure(TokenCipherException failure) { super("GitHub token resolution failed"); this.failure = failure; }
    }
}
