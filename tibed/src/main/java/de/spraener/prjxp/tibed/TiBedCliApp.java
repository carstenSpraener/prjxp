package de.spraener.prjxp.tibed;

import de.spraener.prjxp.common.PrjXPCli;
import de.spraener.prjxp.common.config.PrjXPConfig;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import static de.spraener.prjxp.common.PrjXPCli.readDotEnv;

@SpringBootApplication(scanBasePackages = {
        "de.spraener.prjxp.tibed",
        "de.spraener.prjxp.common",
        "de.spraener.prjxp.lucene"
})
public class TiBedCliApp {

    public static void main(String[] args) {
        readDotEnv(args);
        new SpringApplicationBuilder(TiBedCliApp.class)
                .logStartupInfo(false)
                .bannerMode(Banner.Mode.OFF)
                .headless(false)
                .run(args);
    }

    @Bean("tibedRun")   // unique name: the hub app scans multiple modules that each define a 'run' bean
    @Profile("!test")
    // Active only in CLI mode (hub disabled); the hub app runs the pipeline in-process instead.
    @ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "false", matchIfMissing = true)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "tibed.run-embedding", havingValue = "true", matchIfMissing = true)
    public CommandLineRunner run(
            EmbeddingService storeProcess,
            EmbeddingExportService exportProcess,
            EmbeddingImportService importProcess,
            PrjXPConfig cfg
    ) {
        return args -> {
            switch (cfg.getTransfer().getMode()) {
                case EXPORT -> exportProcess.execute();
                case IMPORT -> importProcess.execute();
                default -> storeProcess.execute();
            }
        };
    }

}
