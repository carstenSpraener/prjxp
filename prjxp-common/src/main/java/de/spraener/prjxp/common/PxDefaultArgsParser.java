package de.spraener.prjxp.common;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.ParseException;
import org.springframework.core.env.Environment;

public class PxDefaultArgsParser extends DefaultParser {
    private final Environment env;

    public PxDefaultArgsParser(Environment env) {
        this.env = env;
    }

    @Override
    protected void handleUnknownToken(String token) throws ParseException {
        // Entferne die "--" am Anfang, falls vorhanden
        String propertyKey = token.startsWith("--") ? token.substring(2) : token;

        // Prüfe auf Gleichheitszeichen bei --key=value
        if (propertyKey.contains("=")) {
            propertyKey = propertyKey.split("=")[0];
        }

        if (env.containsProperty(propertyKey)) {
            // Es ist eine gültige Spring-Property -> Einfach ignorieren für Commons-CLI
            return;
        }

        // Wenn es weder eine Option noch eine bekannte Property ist:
        super.handleUnknownToken(token);
    }
}
