package de.spraener.prjxp.docpipe.config;

public class ConfigException extends Exception {
    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(Exception e) {
        super(e);
    }
}
