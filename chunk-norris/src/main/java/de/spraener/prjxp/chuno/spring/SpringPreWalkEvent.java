package de.spraener.prjxp.chuno.spring;

import de.spraener.prjxp.common.config.PrjXPConfig;

public record SpringPreWalkEvent<T extends PrjXPConfig>(
        T config
) {
}
