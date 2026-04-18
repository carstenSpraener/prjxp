package de.spraener.tibed;

import lombok.NonNull;
import lombok.extern.java.Log;
import org.apache.commons.cli.*;
import org.springframework.stereotype.Component;

@Component
@Log
public class CliArgsParser {
    private Options options;


    private @NonNull Options getOptions() {
        if (options == null) {
            options = new Options();
            options.addOption(Option.builder("i")
                    .longOpt("input")
                    .numberOfArgs(1)
                    .desc("specify the input file. Default is stdin.")
                    .build());
            options.addOption(Option.builder("bs")
                    .longOpt("batch-size")
                    .numberOfArgs(1)
                    .desc("specify the size of one embedding batch. Overwrites a potential configured batch size in the application configuration. Default is 50.")
                    .build());
        }
        return options;
    }

    public TiBedConfig parseArgs(String[] args) {
        TiBedConfig cfg = new TiBedConfig();
        Options options = getOptions();
        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        try {
            CommandLine cmd = parser.parse(options, args);
            if (cmd.hasOption("i")) {
                cfg.setInputSource(cmd.getOptionValue("i"));
            }
            return cfg;
        } catch (Exception e) {
            log.severe("Error while parsing args: " + e.getMessage() + "\n    Application may not work correctly!");
            return cfg;
        }
    }
}
