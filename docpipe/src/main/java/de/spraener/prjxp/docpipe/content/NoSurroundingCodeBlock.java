package de.spraener.prjxp.docpipe.content;

import org.springframework.stereotype.Component;

@Component
public class NoSurroundingCodeBlock implements ContentFilter {
    @Override
    public String name() {
        return "noSurroundingCodeBlock";
    }

    @Override
    public String filter(String content) {
        if( content.startsWith("```") && content.endsWith("```")) {
            return content.substring(content.indexOf('\n')+1, content.length()-3);
        }
        return content;
    }
}
