package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.MethodView;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SymbolMetadata;
import de.spraener.prjxp.common.model.SymbolReadResult;
import de.spraener.prjxp.common.reader.ReaderSupport;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 3rd search level: deterministic symbol lookup in the index (symbol_* metadata),
 * reassembly of method + doc units, max 10 full matches plus the remaining FQNs.
 *
 * <p>Language-agnostic: any chunker that writes {@code symbol_*} metadata and a
 * {@code <language>_code_section} marker is supported (Java, C#, TypeScript,
 * Visual Basic). The section marker and the doc-id suffix differ per language
 * (e.g. C# uses {@code typeFrame} + {@code .doc}, Java uses {@code classFrame} +
 * {@code .javadoc}); both are understood here.
 */
@Service
@RequiredArgsConstructor
public class SymbolReaderService {
    private static final int LOOKUP_LIMIT = 10_000;
    private static final int MAX_FULL_MATCHES = 10;
    /** Section-marker metadata keys, one per supported language; the first present key wins. */
    private static final List<String> SECTION_KEYS = List.of(
            "java_code_section", "csharp_code_section", "typescript_code_section", "visualbasic_code_section");
    /** Doc-id suffixes per language (Java {@code .javadoc}, C# {@code .doc}). */
    private static final List<String> DOC_SUFFIXES = List.of(".javadoc", ".doc");

    private final PxChunkDaoProvider chunkDaoProvider;
    private final ProjectRegistry projectRegistry;

    public SymbolReadResult readBySignature(String method, String container, String project) {
        projectRegistry.ensureSearchable(project);   // throws UnknownProjectException for unknown projects
        if (method == null || method.isBlank())
            return SymbolReadResult.error("Parameter 'method' is required.");

        PxChunkDao dao = resolveDao(project);
        if (dao == null)
            return SymbolReadResult.error("No embedding store available for project '" + project + "'.");

        Map<String, String> filters = new HashMap<>();
        // A class FQN (contains '.', no '#') must be looked up by SYMBOL_FQN — the symbol
        // name of a class is only its short name, so a SYMBOL_NAME filter would miss it.
        // (Known Bug in v0.8.0-rc1, doc/Todos.md §3.)
        if (method.contains("#") || method.contains("."))
            filters.put(SymbolMetadata.SYMBOL_FQN, method.trim());
        else {
            filters.put(SymbolMetadata.SYMBOL_NAME, method.trim());
            if (container != null && !container.isBlank())
                filters.put(SymbolMetadata.SYMBOL_CONTAINER_FQN, container.trim());
        }

        List<PxChunk> hits;
        try {
            hits = dao.searchByIndex(filters, LOOKUP_LIMIT).stream().map(ScoredChunk::chunk).toList();
        } catch (UnsupportedOperationException uoe) {
            return SymbolReadResult.error("Index search is not supported by this embedding store — readBySignature requires a Lucene index.");
        }
        if (hits.isEmpty())
            return SymbolReadResult.error("No symbol matching '" + method + "'"
                    + (container != null && !container.isBlank() ? " in container '" + container + "'" : "") + " found in the index.");

        List<PxChunk> units = ReaderSupport.combineUnits(hits);
        Map<String, PxChunk> docByMethodId = new HashMap<>();
        List<PxChunk> methodUnits = new ArrayList<>();
        List<PxChunk> classUnits = new ArrayList<>();
        for (PxChunk u : units) {
            String section = sectionOf(u);
            if ("method".equals(section)) methodUnits.add(u);
            else if ("methodDoc".equals(section)) docByMethodId.put(u.getId(), u);
            else if ("classFrame".equals(section) || "typeFrame".equals(section)) classUnits.add(u);
            // imports / usings / unknown: never rendered
        }

        List<MethodView> views = new ArrayList<>();
        for (PxChunk m : methodUnits)
            views.add(toMethodView(m, findDoc(docByMethodId, m)));
        for (PxChunk c : classUnits)   // a class FQN was requested -> return the class frame as the "body"
            views.add(new MethodView(meta(c, SymbolMetadata.SYMBOL_FQN), c.getFile(),
                    ReaderSupport.parseLineOrNull(c.getFromLine()), ReaderSupport.parseLineOrNull(c.getToLine()), null, c.getContent()));

        if (views.isEmpty())
            return SymbolReadResult.error("Symbol found but no renderable method/class units (metadata without section info).");

        if (views.size() <= MAX_FULL_MATCHES)
            return new SymbolReadResult(views, List.of(), null);

        List<MethodView> full = views.subList(0, MAX_FULL_MATCHES);
        List<String> remaining = views.subList(MAX_FULL_MATCHES, views.size()).stream()
                .map(MethodView::fqn).distinct().toList();
        return new SymbolReadResult(List.copyOf(full), remaining, null);
    }

    private MethodView toMethodView(PxChunk m, PxChunk doc) {
        return new MethodView(meta(m, SymbolMetadata.SYMBOL_FQN), m.getFile(),
                ReaderSupport.parseLineOrNull(m.getFromLine()), ReaderSupport.parseLineOrNull(m.getToLine()),
                (doc != null && doc.getContent() != null && !doc.getContent().isBlank()) ? doc.getContent() : null,
                m.getContent());
    }

    private String meta(PxChunk u, String key) {
        return u.getMetadata().get(key);
    }

    /** The section marker of a unit, read from whichever language's key is present. */
    private static String sectionOf(PxChunk unit) {
        for (String key : SECTION_KEYS) {
            String section = unit.getMetadata().get(key);
            if (section != null) {
                return section;
            }
        }
        return null;
    }

    /** The doc unit belonging to a method, trying each language's doc-id suffix. */
    private static PxChunk findDoc(Map<String, PxChunk> docByMethodId, PxChunk method) {
        for (String suffix : DOC_SUFFIXES) {
            PxChunk doc = docByMethodId.get(method.getId() + suffix);
            if (doc != null) {
                return doc;
            }
        }
        return null;
    }

    private PxChunkDao resolveDao(String project) {   // same pattern as ReaderService (Phase 05)
        String resolved = projectRegistry.resolve(project);
        return chunkDaoProvider.get(resolved).orElse(null);
    }
}
