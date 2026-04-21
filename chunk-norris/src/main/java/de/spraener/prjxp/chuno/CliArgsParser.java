package de.spraener.prjxp.chuno;

import de.spraener.prjxp.common.config.CliArgsParsingEvent;
import de.spraener.prjxp.common.config.PrjXPConfig;
import lombok.NonNull;
import org.apache.commons.cli.*;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.FileWriter;
import java.io.PrintWriter;

@Component
public class CliArgsParser {
    private Options options;

    @EventListener
    private void parseArgs(CliArgsParsingEvent evt) {
        Options options = getOptions();
        PrjXPConfig cfg = evt.cfg();
        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        try {
            CommandLine cmd = parser.parse(options, evt.args());

            if (cmd.hasOption("h")) {
                formatter.printHelp("chunk-norris", options);
            }
            if (cmd.hasOption("r")) {
                cfg.setChunoRootDir(cmd.getOptionValue("r"));
            } else {
                cfg.setChunoRootDir(".");
            }
            if (cmd.hasOption("o")) {
                String outputFileName = cmd.getOptionValue("o");
                cfg.setChunoOutput(new PrintWriter(new FileWriter(outputFileName)));
            } else {
                cfg.setChunoOutput(new PrintWriter(System.out));
            }
            if (cmd.hasOption("wl")) {
                cfg.setChunoWhiteList(cmd.getOptionValue("wl"));
            }
        } catch (Exception e) {
            System.err.println(e.getMessage());
            throw new RuntimeException("Configuration failed!");
        }
    }

    private @NonNull Options getOptions() {
        if (options == null) {
            options = new Options();
            options.addOption(Option.builder("r")
                    .longOpt("root")
                    .numberOfArgs(1)
                    .desc("Read all files from within the given root directory. Default is the current directory.")
                    .build());
            options.addOption(Option.builder(("o"))
                    .longOpt("output")
                    .numberOfArgs(1)
                    .desc("write JSONL to this file. Default is stdout")
                    .build()
            );
            options.addOption(Option.builder("c")
                    .longOpt("config")
                    .numberOfArgs(1)
                    .desc("read configuration from the given json file. default is none.")
                    .build()
            );
            options.addOption(Option.builder()
                    .longOpt("no-standard-vetos")
                    .desc("Turns of the standard vetos for chunk processing. Default is false (standard vetos enabled).")
                    .build()
            );
            options.addOption(Option.builder("wl")
                    .longOpt("white-list")
                    .numberOfArgs(1)
                    .desc("A list (,) of endings that should be chunked.")
                    .build()
            );
            options.addOption("h", "help", false, "Shows this help.");
        }
        return options;
    }
}
