package de.spraener.prjxp.docpipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Profile;

import java.io.File;

@SpringBootApplication
@ComponentScan("de.spraener.prjxp")
public class DocPipeCliApp {
    public static void main(String[] args) {
        readDotEnv();
        SpringApplication.run(DocPipeCliApp.class, args);
    }

    private static void readDotEnv() {
        System.out.println("Reading dotenv properties file from "+(new File(".env").getAbsolutePath()));
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
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @Profile(("!test"))
    public CommandLineRunner commandLineRunner(
            DocPipeConfig cfg,
            DocPipeArgsParser argsParser,
            DocPipeRunner runner
    ) {
        return args -> {
            argsParser.parseArgs(cfg, args);
            runner.run(cfg);
        };
    }
}
