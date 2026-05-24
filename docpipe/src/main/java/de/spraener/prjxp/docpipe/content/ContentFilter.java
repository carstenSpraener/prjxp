package de.spraener.prjxp.docpipe.content;

/**
 * Interface for filtering or post-processing generated content.
 * <p>
 * Implementations of this interface can be used to clean up LLM responses, 
 * remove unwanted markers, or format the text before it is written to the output file.
 * </p>
 */
public interface ContentFilter {
    /**
     * Returns the unique name of this filter, used for identification in configuration.
     *
     * @return the filter name
     */
    String name();

    /**
     * Applies the filter logic to the provided content.
     *
     * @param content the raw content to be filtered
     * @return the processed content
     */
    String filter(String content);
}
