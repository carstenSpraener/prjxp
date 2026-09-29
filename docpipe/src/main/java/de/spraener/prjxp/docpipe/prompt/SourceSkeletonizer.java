package de.spraener.prjxp.docpipe.prompt;

import java.io.File;

public interface SourceSkeletonizer {
    boolean supports(File sourceFile, String ending);

    String skeletonize(File sourceFile) throws Exception;
}
