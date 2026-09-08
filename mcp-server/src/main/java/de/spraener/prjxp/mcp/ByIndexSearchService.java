package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SymbolMetadata;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ByIndexSearchService {
    public static final String SOURCE = "index";

    private static final int SNIPPET_MAX = 240;

    private final PxChunkDaoProvider chunkDaoProvider;
    private final PrjXPConfig cfg;
    private final SearchCapabilitiesRegistry registry;

    public List<SearchHit> search(ByIndexQuery query) {
        PxChunkDao dao = chunkDaoProvider.get(resolveProject(query.project())).orElse(null);
        if (dao == null) {
            return List.of();
        }

        Map<String, String> filters = new HashMap<>();
        registry.forLanguage(query.language())
                .filter(cap -> !cap.mimeTypes().isEmpty())
                .ifPresent(cap -> filters.put(PxChunk.PXCHUNK_MIME_TYPE, cap.mimeTypes().get(0)));
        putIfPresent(filters, SymbolMetadata.SYMBOL_FQN, query.fqn());
        putIfPresent(filters, SymbolMetadata.SYMBOL_TYPE, query.symbolType());
        putIfPresent(filters, SymbolMetadata.SYMBOL_NAME, query.methodName());
        putIfPresent(filters, SymbolMetadata.SYMBOL_SIGNATURE_HASH, query.signatureHash());
        putIfPresent(filters, SymbolMetadata.SYMBOL_CONTAINER_FQN, query.containerFqn());

        List<ScoredChunk> results;
        try {
            results = dao.searchByIndex(filters, query.limit());
        } catch (UnsupportedOperationException e) {
            return List.of();
        }

        return results.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparing(sc -> sc.chunk().getId(), Comparator.nullsLast(String::compareTo)))
                .map(sc -> SearchHit.from(sc.chunk(), sc.score(), preview(sc.chunk().getContent()), SOURCE))
                .toList();
    }

    private String resolveProject(String project) {
        if (project == null || project.isBlank() || "default".equalsIgnoreCase(project)) {
            return cfg.getActiveProject().map(ProjectDefinition::getName).orElse("default");
        }
        return project;
    }

    private void putIfPresent(Map<String, String> filters, String key, String value) {
        if (value != null && !value.isBlank()) {
            filters.put(key, value.trim());
        }
    }

    private String preview(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content.length() <= SNIPPET_MAX ? content : content.substring(0, SNIPPET_MAX);
    }
}
