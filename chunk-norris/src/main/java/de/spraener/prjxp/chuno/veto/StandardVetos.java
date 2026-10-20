package de.spraener.prjxp.chuno.veto;

import de.spraener.prjxp.common.annotations.ChunkVeto;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.Set;

@Service()
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "no-standard-vetos",
        havingValue = "false",      // Er soll laufen, solange "no-standard-vetos" NICHT true ist
        matchIfMissing = true       // Wenn der Parameter gar nicht angegeben wird, wird er als "false" gewertet
)
public class StandardVetos {
    @Value("${chunknorris.veto.maxsize:1000000}")
    private long maxSize;
    private final PrjXPConfig cfg;
    private final VetoContext vetoContext;

    @ChunkVeto
    public boolean isBuildArtifact(Path p) {
        ProjectDefinition def = vetoContext.get();
        if (def == null) {
            def = cfg.getActiveProject().orElse(null);
        }
        String sourceDirNames = (def != null && StringUtils.hasText(def.getSourceDirNames()))
                ? def.getSourceDirNames()
                : ProjectDefinition.DEFAULT_SOURCE_DIR_NAMES;
        return DirectoryVetos.scan(p, Set.of("target", "build"), DirectoryVetos.toSet(sourceDirNames));
    }

    @ChunkVeto
    public boolean fileIsToLarge(Path p) {
        return p.toFile().length() > maxSize;
    }

    @ChunkVeto
    public boolean isHiddenFile(Path p) {
        return p.toFile().isHidden();
    }

    @ChunkVeto
    public boolean isImage(Path p) {
        String name = p.toFile().getName();
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    @ChunkVeto
    public boolean notListedInWhiteList(Path p) {
        de.spraener.prjxp.common.config.ProjectDefinition def = vetoContext.get();
        if (def == null) {
            def = cfg.getActiveProject().orElse(null);
        }
        if (def == null || !StringUtils.hasText(def.getChunoWhiteList())) {
            return false;
        }
        String ending = p.toString().substring(p.toString().lastIndexOf(".") + 1);
        return !def.getChunoWhiteList().contains(ending);
    }
}
