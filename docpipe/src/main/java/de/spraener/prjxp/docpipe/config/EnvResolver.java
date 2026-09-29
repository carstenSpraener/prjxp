package de.spraener.prjxp.docpipe.config;

/**
 * Utility class for resolving environment variables within configuration strings.
 * <p>
 * This class provides a simple mechanism to replace placeholders in the format 
 * {@code ${VARIABLE_NAME}} with their corresponding values from the system environment.
 * </p>
 */
public class EnvResolver {

    /**
     * Resolves a string value by replacing environment variable placeholders.
     * <p>
     * If the input starts with {@code ${} and ends with {@code }}, it is treated as an 
     * environment variable name and resolved using {@link System#getenv()}.
     * </p>
     *
     * @param value the string to resolve
     * @return the resolved value, or an empty string if the input is null
     */
    public static String resolve(String value) {
        if( value == null ) {
            return "";
        }
        if( value.startsWith("${")) {
            String envVar = value.replace("${", "").replace("}", "");
            return System.getenv().get(envVar);
        }
        return value;
    }
}
