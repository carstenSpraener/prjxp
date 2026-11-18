package de.spraener.prjxp.mcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Per-measure switches of the P2 contract.
 * <p>
 * The P2 surface (bound project, nine tools) is always on in this profile. The three denser
 * rendering measures can be disabled individually so an A/B run can attribute an effect to one
 * measure instead of a bundle. Without this, a token cap or the call budget of one measure
 * silently changes the effect of another (see Benchmark-Befund 02, §13).
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
public class P2Measures {

    private final boolean grepLines;
    private final boolean snippets;
    private final boolean bodies;

    public P2Measures(@Value("${prjxp.hub.p2.grep-lines:true}") boolean grepLines,
                      @Value("${prjxp.hub.p2.snippets:true}") boolean snippets,
                      @Value("${prjxp.hub.p2.bodies:true}") boolean bodies) {
        this.grepLines = grepLines;
        this.snippets = snippets;
        this.bodies = bodies;
    }

    /** {@code grep} returns {@code file:line} hits instead of a per-file hit count. */
    public boolean grepLines() {
        return grepLines;
    }

    /** {@code vectorSearch} renders a snippet plus a {@code readChunks} hint. */
    public boolean snippets() {
        return snippets;
    }

    /** {@code readBySignature} appends method bodies to a class frame. */
    public boolean bodies() {
        return bodies;
    }
}
