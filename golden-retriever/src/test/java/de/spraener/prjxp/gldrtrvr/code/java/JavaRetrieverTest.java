package de.spraener.prjxp.gldrtrvr.code.java;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.config.PrjXPJsonStreamProvider;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.chunks.PxChunkDaoInMemoryImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.List;

@SpringBootTest
class JavaRetrieverTest {
    @TestConfiguration
    static class TestConfig {
        @Bean
        public List<PxChunkDao> pxChunkDao(ObjectMapper objectMapper, PrjXPConfig cfg) {
            PrjXPJsonStreamProvider streamProvider = new PrjXPJsonStreamProvider(cfg);
            PrjXPEmbeddingStoreReference ref = new PrjXPEmbeddingStoreReference();
            ref.setDefault(true);
            return List.of(
                    new PxChunkDaoInMemoryImpl(ref, objectMapper, streamProvider)
                    .jsonlStream("../prjxp-enriched.jsonl")
            );
        }
    }

    @Autowired
    private JavaRetriever retriever;

    @Autowired
    private PxChunkDaoProvider chunkDaoProvider;
    //@Test
    public void testJavaCodePromptByMethodChunk() {
        PxChunkDao chunkDao = chunkDaoProvider.get("default").get();
        List<PxChunk> chunks = chunkDao.findById("de.spraener.prjxp.chuno.code.java.JavaCodeChunker.Collection<? extends PxChunk> createClassFrameChunk(File, CompilationUnit, List<String>)");
        chunks.addAll(chunkDao.findById("de.spraener.prjxp.chuno.code.java.JavaCodeChunker.Collection<? extends PxChunk> createClassFrameChunk(File, CompilationUnit, List<String>).javadoc"));
        StringBuilder prompt = retriever.buildPromptForFindings("default", new StringBuilder(), List.of(chunks.get(0)));
        System.out.println(prompt);
    }

    //@Test
    public void testJavaCodePromptBy2MethodChunks() {
        PxChunkDao chunkDao = chunkDaoProvider.get("default").get();
        List<PxChunk> chunksA = chunkDao.findById("de.spraener.prjxp.chuno.code.java.JavaCodeChunker.Collection<? extends PxChunk> createClassFrameChunk(File, CompilationUnit, List<String>)");
        List<PxChunk> chunksB = chunkDao.findById("de.spraener.prjxp.chuno.code.java.JavaCodeChunker.void createContainedMethodChunks(File, CompilationUnit, List<PxChunk>, TypeDeclaration<?>, List<String>)");
        StringBuilder prompt = retriever.buildPromptForFindings("default", new StringBuilder(), List.of(chunksA.get(chunksA.size() - 1), chunksB.get(0)));
        System.out.println(prompt);
    }
}