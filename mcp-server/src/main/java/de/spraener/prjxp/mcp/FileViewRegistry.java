package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackRegistry;
import de.spraener.prjxp.common.reader.FileViewProvider;
import de.spraener.prjxp.common.reader.GenericFileViewProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FileViewRegistry {
    private final Map<String, FileViewProvider> byMime = new LinkedHashMap<>();
    private final GenericFileViewProvider generic = new GenericFileViewProvider();

    /** Test/standalone constructor: built-in Spring bean providers only. */
    public FileViewRegistry(List<FileViewProvider> providers) {
        this(providers, null);
    }

    /**
     * Spring wiring: built-in {@code FileViewProvider} beans plus the providers
     * exposed by external LanguagePacks (plugin JARs cannot be Spring beans, so
     * they hand their provider over via {@link LanguagePack#fileViewProvider()}).
     * A plugin provider wins over a built-in one with the same MIME type — the
     * same "plugin wins" rule as the {@link LanguagePackRegistry}.
     */
    @Autowired
    public FileViewRegistry(List<FileViewProvider> providers, ObjectProvider<LanguagePackRegistry> packRegistry) {
        for (FileViewProvider p : providers) {
            if (p.mimeType() != null && !"*/*".equals(p.mimeType())) byMime.put(p.mimeType(), p);
        }
        LanguagePackRegistry registry = packRegistry == null ? null : packRegistry.getIfAvailable();
        if (registry != null) {
            for (LanguagePack pack : registry.all()) {
                pack.fileViewProvider().ifPresent(p -> byMime.put(p.mimeType(), p));
            }
        }
    }

    /** Never empty: falls back to the language-agnostic generic reader. */
    public FileViewProvider forMime(String mime) {
        return byMime.getOrDefault(mime, generic);
    }
}
