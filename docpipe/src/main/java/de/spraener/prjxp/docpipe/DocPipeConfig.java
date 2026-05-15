package de.spraener.prjxp.docpipe;

import de.spraener.prjxp.docpipe.model.DPModelConfig;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
@Data
public class DocPipeConfig {
    private Path projectDir;
    private List<DPModelConfig> globalModels = new ArrayList<>();
}
