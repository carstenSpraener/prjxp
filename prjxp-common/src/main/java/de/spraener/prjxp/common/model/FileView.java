package de.spraener.prjxp.common.model;

import java.util.List;

public record FileView(
        String file,            // normalized path as found in the index
        String language,        // detected language, e.g. "java"
        int chunkCount,         // raw chunks (parts) the file was built from — reconstruction transparency
        String content,         // rendered source view (null on error)
        boolean truncated,      // output cap applied
        Integer totalLines,     // lines of the full view (null on error)
        Integer returnedLines,  // lines actually returned after offset/limit (null on error)
        List<String> candidates,// non-empty when the file path was ambiguous
        String error) {         // error message, null when OK

    public static FileView error(String requestedFile, String message) {
        return new FileView(requestedFile, null, 0, null, false, null, null, List.of(), message);
    }

    public static FileView ambiguous(String requestedFile, List<String> candidates) {
        return new FileView(requestedFile, null, 0, null, false, null, null, candidates,
                "Ambiguous file path — " + candidates.size() + " candidates");
    }
}
