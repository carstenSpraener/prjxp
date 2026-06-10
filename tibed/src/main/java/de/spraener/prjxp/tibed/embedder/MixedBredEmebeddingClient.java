package de.spraener.prjxp.tibed.embedder;

import de.spraener.prjxp.common.model.PxChunk;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class MixedBredEmebeddingClient {

    public List<TextSegment> embed(List<PxChunk> chunks) {
        return null;
    }
}
