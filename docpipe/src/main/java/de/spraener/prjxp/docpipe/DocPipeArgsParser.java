package de.spraener.prjxp.docpipe;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class DocPipeArgsParser {

    public DocPipeConfig parseArgs(String[] args) {
        DocPipeConfig cfg = new DocPipeConfig();
        cfg.setProjectDir(Path.of(""));
        return cfg;
    }
}
