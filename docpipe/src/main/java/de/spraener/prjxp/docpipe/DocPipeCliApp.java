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
        // @Accept: Logging-System is not initialized in this phase of execution. System.out is the only way in this phase
        System.out.println("Reading dotenv properties file from "+(new File(".env").getAbsolutePath()));
        Dotenv dotenv = Dotenv.configure()
                .directory(".")
                .filename(".env")
                .ignoreIfMissing()
                .load();

        // Jede Variable aus der .env für Spring/System verfügbar machen
        dotenv.entries().forEach(entry -> {
            if (System.getProperty(entry.getKey()) == null) {
                // @Accept: All values MUST be set as system properties to propagate them to non spring components
                System.setProperty(entry.getKey(), entry.getValue());
            }
        });
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
        // @Accept: The exception handling is done in the runner itself.
        return args -> {
            argsParser.parseArgs(pxCfg, args);
            runner.run(pxCfg);
        };
    }
}
