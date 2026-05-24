package de.spraener.prjxp.docpipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPConfig;
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
/**
 * Main entry point for the DocPipe command-line application.
 * <p>
 * This class initializes the Spring Boot application, loads environment variables 
 * from a {@code .env} file, and configures the execution flow via a {@link CommandLineRunner}.
 * </p>
 */
public class DocPipeCliApp {
    /**
     * Main entry point for the DocPipe application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        readDotEnv();
        SpringApplication.run(DocPipeCliApp.class, args);
    }

    /**
     * Loads environment variables from a {@code .env} file in the current directory.
     * <p>
     * Each variable found in the file is set as a system property if it is not 
     * already defined, making it available to the Spring environment.
     * </p>
     */
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

    /**
     * Creates a bean for {@link ObjectMapper} to be used for JSON processing.
     *
     * @return a new {@link ObjectMapper} instance
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * Configures the command-line runner that orchestrates the application execution.
     *
     * @param pxCfg the project configuration
     * @param argsParser the argument parser for processing CLI inputs
     * @param runner the runner that executes the documentation jobs
     * @return a {@link CommandLineRunner} instance
     */
    @Bean
    @Profile(("!test"))
    public CommandLineRunner commandLineRunner(
            PrjXPConfig pxCfg,
            DocPipeArgsParser argsParser,
            DocPipeRunner runner
    ) {
        return args -> {
            argsParser.parseArgs(pxCfg, args);
            runner.run(pxCfg);
        };
    }
}
