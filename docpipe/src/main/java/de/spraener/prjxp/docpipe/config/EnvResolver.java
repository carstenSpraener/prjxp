package de.spraener.prjxp.docpipe.config;

public class EnvResolver {

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
