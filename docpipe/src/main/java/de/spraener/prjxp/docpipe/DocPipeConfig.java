package de.spraener.prjxp.docpipe;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@Data
/**
 * Configuration class for the DocPipe module.
 * <p>
 * This class holds global configuration settings, such as the root directory 
 * of the project being processed.
 * </p>
 */
public class DocPipeConfig {
    private Path projectDir;
}
