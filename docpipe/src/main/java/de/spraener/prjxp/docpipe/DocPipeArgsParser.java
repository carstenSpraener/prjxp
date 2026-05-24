package de.spraener.prjxp.docpipe;

import de.spraener.prjxp.common.PxDefaultArgsParser;
import de.spraener.prjxp.common.config.PrjXPConfig;
import lombok.extern.java.Log;
import org.apache.commons.cli.*;
import org.apache.commons.cli.help.HelpFormatter;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;

@Component
@Log
/**
 * Command-line argument parser for the DocPipe application.
 * <p>
 * This class extends {@link PxDefaultArgsParser} to handle application-specific 
 * arguments and integrates with the {@link DPLogService} to report parsing errors.
 * </p>
 */
public class DocPipeArgsParser extends PxDefaultArgsParser {

    private final DPLogService dPLogService;

    /**
     * Constructs a new DocPipeArgsParser.
     *
     * @param env the Spring environment for accessing properties
     * @param dPLogService the logging service for reporting parsing errors
     */
    public DocPipeArgsParser(Environment env, DPLogService dPLogService) {
        super(env);
        this.dPLogService = dPLogService;
    }

    /**
     * Parses the command-line arguments and updates the project configuration.
     * <p>
     * This method calls the superclass parser and catches any runtime exceptions to 
     * log them via the {@link DPLogService} before re-throwing.
     * </p>
     *
     * @param cfg the current project configuration to be updated
     * @param args the command-line arguments array
     * @return the updated project configuration
     */
    public PrjXPConfig parseArgs(PrjXPConfig cfg, String[] args) {
        try {
            super.parseArgs(cfg, args);
        } catch (RuntimeException re) {
            dPLogService.logMessage(
                    new DPLogMessage(Level.SEVERE, "Error while parsing args: " + re.getMessage() + "\n    Application may not work correctly!")
            );
            throw re;
        }
        return cfg;
    }
}
