package de.spraener.prjxp.docpipe.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
/**
 * Represents a single content creation task within a documentation job.
 * <p>
 * This class defines the output file, the LLM model stereotype to use, the prompt template,
 * any post-script to append, and a list of filters to apply to the generated content.
 * </p>
 */
public class DPContentCreation {
    private String forEach;
    private String outputFile;
    private String outputDir;
    private String stereotype;
    private String prompt;
    private String ps;
    private String filterList;
    private Map<String,String> args = new HashMap<>();

    public DPContentCreation clone(ObjectMapper objectMapper) {
        return objectMapper.convertValue(this, DPContentCreation.class);
    }
}
