package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class CurrentFileResolver implements TemplateResolver {

    @Override
    public String getID() {
        return "currentFile";
    }

    @Override
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        File currentFileContent = new File(baseDir.getParentFile(), ((Map<String,String>)context).get("currentFile"));
        return FileUtils.readFileToString(currentFileContent, StandardCharsets.UTF_8);
    }
}
