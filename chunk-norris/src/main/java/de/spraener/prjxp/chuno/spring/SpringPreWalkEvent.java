package de.spraener.prjxp.chuno.spring;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;

public record SpringPreWalkEvent<T extends PrjXPConfig>(
        T config,
        ProjectDefinition project
) {
}
