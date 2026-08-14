package de.spraener.prjxp.gldrtrvr.code.visualbasic;

import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingStrategy;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class VisualBasicRetrieverTest {
    private InMemoryChunkDao chunkDao;
    private VisualBasicRetriever retriever;

    @BeforeEach
    void setUp() {
        chunkDao = new InMemoryChunkDao();
        PxChunkDaoProvider chunkDaoProvider = new PxChunkDaoProvider(List.of(chunkDao));
        retriever = new VisualBasicRetriever(chunkDaoProvider, new ChunkRankingService(List.of(new ChunkRankingStrategy() {
            @Override
            public boolean supports(PxChunk chunk) {
                return true;
            }

            @Override
            public double rank(PxChunk chunk) {
                return 1.0;
            }
        })));
    }

    @Test
    void replacesMethodSignatureInFrameAndPlacesDocBeforeMethod() {
        PxChunk frame = chunk("Example.Example", null, "classFrame", """
                Public Module Example
                    Public Function Hello(name As String) As String
                    End Function
                End Module
                """);
        PxChunk method = chunk("Example.Example.Hello", "Example.Example", "method", """
                    Public Function Hello(name As String) As String
                        Return "Hallo " & name
                    End Function
                """);
        PxChunk doc = chunk("Example.Example.Hello.doc", "Example.Example.Hello", "methodDoc", """
                    ' Says hello to the given name.
                """);
        chunkDao.add(frame, method, doc);

        String prompt = retriever.buildPromptForFindings("default", List.of(method)).toString();

        assertThat(prompt)
                .contains("```vb")
                .contains("Public Module Example")
                .contains("' Says hello to the given name.")
                .contains("Return \"Hallo \" & name");
        assertThat(prompt.indexOf("' Says hello to the given name."))
                .isLessThan(prompt.indexOf("Public Function Hello(name As String) As String"));
    }

    @Test
    void replacesOnlyTheMatchingOverloadSignature() {
        PxChunk frame = chunk("Example.Example", null, "classFrame", """
                Public Module Example
                    Public Function Hello(name As String) As String
                    End Function
                    Public Function Hello(number As Integer) As String
                    End Function
                End Module
                """);
        PxChunk method = chunk("Example.Example.Hello.overload2", "Example.Example", "method", """
                    Public Function Hello(number As Integer) As String
                        Return number.ToString()
                    End Function
                """);
        chunkDao.add(frame, method);

        String prompt = retriever.buildPromptForFindings("default", List.of(method)).toString();

        assertThat(prompt)
                .contains("Public Function Hello(name As String) As String")
                .contains("Return number.ToString()");
        assertThat(prompt)
                .doesNotContain("Public Function Hello(number As Integer) As String\n    End Function");
    }

    @Test
    void combinesMultipartMethodViaDao() {
        PxChunk frame = chunk("Example.Example", null, "classFrame", """
                Public Module Example
                    Public Sub LongWork()
                    End Sub
                End Module
                """);
        PxChunk part0 = chunk("Example.Example.LongWork", "Example.Example", "method", """
                    Public Sub LongWork()
                        Dim value As String = "A"
                """);
        part0.setPart(0);
        part0.setTotal(2);
        part0.setOverlap(0);
        PxChunk part1 = chunk("Example.Example.LongWork", "Example.Example", "method", """
                        value = value & "B"
                    End Sub
                """);
        part1.setPart(1);
        part1.setTotal(2);
        part1.setOverlap(0);
        chunkDao.add(frame, part0, part1);

        String prompt = retriever.buildPromptForFindings("default", List.of(part0)).toString();

        assertThat(prompt)
                .contains("Dim value As String = \"A\"")
                .contains("value = value & \"B\"");
    }

    @Test
    void appendsMethodAsSeparateContextWhenFrameIsMissing() {
        PxChunk method = chunk("Example.Example.Orphan", "Example.Example", "method", """
                    Public Sub Orphan()
                        Console.WriteLine("orphan")
                    End Sub
                """);
        chunkDao.add(method);

        String prompt = retriever.buildPromptForFindings("default", List.of(method)).toString();

        assertThat(prompt)
                .contains("## VisualBasic-Methode Example.Example.Orphan")
                .contains("Console.WriteLine(\"orphan\")");
    }

    @Test
    void putsImportsBeforeMatchingFrame() {
        PxChunk imports = chunk("Example.imports", "Example", "imports", """
                Imports System.Collections.Generic
                """);
        PxChunk frame = chunk("Example.Example", null, "classFrame", """
                Public Module Example
                    Public Sub Work()
                    End Sub
                End Module
                """);
        chunkDao.add(imports, frame);

        String prompt = retriever.buildPromptForFindings("default", List.of(frame, imports)).toString();

        assertThat(prompt.indexOf("Imports System.Collections.Generic"))
                .isLessThan(prompt.indexOf("Public Module Example"));
    }

    private PxChunk chunk(String id, String parent, String section, String content) {
        return PxChunk.create(
                c -> c.setId(id),
                c -> c.setParent(parent),
                c -> c.setContent(content),
                c -> c.setMimeType("text/x-visual-basic-code"),
                c -> c.setPart(0),
                c -> c.setTotal(1),
                c -> c.setOverlap(0),
                c -> c.setSize(content.length()),
                c -> c.getMetadata().put("visualbasic_code_section", section)
        );
    }

    private static class InMemoryChunkDao implements PxChunkDao {
        private final PrjXPEmbeddingStoreReference reference = new PrjXPEmbeddingStoreReference();
        private final Map<String, List<PxChunk>> chunksById = new HashMap<>();

        InMemoryChunkDao() {
            reference.setDefault(true);
        }

        void add(PxChunk... chunks) {
            for (PxChunk chunk : chunks) {
                chunksById.computeIfAbsent(chunk.getId(), id -> new ArrayList<>()).add(chunk);
            }
        }

        @Override
        public PrjXPEmbeddingStoreReference getStoreReference() {
            return reference;
        }

        @Override
        public List<PxChunk> findById(String id) {
            return new ArrayList<>(chunksById.getOrDefault(id, List.of()));
        }

        @Override
        public List<PxChunk> findByMetaData(Map<String, String> metaData) {
            return List.of();
        }

        @Override
        public List<PxChunk> findRelevant(String question, int maxResults, double minScore) {
            return List.of();
        }

        @Override
        public Stream<PxChunk> findAll() {
            return chunksById.values().stream().flatMap(List::stream);
        }
    }
}
