package de.spraener.prjxp.tibed;

import de.spraener.prjxp.common.PrjXPCli;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import static de.spraener.prjxp.common.PrjXPCli.readDotEnv;

@SpringBootApplication(scanBasePackages = {"de.spraener.prjxp.tibed", "de.spraener.prjxp.common"})
public class TiBedCliApp {

    public static void main(String[] args) {
        readDotEnv(args);
        new SpringApplicationBuilder(TiBedCliApp.class)
                .logStartupInfo(false)
                .bannerMode(Banner.Mode.OFF)
                .headless(false)
                .run(args);
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
