package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.EnvPlaceholderResolver;
import de.spraener.prjxp.common.mcp.McpClientManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Optional;
import java.util.function.Predicate;

@Component
@RequiredArgsConstructor
public class KIChatProvider {
    private final PrjXPConfig config;
    private final KIChatModelProvider modelProvider;
    private final McpClientManager mcpClientManager;

    public Optional<KIChat> getByName(String name) {
        return config.getChatModels().stream()
                .filter(m -> name.equals(EnvPlaceholderResolver.resolve(m.getModelName())))
                .findFirst()
                .map(this::resolveModelReference)
                .map(modelProvider::createKIChat);
    }

    public Optional<KIChat> getByStereotype(String stereotype) {
        Optional<KIChat> result = config.getChatModels().stream()
                .filter(m -> stereotype.equals(EnvPlaceholderResolver.resolve(m.getStereoType())))
                .findFirst()
                .map(this::resolveModelReference)
                .map(modelProvider::createKIChat);
        return mcpClientManager.decorate(result);
    }

    public Optional<KIChat> apply(Predicate<PrjXPChatModelReference> p) {
        return config.getChatModels().stream()
                .filter(p)
                .findFirst()
                .map(this::resolveModelReference)
                .map(modelProvider::createKIChat);
    }

    private PrjXPChatModelReference resolveModelReference(PrjXPChatModelReference source) {
        PrjXPChatModelReference resolved = new PrjXPChatModelReference();
        resolved.setStereoType(EnvPlaceholderResolver.resolve(source.getStereoType()));
        resolved.setServerType(EnvPlaceholderResolver.resolve(source.getServerType()));
        resolved.setModelName(EnvPlaceholderResolver.resolve(source.getModelName()));
        resolved.setApiKey(EnvPlaceholderResolver.resolve(source.getApiKey()));
        resolved.setProviderUrl(EnvPlaceholderResolver.resolve(source.getProviderUrl()));
        resolved.setTimeoutSecs(source.getTimeoutSecs());
        resolved.setTemperature(source.getTemperature());
        resolved.setPrompt(EnvPlaceholderResolver.resolve(source.getPrompt()));
        if (source.getArgs() != null && !source.getArgs().isEmpty()) {
            resolved.setArgs(new HashMap<>(EnvPlaceholderResolver.resolveMap(source.getArgs())));
        }
        return resolved;
    }
}
