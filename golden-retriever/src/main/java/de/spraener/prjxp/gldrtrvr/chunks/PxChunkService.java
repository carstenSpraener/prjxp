package de.spraener.prjxp.gldrtrvr.chunks;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.gldrtrvr.PxChunkDao;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.function.Predicate;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class PxChunkService {
    private final PxChunkDao chunkDao;

    public Stream<PxChunk> findByPredicate(Predicate<PxChunk> p) {
        return this.chunkDao.findAll().filter(p);
    }
}
