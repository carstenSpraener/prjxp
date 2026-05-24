package de.spraener.prjxp.docpipe;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@Data
public class DocPipeConfig {
    private Path projectDir;
}
