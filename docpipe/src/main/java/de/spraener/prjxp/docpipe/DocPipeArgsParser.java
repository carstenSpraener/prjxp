package de.spraener.prjxp.docpipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.docpipe.model.DPModelConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@RequiredArgsConstructor
public class DocPipeArgsParser {
    private final ObjectMapper objectMapper;

    public DocPipeConfig parseArgs(DocPipeConfig cfg, String[] args) {
        cfg.setProjectDir(Path.of("."));
        cfg.setGlobalModels(DPModelConfig.listFrom(objectMapper, cfg.getProjectDir()+"/"+DocPipeConfig.DP_DIR+"/models.json"));
        return cfg;
    }
}
