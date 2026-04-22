package de.spraener.prjxp.chuno;

import de.spraener.prjxp.common.PrjXPCli;
import io.github.cdimascio.dotenv.Dotenv;
import org.apache.commons.cli.Options;
import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import static de.spraener.prjxp.common.PrjXPCli.readDotEnv;

@SpringBootApplication(scanBasePackages = {"de.spraener.prjxp.chuno", "de.spraener.prjxp.common"})
public class ChunkNorris {

    public static void main(String[] args) {
        readDotEnv(args);
        new SpringApplicationBuilder(ChunkNorris.class)
                .logStartupInfo(false)
                .bannerMode(Banner.Mode.OFF)
                .headless(false)
                .run(args);
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
