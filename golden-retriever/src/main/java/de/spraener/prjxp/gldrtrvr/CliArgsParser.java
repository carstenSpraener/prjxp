package de.spraener.prjxp.gldrtrvr;

import de.spraener.prjxp.common.config.CliArgsParsingEvent;
import de.spraener.prjxp.common.config.PrjXPConfig;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.cli.*;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Log
public class CliArgsParser {
    private final PrjXPConfig cfg;
    private Options options;
    private final Environment env;

    private @NonNull Options getOptions() {
        if (options == null) {
            options = new Options();
            options.addOption(Option.builder("i")
                    .longOpt("input")
                    .numberOfArgs(1)
                    .desc("specify the input file. Default is stdin.")
                    .build());
            options.addOption(Option.builder("q")
                    .longOpt("question")
                    .numberOfArgs(1)
                    .desc("The question you want to ask the llm.")
                    .build());
            options.addOption(Option.builder("bs")
                    .longOpt("batch-size")
                    .numberOfArgs(1)
                    .desc("specify the size of one embedding batch. Overwrites a potential configured batch size in the application configuration. Default is 50.")
                    .build());
            options.addOption(Option.builder("src")
                    .longOpt("project-source")
                    .numberOfArgs(1)
                    .desc("Where to search for the java-project source code. This could be a list (,) of paths.")
                    .build());
        }
        return options;
    }

    @EventListener
    public void parseArgs(CliArgsParsingEvent evt) {
        Options options = getOptions();
        CommandLineParser parser = new DefaultParser() {
            @Override
            protected void handleUnknownToken(String token) throws ParseException {
                // Entferne die "--" am Anfang, falls vorhanden
                String propertyKey = token.startsWith("--") ? token.substring(2) : token;

                // Prüfe auf Gleichheitszeichen bei --key=value
                if (propertyKey.contains("=")) {
                    propertyKey = propertyKey.split("=")[0];
                }

                if (env.containsProperty(propertyKey)) {
                    // Es ist eine gültige Spring-Property -> Einfach ignorieren für Commons-CLI
                    return;
                }

                // Wenn es weder eine Option noch eine bekannte Property ist:
                super.handleUnknownToken(token);
            }
        };

        HelpFormatter formatter = new HelpFormatter();
        try {
            CommandLine cmd = parser.parse(options, evt.args());
            if (cmd.hasOption("i")) {
                cfg.setGrInputSource(cmd.getOptionValue("i"));
            }
            if (cmd.hasOption("q")) {
                cfg.setGrQuestion(cmd.getOptionValue("q"));
            }
            if (cmd.hasOption("src")) {
                cfg.setGrProjectSourceDir(cmd.getOptionValue("src"));
            }
            List<String> otherArgs = cmd.getArgList();
            StringBuilder sb = new StringBuilder();
            otherArgs.stream().forEach(str -> sb.append(str).append(" "));
        } catch (Exception e) {
            log.severe("Error while parsing args: " + e.getMessage() + "\n    Application may not work correctly!");
        }
    }
}
