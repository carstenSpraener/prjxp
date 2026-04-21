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
    private static ChunkRankingService self;

    public static double rank(PxChunk chunk) {
        if( self==null ) {
            return 0.0;
        }
        for (ChunkRankingStrategy strategy : self.strategies) {
            if( strategy.supports(chunk)) {
                return strategy.rank(chunk);
            }
        }
        return 0.0;
    }

    @Component
    public static class SelfSetter implements ApplicationContextAware {
        @Override
        public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
            ChunkRankingService.self = applicationContext.getBean(ChunkRankingService.class);
        }
    }
}
