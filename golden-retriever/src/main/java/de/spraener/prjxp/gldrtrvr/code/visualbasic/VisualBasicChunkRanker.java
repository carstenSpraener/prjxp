package de.spraener.prjxp.gldrtrvr.code.visualbasic;

import de.spraener.prjxp.common.code.java.JavaCodeSection;
import de.spraener.prjxp.common.code.visualbasic.VisualBasicCodeSection;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingStrategy;
import org.springframework.stereotype.Component;

@Component
public class VisualBasicChunkRanker implements ChunkRankingStrategy {
    @Override
    public boolean supports(PxChunk chunk) {
        return chunk.getMetadata().containsKey("visualbasic_code_section");
    }

    @Override
    public double rank(PxChunk chunk) {
        VisualBasicCodeSection section = VisualBasicCodeSection.fromName(chunk.getMetadata().get("vb_code_section"));
        switch (section) {
            case CLASS_FRAME:
                return 2;
            case METHOD:
            case METHOD_DOC:
                return 5;
            case IMPORTS:
                return 1;
        }
        return 0.1;
    }
}
