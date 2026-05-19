package de.spraener.prjxp.gldrtrvr.chunks;

import de.spraener.prjxp.common.model.PxChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChunkRankingService {
    private final List<ChunkRankingStrategy> strategies;

    public double rank(PxChunk chunk) {
        for (ChunkRankingStrategy strategy : strategies) {
            if( strategy.supports(chunk)) {
                return strategy.rank(chunk);
            }
        }
        return 0.0;
    }
}
