package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.reader.FileViewProvider;
import de.spraener.prjxp.common.reader.GenericFileViewProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FileViewRegistry {
    private final Map<String, FileViewProvider> byMime = new LinkedHashMap<>();
    private final GenericFileViewProvider generic = new GenericFileViewProvider();

    public FileViewRegistry(List<FileViewProvider> providers) {
        for (FileViewProvider p : providers)
            if (p.mimeType() != null && !"*/*".equals(p.mimeType())) byMime.put(p.mimeType(), p);
    }

    /** Never empty: falls back to the language-agnostic generic reader. */
    public FileViewProvider forMime(String mime) {
        return byMime.getOrDefault(mime, generic);
    }
}
