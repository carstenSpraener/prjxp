package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.FileListResult;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class FileListService {

    private final PxChunkDaoProvider chunkDaoProvider;
    private final ProjectRegistry projectRegistry;

    public FileListResult list(String pattern, String project, int limit) {
        projectRegistry.ensureSearchable(project);   // throws UnknownProjectException for unknown projects
        String resolved = projectRegistry.resolve(project);
        PxChunkDao dao = chunkDaoProvider.get(resolved).orElse(null);
        if (dao == null) {
            return new FileListResult(List.of(), false);
        }
        return dao.listFiles(pattern, limit);
    }
}
