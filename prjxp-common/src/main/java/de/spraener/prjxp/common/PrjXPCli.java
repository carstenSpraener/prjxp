package de.spraener.prjxp.common;

import de.spraener.prjxp.common.config.PrjXPArgsParser;
import de.spraener.prjxp.common.config.ConfigFileLocator;
import de.spraener.prjxp.common.config.ConfigSecretResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class PrjXPCli implements SmartLifecycle {
    private final PrjXPArgsParser argsParser;
    private static String[] args;
    private boolean running = false;

    public static void readDotEnv(String[]args) {
        ConfigFileLocator paths = new ConfigFileLocator(args);
        try {
            new ConfigSecretResolver(paths.getEnvFilePath()).importIntoSystemProperties();
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load .env at " + paths.getEnvFilePath(), error);
        }
        paths = new ConfigFileLocator(args);
        if (paths.getSpringAdditionalLocation() != null) {
            System.setProperty("spring.config.additional-location", paths.getSpringAdditionalLocation());
        }
        PrjXPCli.args = args;
    }

    @Override
    public void start() {
        this.running = true;
        this.argsParser.parse(args);
        System.out.printf("Starting with configuration:\n    %s\n", this.argsParser.getCfg());
    }

    @Override
    public void stop() {
        this.running = false;
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }
}
