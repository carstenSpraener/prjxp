package de.spraener.prjxp.gldrtrvr.code.java;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.gldrtrvr.GldRtrvrCfg;
import de.spraener.prjxp.gldrtrvr.PxChunkDao;
import de.spraener.prjxp.gldrtrvr.chunks.PxChunkDaoInMemoryImpl;
import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.Test;
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
        public PxChunkDao pxChunkDao(ObjectMapper objectMapper, GldRtrvrCfg cfg) {
            cfg.setInputSource("../prjxp-enriched.jsonl");
            return new PxChunkDaoInMemoryImpl(objectMapper, cfg);
        }
    }

    @Autowired
    private JavaRetriever retriever;

    @Autowired
    private PxChunkDao chunkDao;

    //@Test
    public void testJavaCodePromptByMethodChunk() {
        List<PxChunk> chunks = chunkDao.findById("de.spraener.prjxp.chuno.code.java.JavaCodeChunker.Collection<? extends PxChunk> createClassFrameChunk(File, CompilationUnit, List<String>)");
        chunks.addAll(chunkDao.findById("de.spraener.prjxp.chuno.code.java.JavaCodeChunker.Collection<? extends PxChunk> createClassFrameChunk(File, CompilationUnit, List<String>).javadoc"));
        StringBuilder prompt = retriever.buildPromptForFindings(new StringBuilder(), List.of(chunks.get(0)));
        System.out.println(prompt);
    }

    //@Test
    public void testJavaCodePromptBy2MethodChunks() {
        List<PxChunk> chunksA = chunkDao.findById("de.spraener.prjxp.chuno.code.java.JavaCodeChunker.Collection<? extends PxChunk> createClassFrameChunk(File, CompilationUnit, List<String>)");
        List<PxChunk> chunksB = chunkDao.findById("de.spraener.prjxp.chuno.code.java.JavaCodeChunker.void createContainedMethodChunks(File, CompilationUnit, List<PxChunk>, TypeDeclaration<?>, List<String>)");
        StringBuilder prompt = retriever.buildPromptForFindings(new StringBuilder(), List.of(chunksA.get(chunksA.size() - 1), chunksB.get(0)));
        System.out.println(prompt);
    }
}