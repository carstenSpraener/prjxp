package de.spraener.prjxp.chuno;

import de.spraener.prjxp.common.config.PrjXPConfig;
import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import static de.spraener.prjxp.common.PrjXPCli.readDotEnv;

@SpringBootApplication(
        scanBasePackages = {
                "de.spraener.prjxp"
        }
)
public class ChunkNorris {

    public static void main(String[] args) {
        readDotEnv(args);
        new SpringApplicationBuilder(ChunkNorris.class)
                .bannerMode(Banner.Mode.OFF)
                .headless(false)
                .run(args);
    }

    @Bean
    @Profile("!test")
    public CommandLineRunner run(
            PrjXPConfig cfg,
            ChunkProcess chunkProcess
    ) {
        return args -> {
            System.out.println("Running on Project: " + cfg.getActiveProject().orElseThrow().getName());
            chunkProcess.execute();
        };
    }
}
