package de.spraener.prjxp.common.chat;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.config.PrjXPConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Predicate;

@Component
@RequiredArgsConstructor
public class KIChatProvider {
    private final PrjXPConfig config;
    private final KIChatModelProvider modelProvider;

    public Optional<KIChat> getByName(String name) {
        return config.getChatModels().stream()
                .filter(m -> m.getModelName().equals(name))
                .findFirst()
                .map(modelProvider::createKIChat);
    }

    public Optional<KIChat> getByStereotype(String stereotype) {
        return config.getChatModels().stream()
                .filter(m -> m.getStereoType().equals(stereotype))
                .findFirst()
                .map(modelProvider::createKIChat);
    }

    public Optional<KIChat> apply(Predicate<PrjXPChatModelReference> p) {
        return config.getChatModels().stream()
                .filter(p)
                .findFirst()
                .map(modelProvider::createKIChat);
    }
}
