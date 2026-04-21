package de.spraener.prjxp.gldrtrvr.code.java;

import de.spraener.prjxp.common.code.java.JavaCodeSection;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingStrategy;

public class JavaChunkRanker implements ChunkRankingStrategy {
    @Override
    public boolean supports(PxChunk chunk) {
        return chunk.getMetadata().containsKey("java_code_section");
    }

    @Override
    public double rank(PxChunk chunk) {
        JavaCodeSection section = JavaCodeSection.fromName(chunk.getMetadata().get("java_code_section"));
        switch (section) {
            case CLAZZ_FRAME:
                return 2;
            case METHOD:
            case METHOD_DOC:
                return 5;
            case DEPENDENCIE_INFO:
                return 0;
            case IMPORTS:
                return 1;
        }
        return 0;
    }
}
