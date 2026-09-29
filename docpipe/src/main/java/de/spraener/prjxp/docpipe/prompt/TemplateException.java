package de.spraener.prjxp.docpipe.prompt;

/**
 * Exception thrown when an error occurs during the resolution of a prompt template.
 */
public class TemplateException extends RuntimeException {
    /**
     * Constructs a new TemplateException with the specified detail message.
     *
     * @param message the detail message
     */
    public TemplateException(String message) {
        super(message);
    }

    /**
     * Constructs a new TemplateException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause of the exception
     */
    public TemplateException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new TemplateException with the specified cause.
     *
     * @param cause the cause of the exception
     */
    public TemplateException(Throwable cause) {
        super(cause);
    }
}
