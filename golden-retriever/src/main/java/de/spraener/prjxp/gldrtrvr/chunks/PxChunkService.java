package de.spraener.prjxp.gldrtrvr.chunks;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.function.Predicate;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class PxChunkService {
    private final PxChunkDaoProvider chunkDaoProvider;
    private final PrjXPConfig cfg;

    public Stream<PxChunk> findByPredicate(String projectName, Predicate<PxChunk> p) {
        return this.chunkDaoProvider.get(projectName).get().findAll().filter(p);
    }

    public Stream<PxChunk> findByPredicate(Predicate<PxChunk> p) {
        return this.chunkDaoProvider.get(cfg.getActiveProject().get().getName()).get().findAll().filter(p);
    }
}
