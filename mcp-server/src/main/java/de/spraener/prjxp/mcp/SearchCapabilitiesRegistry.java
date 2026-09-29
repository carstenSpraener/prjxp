package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.capability.ChunkerSearchCapabilitiesProvider;
import de.spraener.prjxp.common.capability.LanguageCapability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SearchCapabilitiesRegistry {

    private final List<ChunkerSearchCapabilitiesProvider> providers;

    public Map<String, LanguageCapability> byLanguage() {
        Map<String, LanguageCapability> capabilities = new LinkedHashMap<>();
        for (ChunkerSearchCapabilitiesProvider provider : providers) {
            capabilities.put(provider.language(), provider.capability());
        }
        return capabilities;
    }

    public Optional<LanguageCapability> forLanguage(String language) {
        if (language == null || language.isBlank()) {
            return Optional.empty();
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        return byLanguage().entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).equals(normalized))
                .map(Map.Entry::getValue)
                .findFirst();
    }
}
