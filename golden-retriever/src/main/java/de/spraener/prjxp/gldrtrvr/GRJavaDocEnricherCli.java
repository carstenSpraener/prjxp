package de.spraener.prjxp.gldrtrvr;

import de.spraener.prjxp.gldrtrvr.javadoc.JavaDocEnricher;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

@SpringBootApplication
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
                .logStartupInfo(false) // Versteckt "Starting OragelCliApp..."
                .bannerMode(Banner.Mode.OFF) // Schaltet das ASCII-Spring-Logo aus
                .headless(false)
                .run(args);
    }

    @Bean
    public CommandLineRunner enricherRun(
            CliArgsParser argsParser,
            GldRtrvrCfg cfg,
            JavaDocEnricher javaDocEnricher
    ) {
        return args -> {
            argsParser.parseArgs(args);
            String path = cfg.getProjectSourceDir();
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
