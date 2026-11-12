package de.spraener.prjxp.docpipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.PrjXPCli;
import de.spraener.prjxp.common.config.PrjXPConfig;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Profile;

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
        PrjXPCli.readDotEnv(args);
        SpringApplication.run(DocPipeCliApp.class, args);
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
    // Active only in CLI mode (hub disabled); the hub app runs the pipeline in-process instead.
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "prjxp.hub.enabled", havingValue = "false", matchIfMissing = true)
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
