package de.spraener.prjxp.docpipe.content;

public interface ContentFilter {
    String name();
    String filter(String content);
}
