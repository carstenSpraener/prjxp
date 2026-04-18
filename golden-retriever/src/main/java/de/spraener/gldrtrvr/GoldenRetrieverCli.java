package de.spraener.gldrtrvr;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

//@SpringBootApplication
public class GoldenRetrieverCli {
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
        SpringApplication.run(GoldenRetrieverCli.class, args);
    }

    @Bean
    @Profile("!test")
    public CommandLineRunner run(
            CliArgsParser argsParser,
            GldRtrvrQuestioner questioner,
            GldRtrvrCfg cfg
    ) {
        return args -> {
            argsParser.parseArgs(args);
            System.out.println(questioner.ask(cfg.getQuestion()));
        };
    }
}
