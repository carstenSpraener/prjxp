package de.spraener.prjxp.docpipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.docpipe.config.ModelConfigLoader;
import de.spraener.prjxp.docpipe.model.DPModelConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.cli.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;

@Component
@RequiredArgsConstructor
@Log
public class DocPipeArgsParser {
    private final ObjectMapper objectMapper;
    private final Environment env;
    private final ModelConfigLoader modelConfigLoader;
    private final DPLogService logService;

    public Options getOptions() {
        Options options = new Options();
        options.addOption(Option.builder("r")
                .longOpt("root")
                .numberOfArgs(1)
                .desc("specify the root directory to work on.")
                .build());
        return  options;
    }

    public DocPipeConfig parseArgs(DocPipeConfig cfg, String[] args) {
        Options options = getOptions();
        cfg.setProjectDir(Path.of("."));

        CommandLineParser parser = new DefaultParser() {
            @Override
            protected void handleUnknownToken(String token) throws ParseException {
                String propertyKey = token.startsWith("--") ? token.substring(2) : token;
                if (propertyKey.contains("=")) {
                    propertyKey = propertyKey.split("=")[0];
                }
                if (env.containsProperty(propertyKey)) {
                    return;
                }
                super.handleUnknownToken(token);
            }
        };
        HelpFormatter formatter = new HelpFormatter();
        try {
            CommandLine cmd = parser.parse(options, args);
            if (cmd.hasOption("r")) {
                cfg.setProjectDir(Path.of(cmd.getOptionValue("r")));
            }
        } catch (Exception e) {
            logService.logMessage(
                    new DPLogMessage(Level.SEVERE, "Error while parsing args: " + e.getMessage() + "\n    Application may not work correctly!")
            );
            formatter.printHelp("docpipe", options);
            throw  new RuntimeException(e);
        }
        return cfg;
    }
}
