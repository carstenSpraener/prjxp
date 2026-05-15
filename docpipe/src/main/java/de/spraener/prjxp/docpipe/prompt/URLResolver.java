package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;

@Component
public class URLResolver implements TemplateResolver {
    @Override
    public String getID() {
        return "URL";
    }

    @Override
    public String resolve(File baseDir, Options options) throws Exception{
        String fileParam = options.param(0).toString();
        File value = new File(baseDir.getAbsolutePath()+"/"+(new URL(fileParam).getFile()));
        return Files.readString(value.toPath());
    }
}
