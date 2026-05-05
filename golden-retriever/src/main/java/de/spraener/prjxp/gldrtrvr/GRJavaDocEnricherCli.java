package de.spraener.prjxp.gldrtrvr;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.gldrtrvr.javadoc.JavaDocEnricher;
import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;

import static de.spraener.prjxp.common.PrjXPCli.readDotEnv;

@SpringBootApplication(scanBasePackages = {"de.spraener.prjxp.gldrtrvr", "de.spraener.prjxp.common"})
public class GRJavaDocEnricherCli {

    public static void main(String[] args) {
        readDotEnv(args);
        new SpringApplicationBuilder(GRJavaDocEnricherCli.class)
                .logStartupInfo(false)
                .bannerMode(Banner.Mode.OFF)
                .headless(false)
                .run(args);
    }

    //@Bean
    public CommandLineRunner enricherRun(
            PrjXPConfig cfg,
            JavaDocEnricher javaDocEnricher
    ) {
        return args -> {
            javaDocEnricher.enrichProject(cfg.getGrProjectSourceDirs());
        };
    }
}
