package de.spraener.prjxp.tibed;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.cli.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Log
@RequiredArgsConstructor
public class CliArgsParser {
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
