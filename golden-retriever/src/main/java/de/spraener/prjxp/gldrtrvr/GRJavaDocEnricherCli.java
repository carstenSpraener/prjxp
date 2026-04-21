package de.spraener.prjxp.gldrtrvr;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.gldrtrvr.javadoc.JavaDocEnricher;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

@SpringBootApplication(scanBasePackages = {"de.spraener.prjxp.gldrtrvr", "de.spraener.prjxp.common"})
public class GRJavaDocEnricherCli {
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
        new SpringApplicationBuilder(GRJavaDocEnricherCli.class)
                .logStartupInfo(false)
                .bannerMode(Banner.Mode.OFF)
                .headless(false)
                .run(args);
    }

    @Bean
    public CommandLineRunner enricherRun(
            PrjXPConfig cfg,
            JavaDocEnricher javaDocEnricher
    ) {
        return args -> {
            String path = cfg.getGrProjectSourceDir();
            if (path == null) {
                return;
            }
            String[] paths = path.split(",");
            Path[] p = new Path[paths.length];
            for (int i = 0; i < paths.length; i++) {
                p[i] = Path.of(paths[i]);
            }
            javaDocEnricher.enrichProject(p);
        };
    }
}
