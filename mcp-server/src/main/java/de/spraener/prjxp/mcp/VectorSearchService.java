package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VectorSearchService {
    public static final String SOURCE = "vector";

    private final PxChunkDaoProvider chunkDaoProvider;
    private final PrjXPConfig cfg;
    private final SearchCapabilitiesRegistry registry;

    public List<SearchHit> search(String query, String project, String language, int limit) {
        PxChunkDao dao = chunkDaoProvider.get(resolveProject(project)).orElse(null);
        if (dao == null) {
            return List.of();
        }

        Map<String, String> filters = new HashMap<>();
        registry.forLanguage(language)
                .filter(cap -> !cap.mimeTypes().isEmpty())
                .ifPresent(cap -> filters.put(PxChunk.PXCHUNK_MIME_TYPE, cap.mimeTypes().get(0)));

        List<ScoredChunk> results;
        try {
            results = dao.searchVector(query, filters, limit);
        } catch (UnsupportedOperationException e) {
            return List.of();
        }

        return results.stream()
                .map(sc -> SearchHit.from(sc.chunk(), sc.score(), SearchHit.preview(sc.chunk().getContent()), SOURCE))
                .toList();
    }

    private String resolveProject(String project) {
        if (project == null || project.isBlank() || "default".equalsIgnoreCase(project)) {
            return cfg.getActiveProject().map(ProjectDefinition::getName).orElse("default");
        }
        return project;
    }
}
