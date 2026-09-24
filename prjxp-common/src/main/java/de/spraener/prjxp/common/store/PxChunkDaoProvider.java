package de.spraener.prjxp.common.store;

import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

@Component
public class PxChunkDaoProvider implements Function<Predicate<PrjXPEmbeddingStoreReference>, Optional<PxChunkDao>> {
    private final List<PxChunkDao> chunkDaos = new CopyOnWriteArrayList<>();

    public PxChunkDaoProvider(List<PxChunkDao> initialDaos) {
        chunkDaos.addAll(initialDaos);
    }

    /** Registers a DAO at runtime (hub: dynamically imported projects). */
    public void register(PxChunkDao dao) { chunkDaos.add(dao); }

    /** Removes all DAOs whose store reference belongs to the given project (hub: project deletion). */
    public void unregisterByProject(String projectName) {
        chunkDaos.removeIf(d -> projectName.equals(d.getStoreReference().getProjectName()));
    }

    public Optional<PxChunkDao> apply(Predicate<PrjXPEmbeddingStoreReference> predicate) {
        for( var  chunkDao : chunkDaos ) {
            if( predicate.test(chunkDao.getStoreReference()) ) {
                return Optional.of(chunkDao);
            }
        }
        return Optional.empty();
    }

    public Optional<PxChunkDao> get(String prjName) {
        if( "default".equalsIgnoreCase(prjName) ) {
            return apply(m->
                    m.isDefault()
            );
        } else {
            return apply(m->m.getProjectName().equals(prjName));
        }
    }

    public String getModelName(String prjName) {
        for( var  chunkDao : chunkDaos ) {
            if( chunkDao.getStoreReference().getProjectName().equals(prjName) ) {
                return chunkDao.getStoreReference().getDbName();
            }
        }
        return "default";
    }
}
