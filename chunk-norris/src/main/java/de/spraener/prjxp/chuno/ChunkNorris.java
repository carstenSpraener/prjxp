package de.spraener.prjxp.chuno;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.NonNull;
import org.apache.commons.cli.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import java.io.FileWriter;
import java.io.PrintWriter;

@SpringBootApplication
public class ChunkNorris {
    private static Options options;

    private static void readDotEnv() {
        Dotenv dotenv = Dotenv.configure()
                .directory(".")
                .filename(".env")
                .ignoreIfMissing()
                .load();

        // Jede Variable aus der .env für Spring/System verfügbar machen
        dotenv.entries().forEach(entry -> {
            if (System.getProperty(entry.getKey()) == null) {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        });
    }

    public static void main(String[] args) {
        readDotEnv();
        SpringApplication.run(ChunkNorris.class, args);
    }

    @Bean
    @Profile("!test")
    public CommandLineRunner run(
            ChunkProcess chunkProcess,
            ChunkNorrisConfig cfg
    ) {
        return args -> {
            chunkProcess.execute(parseArgs(args, cfg));
        };
    }

    private static ChunkNorrisConfig parseArgs(String[] args, ChunkNorrisConfig cfg) {
        Options options = getOptions();
        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();
        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("h")) {
                formatter.printHelp("chunk-norris", options);
                return null;
            }
            if (cmd.hasOption("r")) {
                cfg.setRootDir(cmd.getOptionValue("r"));
            } else {
                cfg.setRootDir(".");
            }
            if (cmd.hasOption("o")) {
                String outputFileName = cmd.getOptionValue("o");
                cfg.setOutput(new PrintWriter(new FileWriter(outputFileName)));
            } else {
                cfg.setOutput(new PrintWriter(System.out));
            }
            if (cmd.hasOption("c")) {
                cfg.setConfigFile(cmd.getOptionValue("c"));
            }
            if (cmd.hasOption("wl")) {
                cfg.setWhiteList(cmd.getOptionValue("wl"));
            }
            return cfg;
        } catch (Exception e) {
            System.err.println(e.getMessage());
            throw new RuntimeException("Configuration failed!");
        }
    }

    private static @NonNull Options getOptions() {
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
