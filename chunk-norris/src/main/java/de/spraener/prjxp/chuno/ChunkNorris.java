package de.spraener.prjxp.chuno;

import io.github.cdimascio.dotenv.Dotenv;
import org.apache.commons.cli.Options;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

@SpringBootApplication(scanBasePackages = {"de.spraener.prjxp.chuno", "de.spraener.prjxp.common"})
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
            ChunkProcess chunkProcess
    ) {
        return args -> {
            chunkProcess.execute();
        };
    }
}
