package de.spraener.prjxp.chuno.spring;

import de.spraener.prjxp.chuno.ChunkNorrisConfig;

public record SpringPreWalkEvent<T extends ChunkNorrisConfig>(
        T config
) {
}
