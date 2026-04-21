package de.spraener.prjxp.tibed;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

@SpringBootApplication(scanBasePackages = {"de.spraener.prjxp.tibed", "de.spraener.prjxp.common"})
public class TiBedCliApp {
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
        SpringApplication.run(TiBedCliApp.class, args);
    }

    @Bean
    @Profile("!test")
    public CommandLineRunner run(
            EmbeddingService embedProcess
    ) {
        return args -> {
            embedProcess.execute();
        };
    }

}
