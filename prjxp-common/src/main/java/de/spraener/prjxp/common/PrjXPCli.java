package de.spraener.prjxp.common;

import de.spraener.prjxp.common.config.PrjXPArgsParser;
import io.github.cdimascio.dotenv.Dotenv;
import lombok.RequiredArgsConstructor;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PrjXPCli implements SmartLifecycle {
    private final PrjXPArgsParser argsParser;
    private static String[] args;
    private boolean running = false;

    public static void readDotEnv(String[]args) {
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
