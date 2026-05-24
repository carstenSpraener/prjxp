package de.spraener.prjxp.docpipe.config;

/**
 * Exception thrown when an error occurs during the loading or parsing of configuration files.
 */
public class ConfigException extends Exception {
    /**
     * Constructs a new ConfigException with the specified detail message.
     *
     * @param message the detail message
     */
    public ConfigException(String message) {
        super(message);
    }

    /**
     * Constructs a new ConfigException with the specified cause.
     *
     * @param e the cause of the exception
     */
    public ConfigException(Exception e) {
        super(e);
    }
}
