package de.spraener.chuno.spring;

import de.spraener.chuno.ChunkNorrisConfig;

public record SpringPreWalkEvent<T extends ChunkNorrisConfig>(
        T config
) {
}
