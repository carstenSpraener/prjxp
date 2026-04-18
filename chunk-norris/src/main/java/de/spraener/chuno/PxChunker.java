package de.spraener.chuno;

import de.spraener.prjxp.common.model.PxChunk;

import java.io.File;
import java.util.stream.Stream;

/**
 * ProjectExpert PxChunker is the interface to chunk files into PxChunks.
 * It can be implemented directly or by adding a @Chunker annotation to a matching
 * method.
 */
public interface PxChunker {
    /**
     * The chunker will receive a file and converts it into a stream of chunks
     *
     * @param f The file fo process
     * @return a (maybe empty) stream of chunks for further processing.
     */
    Stream<PxChunk> chunk(File f);

    /**
     * Checks if the chunker can process the given file.
     *
     * @param f The file to check
     * @return true if the chunker can process the file, false otherwise
     */
    boolean matches(File f);
}
