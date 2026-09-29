package de.spraener.prjxp.docpipe.content;

import org.springframework.stereotype.Component;

@Component
/**
 * A content filter that removes surrounding Markdown code blocks from the generated content.
 * <p>
 * If the content starts and ends with triple backticks (```), this filter strips them 
 * along with the first newline, ensuring only the actual content is returned.
 * </p>
 */
public class NoSurroundingCodeBlock implements ContentFilter {
    /**
     * Returns the name of this content filter.
     *
     * @return the filter name "noSurroundingCodeBlock"
     */
    @Override
    public String name() {
        return "noSurroundingCodeBlock";
    }

    /**
     * Filters the content by removing surrounding Markdown code blocks.
     *
     * @param content the raw generated content to filter
     * @return the filtered content without surrounding code blocks, or the original content if no such blocks are found
     */
    @Override
    public String filter(String content) {
        if( content.startsWith("```") && content.endsWith("```")) {
            return content.substring(content.indexOf('\n')+1, content.length()-3);
        }
        return content;
    }
}
