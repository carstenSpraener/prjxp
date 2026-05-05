package de.spraener.prjxp.chuno.docs.txt;

import de.spraener.prjxp.common.scripting.ScriptCompileService;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;

class TextChunkerTest {

    @Test
    void chunkGoFK_TextFile() throws Exception {
        URL resource = getClass().getClassLoader().getResource("test-go-fk.txt");
        if (resource != null) {
            String fqFileName = new File(resource.toURI()).getAbsolutePath();
            TextChunker uut = new TextChunker(new ScriptCompileService());
            uut.chunkTextFile(new File(fqFileName)).forEach(
                    c -> System.out.printf("Chunk %s [%d of %d]: Länge: %d, Zeilen %s-%s.%n",
                            c.getId(),
                            c.getPart(),
                            c.getTotal(),
                            c.getContent().length(),
                            c.getFromLine(),
                            c.getToLine()
                    )
            );
        }
    }

    @Test
    void chunkSpaceInvadersFK_TextFile() throws Exception {
        URL resource = getClass().getClassLoader().getResource("test-spaceinvaders-fk.txt");
        if (resource != null) {
            String fqFileName = new File(resource.toURI()).getAbsolutePath();
            TextChunker uut = new TextChunker(new ScriptCompileService());
            uut.chunkTextFile(new File(fqFileName)).forEach(
                    c -> System.out.printf("Chunk %s [%d of %d]: Länge: %d, Zeilen %s-%s.%n",
                            c.getId(),
                            c.getPart(),
                            c.getTotal(),
                            c.getContent().length(),
                            c.getFromLine(),
                            c.getToLine()
                    )
            );
        }
    }

}