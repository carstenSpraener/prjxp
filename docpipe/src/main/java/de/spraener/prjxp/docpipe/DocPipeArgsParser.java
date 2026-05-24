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
public class DocPipeArgsParser extends PxDefaultArgsParser {

    private final DPLogService dPLogService;

    public DocPipeArgsParser(Environment env, DPLogService dPLogService) {
        super(env);
        this.dPLogService = dPLogService;
    }

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
