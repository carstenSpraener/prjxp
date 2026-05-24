package de.spraener.prjxp.docpipe.model;

import lombok.Data;

@Data
/**
 * Represents a single content creation task within a documentation job.
 * <p>
 * This class defines the output file, the LLM model stereotype to use, the prompt template,
 * any post-script to append, and a list of filters to apply to the generated content.
 * </p>
 */
public class DPContentCreation {
    private String outputFile;
    private String stereotype;
    private String prompt;
    private String ps;
    private String filterList;
}
