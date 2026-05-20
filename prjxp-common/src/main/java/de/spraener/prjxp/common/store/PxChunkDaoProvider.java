package de.spraener.prjxp.common.store;

import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

@Component
@RequiredArgsConstructor
public class PxChunkDaoProvider implements Function<Predicate<PrjXPEmbeddingStoreReference>, Optional<PxChunkDao>> {
    private final List<PxChunkDao> chunkDaos;

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
                return chunkDao.getStoreReference().getStoreDBName();
            }
        }
        return "default";
    }
}
