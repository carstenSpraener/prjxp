package de.spraener.prjxp.common.util;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;

import java.util.List;

/**
 * ServiceLoader factory for {@link GroovyPassthroughEngine}, registered under the
 * name "groovy" (see {@code META-INF/services/javax.script.ScriptEngineFactory} in
 * the test resources) so that {@code ScriptCompileService.createEngine("groovy")}
 * resolves to the test double.
 *
 * <p><b>Note on the API:</b> this JDK's {@code java.scripting} module ships the
 * JSR-223 <i>draft</i> API — {@code getNames()}/{@code getExtensions()}/
 * {@code getMimeTypes()} return {@link List}s, and the factory additionally declares
 * {@code getEngineVersion()}/{@code getLanguageVersion()}/
 * {@code getMethodCallSyntax(...)}/{@code getOutputStatement(...)}/
 * {@code getProgram(String...)}. This class implements exactly that shape.</p>
 */
public class GroovyPassthroughEngineFactory implements ScriptEngineFactory {

    @Override
    public String getEngineName() {
        return "Groovy (test double)";
    }

    @Override
    public String getEngineVersion() {
        return "1.0-test-double";
    }

    @Override
    public List<String> getNames() {
        return List.of("groovy");
    }

    @Override
    public List<String> getExtensions() {
        return List.of("groovy", "gvy");
    }

    @Override
    public List<String> getMimeTypes() {
        return List.of("application/x-groovy");
    }

    @Override
    public String getLanguageName() {
        return "Groovy";
    }

    @Override
    public String getLanguageVersion() {
        return "test-double";
    }

    @Override
    public Object getParameter(String key) {
        return null;
    }

    @Override
    public String getMethodCallSyntax(String obj, String m, String... args) {
        return "";
    }

    @Override
    public String getOutputStatement(String statement) {
        return statement;
    }

    @Override
    public String getProgram(String... statements) {
        return String.join("\n", statements);
    }

    @Override
    public ScriptEngine getScriptEngine() {
        return new GroovyPassthroughEngine();
    }
}
