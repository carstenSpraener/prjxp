package de.spraener.prjxp.docpipe;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

@SpringBootApplication
public class DocPipeCliApp {
    public static void main(String[] args) {
        readDotEnv();
        SpringApplication.run(DocPipeCliApp.class, args);
    }

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

    @Bean
    @Profile(("!test"))
    public CommandLineRunner commandLineRunner(
            DocPipeArgsParser argsParser,
            DocPipeRunner runner
    ) {
        return args -> {
            DocPipeConfig cfg = argsParser.parseArgs(args);
            runner.run(cfg);
        };
    }
}
