package de.spraener.prjxp.common.util;

import de.spraener.prjxp.common.scripting.ScriptCompileService;
import lombok.extern.java.Log;
import org.springframework.util.StringUtils;

import javax.script.CompiledScript;
import javax.script.ScriptException;
import java.io.*;
import java.util.function.Predicate;

@Log
public class LineScanner implements AutoCloseable {

    public static enum STATE {
        INIT, LOADED, RUNNING, FINISHED
    }
    public static final String EOF = "___EOF___";

    private final BufferedReader reader;
    private final String[] ringBuffer;
    private final int windowSize; // n
    private final int bufferCapacity; // 2n + 1

    private int currentHead = 0; // Index der aktuellen Zeile im RingBuffer
    private int globalLineIndex = 0;
    private boolean endOfFileReached = false;
    private CompiledScript lineFilter = null;
    private STATE state;
    private boolean inScript = false;

    public LineScanner(InputStream input, File originalFile, int n) throws Exception {
        File sideScript = new File(originalFile.getAbsolutePath()+".groovy");
        if( sideScript.exists() ) {
            log.info( "Using filter script for file "+originalFile.getName());
            ScriptCompileService scs = new ScriptCompileService();
            lineFilter = scs.compile(sideScript.toPath(), scs.createEngine("groovy"));
        }

        this.windowSize = n;
        this.bufferCapacity = (2 * n) + 1;
        this.ringBuffer = new String[bufferCapacity];
        this.reader = new BufferedReader(new InputStreamReader(input));
        this.state = STATE.INIT;

        // Initialisierung: Die ersten n "Zukunftzeilen" füllen
        // Der Kopf steht initial auf 0, d.h. wir füllen Plätze 1 bis n
        for (int i = 0; i <= n; i++) {
            fillBufferAtIndex(i);
        }
        this.state = STATE.LOADED;
        runScript();
        this.state = STATE.RUNNING;
    }

    public int getWindowSize() {
        return windowSize;
    }

    public int getBufferCapacity() {
        return bufferCapacity;
    }
    /**
     * Bewegt den Scanner eine Zeile weiter.
     * @return Die neue aktuelle Zeile oder EOF.
     */
    public String nextLine() throws IOException {
        globalLineIndex++;
        // Der Kopf wandert im Ring
        currentHead = (currentHead + 1) % bufferCapacity;

        // Die neue "n-te Zukunft" lesen, um den Puffer voll zu halten
        int futureIdx = (currentHead + windowSize) % bufferCapacity;
        fillBufferAtIndex(futureIdx);

        return getCurrentLine();
    }

    public void swapLines(int i, int j) {
        if (Math.abs(i) > windowSize || Math.abs(j) > windowSize) {
            throw new IllegalArgumentException(
                    String.format("Index out of window: i=%d, j=%d (n=%d)", i, j, windowSize)
            );
        }

        int rbI = (currentHead + i) % bufferCapacity;
        if (rbI < 0) rbI += bufferCapacity;

        int rbJ = (currentHead + j) % bufferCapacity;
        if (rbJ < 0) rbJ += bufferCapacity;

        if (EOF.equals(ringBuffer[rbI]) || EOF.equals(ringBuffer[rbJ])) {
            log.warning("Warning: Swapping a line with EOF might break the scanner's termination logic.");
        }

        String temp = ringBuffer[rbI];
        ringBuffer[rbI] = ringBuffer[rbJ];
        ringBuffer[rbJ] = temp;
    }

    private Object runScript() {
        if( this.lineFilter == null) {
            return null;
        }
        if( inScript ) {
            return readRawLine();
        }
        try {
            inScript = true;
            // Wir erstellen Bindings, damit das Script Zugriff auf 'this' (den Scanner) hat
            javax.script.Bindings bindings = lineFilter.getEngine().createBindings();
            bindings.put("scanner", this);

            // Wir rufen das Script auf.
            // Wichtig: Das Script muss intern scanner.readRawLine() nutzen!
            Object result = lineFilter.eval(bindings);
            return result;
        } catch( ScriptException sxc ) {
            log.severe( "Error while evaluating line filter script: " + sxc.getMessage());
            return null;
        } finally {
            inScript = false;
        }
    }

    private void fillBufferAtIndex(int idx) throws IOException {
        if (endOfFileReached) {
            ringBuffer[idx] = EOF;
            return;
        }

        if (lineFilter != null) {
            try {
                Object result = runScript();

                if (result == null || result.toString().equals(EOF)) {
                    endOfFileReached = true;
                    ringBuffer[idx] = EOF;
                } else {
                    ringBuffer[idx] = result.toString();
                }
            } catch (Exception e) {
                throw new IOException("Fehler im LineFilter-Script", e);
            }
        } else {
            // Klassischer Fall ohne Script
            String line = readRawLine();
            ringBuffer[idx] = (line == null) ? EOF : line;
            if (line == null) endOfFileReached = true;
        }
    }

    /**
     * Hilfsmethode für das Script, um an die "echten" Zeilen zu kommen,
     * ohne die RingBuffer-Logik des Scanners zu stören.
     */
    public String readRawLine() {
        try {
            String line = reader.readLine();
            return line;
        } catch( IOException xc ) {
            return EOF;
        }
    }

    public String peek(int offset) {
        if (Math.abs(offset) > windowSize) {
            throw new IllegalArgumentException("Offset außerhalb des Fensters n=" + windowSize);
        }
        int idx = (currentHead + offset) % bufferCapacity;
        if (idx < 0) idx += bufferCapacity;

        String val = ringBuffer[idx];
        return val == null ? EOF : val;
    }

    public String getCurrentLine() {
        return peek(0);
    }

    public int getGlobalLineIndex() {
        return globalLineIndex;
    }

    @Override
    public void close() throws IOException {
        reader.close();
        this.state = STATE.FINISHED;
        runScript();
    }

    public String peekNextNonEmpty() {
        for( int peek=0; peek<windowSize; peek++ ) {
            String line = peek(peek);
            if( line!=null && !EOF.equals(line) && StringUtils.hasText(line) ) {
                return peek(peek);
            }
        }
        return "";
    }

    public String peekPrevNonEmpty() {
        for( int peek=0; peek<windowSize; peek++ ) {
            String line = peek(-1 * peek);
            if(line != null && !EOF.equals(line) && StringUtils.hasText(line) ) {
                return line;
            }
        }
        return "";
    }

    public void skipToLine(Predicate<LineScanner> p ) throws IOException {
        while( !p.test(this) ) {
            nextLine();
        }
    }

    public LineScanner.STATE getState() {
        return state;
    }
}