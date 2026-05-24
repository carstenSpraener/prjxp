package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;

@Component
/**
 * A template resolver that reads the content of a file specified by a URL.
 * <p>
 * This resolver allows including external or local files in the prompt by providing 
 * a URL-like path relative to the configuration directory.
 * </p>
 */
public class URLResolver implements TemplateResolver {
    @Override
    /**
     * Returns the identifier for this resolver.
     * 
     * @return "URL"
     */
    public String getID() {
        return "URL";
    }

    @Override
    /**
     * Resolves the content of a file specified by a URL.
     * <p>
     * This method extracts the file path from the provided URL and reads the content 
     * of the corresponding file relative to the base configuration directory.
     * </p>
     *
     * @param baseDir the configuration directory used as a base for resolution
     * @param context the current context of the template execution
     * @param options Handlebars options, expecting a URL as the first parameter
     * @return the content of the resolved file as a string
     * @throws Exception if an error occurs while reading the file or parsing the URL
     */
    public String resolve(File baseDir, Object context, Options options) throws Exception{
        String fileParam = options.param(0).toString();
        File value = new File(baseDir.getAbsolutePath()+"/"+(new URL(fileParam).getFile()));
        return Files.readString(value.toPath());
    }
}
