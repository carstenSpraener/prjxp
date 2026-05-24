package de.spraener.prjxp.common;

import de.spraener.prjxp.common.config.PrjXPConfig;
import lombok.RequiredArgsConstructor;
import org.apache.commons.cli.*;
import org.apache.commons.cli.help.HelpFormatter;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.util.logging.Level;

@RequiredArgsConstructor
public class PxDefaultArgsParser extends DefaultParser {
    private final Environment env;

    public Options getOptions() {
        Options options = new Options();
        options.addOption(Option.builder("p")
                .longOpt("project")
                .numberOfArgs(1)
                .desc("specify the active project to work on.")
                .build());
        return  options;
    }

    public PrjXPConfig parseArgs(PrjXPConfig cfg, String[] args) {
        Options options = getOptions();
        cfg.setActiveProject("default");
        HelpFormatter formatter = HelpFormatter.builder().get();
        try {
            CommandLine cmd = parse(options, args);
            if (cmd.hasOption("p")) {
                cfg.setActiveProject(cmd.getOptionValue("p"));
            }
        } catch (ParseException pe) {
            try {
                formatter.printHelp("docpipe [-p | --project porjectname]", "docpipe", options, "---", true);
                throw new RuntimeException(pe);
            } catch( IOException ioxc ) {
                throw new RuntimeException(ioxc);
            }
        }
        return cfg;
    }

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
}
