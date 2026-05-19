package de.spraener.prjxp.gldrtrvr.code.typescript;

import de.spraener.prjxp.common.code.typescript.TypeScriptCodeSection;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingStrategy;
import org.springframework.stereotype.Component;

@Component
public class TypeScriptChunkRanker implements ChunkRankingStrategy {
    @Override
    public boolean supports(PxChunk chunk) {
        return chunk.getMetadata().containsKey("typescript_code_section");
    }

    @Override
    public double rank(PxChunk chunk) {
        TypeScriptCodeSection section = TypeScriptCodeSection.fromName(chunk.getMetadata().get("typescript_code_section"));
        switch (section) {
            case CLASS_FRAME:
                return 0;
            case METHOD:
                return 10 * chunk.getContent().length();
            case METHOD_DOC:
                return 5;
            case DEPENDENCIE_INFO:
                return 0;
            case IMPORTS:
                return 0;
        }
        return 0;
    }
}
