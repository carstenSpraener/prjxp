package de.spraener.prjxp.oragel;

import org.springframework.boot.Banner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

@SpringBootApplication(
        scanBasePackages = {
                "de.spraener.prjxp.oragel",
                "de.spraener.prjxp.gldrtrvr" // Hier liegen die RAG-Beans
        }
)
public class OragelCliApp {

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "false");

        new SpringApplicationBuilder(OragelCliApp.class)
                .logStartupInfo(false) // Versteckt "Starting OragelCliApp..."
                .bannerMode(Banner.Mode.OFF) // Schaltet das ASCII-Spring-Logo aus
                .headless(false)
                .run(args);
    }

    @Bean
    @Profile("!test")
    public CommandLineRunner oragelApp(OragelCliRunner runner) {
        return runner::startApp;
    }

    @Bean
    @Profile("!test")
    public PromptSource interactivePromptSource() {
        return PromptSource.interactive();
    }
}
