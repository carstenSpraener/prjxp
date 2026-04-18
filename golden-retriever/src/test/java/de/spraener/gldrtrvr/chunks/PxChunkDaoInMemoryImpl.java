package de.spraener.gldrtrvr.chunks;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.gldrtrvr.GldRtrvrCfg;
import de.spraener.gldrtrvr.PxChunkDao;
import de.spraener.prjxp.common.PxChunkFromJsonLReader;
import de.spraener.prjxp.common.model.PxChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Profile("test")
@RequiredArgsConstructor
public class PxChunkDaoInMemoryImpl implements PxChunkDao {
    private Map<String, List<PxChunk>> chunkDB = null;
    private final ObjectMapper objectMapper;
    private final GldRtrvrCfg cfg;

    private PxChunk fromJSONL(String jsonl) {
        try {
            return this.objectMapper.readValue(jsonl, PxChunk.class);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, List<PxChunk>> getChunkDB() {
        if (chunkDB == null) {
            try {
                chunkDB = new HashMap<>();
                new PxChunkFromJsonLReader().readChunksFromJsonlStreamBatched(cfg.getJsonlStream(), 50, this::fromJSONL)
                        .forEach(batch -> {
                            for (var chunk : batch) {
                                List<PxChunk> cList = chunkDB.computeIfAbsent(chunk.getId(), k -> new ArrayList<>());
                                cList.add(chunk);
                            }
                        });
            } catch (Exception e) {
                chunkDB = null;
                throw new RuntimeException(e);
            }
        }
        return chunkDB;
    }

    @Override
    public List<PxChunk> findById(String id) {
        return getChunkDB().get(id);
    }

    @Override
    public List<PxChunk> findByMetaData(Map<String, String> metaData) {
        return List.of();
    }

    @Override
    public List<PxChunk> findRelevant(String question, int maxResults, double minScore) {
        return List.of();
    }
}
