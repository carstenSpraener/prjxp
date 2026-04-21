package de.spraener.prjxp.gldrtrvr;

import de.spraener.prjxp.common.model.PxChunk;

import java.util.List;
import java.util.function.Function;

public interface GoldenRetriever {
    StringBuilder buildPromptForFindings(StringBuilder prompt, List<PxChunk> chunks, Function<String, Boolean>... contextValidators);
}
