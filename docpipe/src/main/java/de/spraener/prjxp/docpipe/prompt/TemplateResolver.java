package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;

import java.io.File;

public interface TemplateResolver {
    String getID();
    String resolve(File baseDir, Options options) throws Exception;
}
