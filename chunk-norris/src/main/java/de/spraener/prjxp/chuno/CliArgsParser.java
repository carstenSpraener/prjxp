package de.spraener.prjxp.chuno;

import de.spraener.prjxp.common.PxDefaultArgsParser;
import de.spraener.prjxp.common.config.CliArgsParsingEvent;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.transfer.TransferEncryptMode;
import lombok.RequiredArgsConstructor;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.help.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class CliArgsParser {
    private final PxLogService logService;
    private final PrjXPConfig cfg;
    private final Environment env;

    @EventListener
    public void parseArgs(CliArgsParsingEvent evt) {
        apply(parse(evt.args()));
    }

    private CommandLine parse(String[] args) {
        Options options = buildOptions();

        CommandLineParser parser = new PxDefaultArgsParser(env);
        HelpFormatter formatter = HelpFormatter.builder().get();
        try {
            return parser.parse(options, args);
        } catch (Exception e) {
            logService.error(e, "Error while parsing args: %s\n    Application may not work correctly!", e.getMessage());
            try {
                formatter.printHelp("chunk-norris [options]", "chunk-norris", options, "---", true);
            } catch (IOException ex) {}
            System.exit(0);
            return null;
        }
    }

    private void apply(CommandLine cmd) {
        if (cmd.hasOption("h")) {
            try {
                HelpFormatter.builder().get().printHelp("chunk-norris [options]", "chunk-norris", buildOptions(), "---", true);
            } catch (IOException e) {}
            System.exit(0);
        }
        if (cmd.hasOption("p")) {
            cfg.setActiveProject(cmd.getOptionValue("p"));
        }
        if (cmd.hasOption("password-env")) {
            cfg.getTransfer().setPasswordEnv(cmd.getOptionValue("password-env"));
        }
        if (cmd.hasOption("encrypt") && cmd.hasOption("no-encrypt")) {
            throw new IllegalArgumentException("--encrypt and --no-encrypt cannot be combined");
        }
        if (cmd.hasOption("encrypt")) {
            cfg.getTransfer().setEncrypt(TransferEncryptMode.TRUE);
        }
        if (cmd.hasOption("no-encrypt")) {
            cfg.getTransfer().setEncrypt(TransferEncryptMode.FALSE);
        }
    }

    private Options buildOptions() {
        Options options = new Options();
        options.addOption(Option.builder("p")
                .longOpt("project")
                .numberOfArgs(1)
                .desc("specify the active project to work on.")
                .build());
        options.addOption(Option.builder()
                .longOpt("password-env")
                .numberOfArgs(1)
                .desc("name of the environment variable holding the transfer password (default: PRJXP_TRANSFER_PASSWORD).")
                .build());
        options.addOption(Option.builder()
                .longOpt("encrypt")
                .desc("force encryption of the output file.")
                .build());
        options.addOption(Option.builder()
                .longOpt("no-encrypt")
                .desc("force plaintext output, even if a transfer password is available.")
                .build());
        options.addOption(Option.builder("h")
                .longOpt("help")
                .desc("print this help message.")
                .build());
        return options;
    }
}
