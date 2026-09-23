package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.model.MethodView;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SymbolMetadata;
import de.spraener.prjxp.common.model.SymbolReadResult;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 06 (doc §4): all 9 TDD cases for {@link SymbolReaderService}.
 * Fixtures build Java units via {@link PxChunk#create} with java_code_section + symbol_* metadata
 * (single part: part=0/total=1, so combineUnits round-trips the content); the dao's
 * searchByIndex is mocked to return the matching parts.
 */
@ExtendWith(MockitoExtension.class)
class SymbolReaderServiceTest {

    @Mock
    private PxChunkDaoProvider chunkDaoProvider;
    @Mock
    private PxChunkDao dao;
    @Mock
    private PrjXPConfig cfg;

    private SymbolReaderService service;

    @BeforeEach
    void setUp() {
        service = new SymbolReaderService(chunkDaoProvider, cfg);
    }

    // ------------------------------------------------------------------ fixtures

    private static PxChunk unit(String id, String parent, String file,
                                String fromLine, String toLine, String section,
                                Map<String, String> symbolMeta, String content) {
        return PxChunk.create(
                c -> c.setId(id),
                c -> c.setParent(parent),
                c -> c.setFile(file),
                c -> c.setFromLine(fromLine),
                c -> c.setToLine(toLine),
                c -> c.setPart(0),
                c -> c.setTotal(1),
                c -> c.setSize(content.length()),
                c -> c.setOverlap(0),
                c -> {
                    c.getMetadata().put("java_code_section", section);
                    c.getMetadata().putAll(symbolMeta);
                },
                c -> c.setContent(content)
        );
    }

    private static PxChunk methodUnit(String methodId, String fqn, String name, String containerFqn,
                                      String file, String fromLine, String toLine, String body) {
        Map<String, String> meta = new HashMap<>();
        meta.put(SymbolMetadata.SYMBOL_FQN, fqn);
        meta.put(SymbolMetadata.SYMBOL_NAME, name);
        meta.put(SymbolMetadata.SYMBOL_CONTAINER_FQN, containerFqn);
        return unit(methodId, containerFqn, file, fromLine, toLine, "method", meta, body);
    }

    private static PxChunk docUnit(String methodId, String file, String fromLine, String toLine, String javadoc) {
        return unit(methodId + ".javadoc", methodId, file, fromLine, toLine, "methodDoc", Map.of(), javadoc);
    }

    private static PxChunk classFrameUnit(String fqn, String simpleName, String file,
                                          String fromLine, String toLine, String frame) {
        Map<String, String> meta = new HashMap<>();
        meta.put(SymbolMetadata.SYMBOL_FQN, fqn);
        meta.put(SymbolMetadata.SYMBOL_NAME, simpleName);
        return unit(fqn, null, file, fromLine, toLine, "classFrame", meta, frame);
    }

    private static PxChunk importsUnit(String fqn, String file, String fromLine, String toLine, String imports) {
        Map<String, String> meta = new HashMap<>();
        meta.put(SymbolMetadata.SYMBOL_FQN, fqn);
        return unit(fqn + ".imports", fqn, file, fromLine, toLine, "imports", meta, imports);
    }

    private static ScoredChunk scored(PxChunk chunk) {
        return new ScoredChunk(chunk, 1.0);
    }

    /** Provider resolves project "p" to the mocked dao; searchByIndex returns the given units. */
    private void stubDaoReturns(PxChunk... chunks) {
        when(chunkDaoProvider.get("p")).thenReturn(Optional.of(dao));
        List<ScoredChunk> hits = new ArrayList<>();
        for (PxChunk chunk : chunks) {
            hits.add(scored(chunk));
        }
        when(dao.searchByIndex(anyMap(), anyInt())).thenReturn(hits);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void fqnLookupReturnsMethodWithJavadoc() {
        String file = "/src/de/ex/Foo.java";
        String body = "public void bar() {\n    // implementation\n}\n";
        PxChunk method = methodUnit("de.ex.Foo.public void bar()", "de.ex.Foo#bar", "bar", "de.ex.Foo",
                file, "10", "25", body);
        PxChunk doc = docUnit("de.ex.Foo.public void bar()", file, "5", "9", "Does something useful.");
        stubDaoReturns(method, doc);

        SymbolReadResult result = service.readBySignature("de.ex.Foo#bar", null, "p");

        assertThat(result.error()).isNull();
        assertThat(result.remainingFqns()).isEmpty();
        assertThat(result.matches()).hasSize(1);

        MethodView view = result.matches().get(0);
        assertThat(view.fqn()).isEqualTo("de.ex.Foo#bar");
        assertThat(view.file()).isEqualTo(file);
        assertThat(view.lineFrom()).isEqualTo(10);
        assertThat(view.lineTo()).isEqualTo(25);
        assertThat(view.javadoc()).isEqualTo("Does something useful.");
        assertThat(view.body()).isEqualTo(body);
    }

    @Test
    void bareNameWithContainerReturnsAllOverloads() {
        String file = "/src/de/ex/Foo.java";
        PxChunk m1 = methodUnit("de.ex.Foo.public void bar()", "de.ex.Foo#bar", "bar", "de.ex.Foo",
                file, "10", "25", "body-1");
        PxChunk m2 = methodUnit("de.ex.Foo.public void bar(int)", "de.ex.Foo#bar(int)", "bar", "de.ex.Foo",
                file, "30", "45", "body-2");
        PxChunk d1 = docUnit("de.ex.Foo.public void bar()", file, "5", "9", "doc-1");
        PxChunk d2 = docUnit("de.ex.Foo.public void bar(int)", file, "26", "29", "doc-2");
        stubDaoReturns(m1, m2, d1, d2);

        SymbolReadResult result = service.readBySignature("bar", "de.ex.Foo", "p");

        assertThat(result.error()).isNull();
        assertThat(result.matches()).hasSize(2);

        Map<String, MethodView> byFqn = result.matches().stream()
                .collect(Collectors.toMap(MethodView::fqn, v -> v));
        assertThat(byFqn).containsKeys("de.ex.Foo#bar", "de.ex.Foo#bar(int)");
        assertThat(byFqn.get("de.ex.Foo#bar").javadoc()).isEqualTo("doc-1");
        assertThat(byFqn.get("de.ex.Foo#bar").body()).isEqualTo("body-1");
        assertThat(byFqn.get("de.ex.Foo#bar(int)").javadoc()).isEqualTo("doc-2");
        assertThat(byFqn.get("de.ex.Foo#bar(int)").body()).isEqualTo("body-2");
    }

    @Test
    void bareNameWithoutContainerUsesNameOnlyFilter() {
        stubDaoReturns();   // empty hits — only the filter map matters here

        service.readBySignature("bar", null, "p");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(dao).searchByIndex(captor.capture(), eq(10_000));
        assertThat(captor.getValue()).isEqualTo(Map.of("symbol_name", "bar"));
    }

    @Test
    void classFqnReturnsFrameAsBody() {
        String file = "/src/de/ex/Foo.java";
        String frame = "public class Foo {\n    public void bar() { }\n}\n";
        PxChunk frameUnit = classFrameUnit("de.ex.Foo", "Foo", file, "1", "50", frame);
        PxChunk imports = importsUnit("de.ex.Foo", file, "1", "3", "import java.util.List;\n");
        stubDaoReturns(frameUnit, imports);

        SymbolReadResult result = service.readBySignature("de.ex.Foo", null, "p");

        assertThat(result.error()).isNull();
        assertThat(result.matches()).hasSize(1);   // the imports unit is never rendered

        MethodView view = result.matches().get(0);
        assertThat(view.fqn()).isEqualTo("de.ex.Foo");
        assertThat(view.file()).isEqualTo(file);
        assertThat(view.javadoc()).isNull();
        assertThat(view.body()).isEqualTo(frame);
    }

    @Test
    void moreThan10MatchesCapsAt10PlusRemaining() {
        String file = "/src/de/ex/C.java";
        List<PxChunk> units = new ArrayList<>();
        for (int i = 1; i <= 13; i++) {
            units.add(methodUnit("C.public void m" + i + "()", "C#m" + i, "m" + i, "C",
                    file, "" + (10 * i), "" + (20 * i), "body-" + i));
        }
        stubDaoReturns(units.toArray(new PxChunk[0]));

        SymbolReadResult result = service.readBySignature("m", "C", "p");   // the index returned 13 hits

        assertThat(result.error()).isNull();
        assertThat(result.matches()).hasSize(10);
        assertThat(result.remainingFqns()).containsExactly("C#m11", "C#m12", "C#m13");
    }

    @Test
    void noHitsReturnsError() {
        stubDaoReturns();   // empty

        SymbolReadResult result = service.readBySignature("bar", null, "p");

        assertThat(result.error()).contains("bar");
        assertThat(result.matches()).isEmpty();
    }

    @Test
    void unsupportedStoreReturnsError() {
        when(chunkDaoProvider.get("p")).thenReturn(Optional.of(dao));
        when(dao.searchByIndex(anyMap(), anyInt()))
                .thenThrow(new UnsupportedOperationException("chroma store"));

        SymbolReadResult result = service.readBySignature("bar", null, "p");

        assertThat(result.error()).containsIgnoringCase("lucene");
        assertThat(result.matches()).isEmpty();
    }

    @Test
    void noDaoReturnsError() {
        when(chunkDaoProvider.get("p")).thenReturn(Optional.empty());
        when(chunkDaoProvider.get("default")).thenReturn(Optional.empty());

        SymbolReadResult result = service.readBySignature("bar", null, "p");

        assertThat(result.error()).contains("No embedding store available").contains("'p'");
        verify(dao, never()).searchByIndex(anyMap(), anyInt());
    }

    @Test
    void blankMethodReturnsError() {
        SymbolReadResult result = service.readBySignature("", null, "p");

        assertThat(result.error()).contains("method");
        verify(chunkDaoProvider, never()).get(anyString());
        verify(dao, never()).searchByIndex(anyMap(), anyInt());
    }
}
