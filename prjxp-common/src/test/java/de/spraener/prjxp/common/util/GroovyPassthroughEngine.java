package de.spraener.prjxp.common.util;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.SimpleBindings;

import java.io.Reader;

/**
 * Minimal JSR-223 test double registered under the name "groovy" via
 * {@code META-INF/services/javax.script.ScriptEngineFactory} in the test resources.
 *
 * <p>The real Groovy engine is NOT on prjxp-common's test classpath, but
 * {@link LineScanner} hard-codes {@code createEngine("groovy")} for its sidecar
 * filter scripts. This double makes the sidecar-script code path testable: its
 * compiled script behaves exactly like the Groovy expression
 * {@code return scanner.readRawLine();}, i.e. it delegates to the bound
 * {@link LineScanner}'s raw reader.</p>
 *
 * <p><b>Note on the API:</b> this JDK's {@code java.scripting} module ships the
 * JSR-223 <i>draft</i> API ({@code CompiledScript} is an abstract class,
 * {@code ScriptEngineFactory#getNames()} returns a {@link java.util.List}), so the
 * double is written against that shape — not against the final JSR-223 API.</p>
 */
public class GroovyPassthroughEngine extends AbstractScriptEngine implements Compilable {

    @Override
    public Object eval(String script, ScriptContext context) {
        return readBoundScannerLine(context);
    }

    @Override
    public Object eval(Reader reader, ScriptContext context) {
        throw new UnsupportedOperationException("test double");
    }

    @Override
    public Bindings createBindings() {
        return new SimpleBindings();
    }

    @Override
    public ScriptEngineFactory getFactory() {
        return new GroovyPassthroughEngineFactory();
    }

    @Override
    public CompiledScript compile(String script) {
        return new PassthroughCompiledScript(this);
    }

    @Override
    public CompiledScript compile(Reader script) {
        throw new UnsupportedOperationException("test double");
    }

    private static Object readBoundScannerLine(ScriptContext context) {
        Bindings bindings = context.getBindings(ScriptContext.ENGINE_SCOPE);
        Object scanner = bindings == null ? null : bindings.get("scanner");
        if (scanner instanceof LineScanner lineScanner) {
            return lineScanner.readRawLine();
        }
        return null;
    }

    /** Compiled form of the double: behaves like {@code return scanner.readRawLine();}. */
    private static class PassthroughCompiledScript extends CompiledScript {

        private final GroovyPassthroughEngine engine;

        private PassthroughCompiledScript(GroovyPassthroughEngine engine) {
            this.engine = engine;
        }

        @Override
        public Object eval(ScriptContext context) {
            return readBoundScannerLine(context);
        }

        @Override
        public ScriptEngine getEngine() {
            return engine;
        }
    }
}
