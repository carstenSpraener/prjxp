package de.spraener.prjxp.chuno.veto;

import de.spraener.prjxp.chuno.ChunkNorrisConfig;
import de.spraener.prjxp.common.annotations.ChunkVeto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;

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
    private final ChunkNorrisConfig cfg;

    @ChunkVeto
    public boolean isBuildArtifact(Path p) {
        return p.toString().contains("target") || p.toString().contains("build");
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
        if (!StringUtils.hasText(cfg.getWhiteList())) {
            return false;
        }
        String ending = p.toString().substring(p.toString().lastIndexOf(".") + 1);
        return !cfg.getWhiteList().contains(ending);
    }
}
