package de.spraener.prjxp.docpipe;

import de.spraener.prjxp.common.PxDefaultArgsParser;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.errorlog.PxLogService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Command-line argument parser for the DocPipe application.
 * <p>
 * This class extends {@link PxDefaultArgsParser} to handle application-specific 
 * arguments and integrates with the {@link de.spraener.prjxp.common.errorlog.PxLogService} to report parsing errors.
 * </p>
 */
@Component
public class DocPipeArgsParser extends PxDefaultArgsParser {

    private final PxLogService pxLS;

    /**
     * Constructs a new DocPipeArgsParser.
     *
     * @param env the Spring environment for accessing properties
     * @param pxLS the logging service for reporting parsing errors
     */
    public DocPipeArgsParser(Environment env, PxLogService pxLS) {
        super(env);
        this.pxLS = pxLS;
    }

    /**
     * Parses the command-line arguments and updates the project configuration.
     * <p>
     * This method calls the superclass parser and catches any runtime exceptions to 
     * log them via the {@link de.spraener.prjxp.common.errorlog.PxLogService} before re-throwing.
     * </p>
     *
     * @Accept: the RuntimeException re is logged and NOT re-thrown to keep the cli application running
     * @Accept: the order of the arguments to pxLS.error has to be this way to enable string varargs formatting
     *
     * @param cfg the current project configuration to be updated
     * @param args the command-line arguments array
     * @return the updated project configuration
     */
    public PrjXPConfig parseArgs(PrjXPConfig cfg, String[] args) {
        try {
            super.parseArgs(cfg, args);
       } catch (RuntimeException re) {
            pxLS.error(re,"Error while parsing args: '%s'. Application may not work correctly!");
        }
        return cfg;
    }
}
