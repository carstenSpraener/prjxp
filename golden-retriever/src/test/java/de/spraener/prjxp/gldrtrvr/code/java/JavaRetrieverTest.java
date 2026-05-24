package de.spraener.prjxp.gldrtrvr.code.java;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.config.PrjXPJsonStreamProvider;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.chunks.PxChunkDaoInMemoryImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class JavaRetrieverTest {
    private PxChunkDaoInMemoryImpl pxChunkDaoInMemoryImpl;

    @TestConfiguration
    static class TestConfig {

        @Bean
        public PxChunkDaoInMemoryImpl pxChunkDaoInMemoryImpl(ObjectMapper objectMapper, PrjXPConfig cfg) {
            PrjXPJsonStreamProvider streamProvider = new PrjXPJsonStreamProvider(cfg);
            PrjXPEmbeddingStoreReference ref = new PrjXPEmbeddingStoreReference();
            ref.setDefault(true);
            return new PxChunkDaoInMemoryImpl(ref, objectMapper, streamProvider)
                            .jsonlStream("../prjxp-enriched.jsonl");
        }
        @Bean
        public List<PxChunkDao> pxChunkDaos(PxChunkDaoInMemoryImpl chunkDao) {
            return List.of(chunkDao);
        }
    }

    @Autowired
    private JavaRetriever retriever;

    @Autowired
    private PxChunkDaoInMemoryImpl  pxChunkDaoInMemory;

    @Autowired
    private PxChunkDaoProvider chunkDaoProvider;

    //@Test
    public void testJavaCodePromptByMethodChunk() {
        PxChunkDao chunkDao = chunkDaoProvider.get("default").get();
        List<PxChunk> chunks = chunkDao.findById("de.spraener.prjxp.chuno.code.java.JavaCodeChunker.Collection<? extends PxChunk> createClassFrameChunk(File, CompilationUnit, List<String>)");
        chunks.addAll(chunkDao.findById("de.spraener.prjxp.chuno.code.java.JavaCodeChunker.Collection<? extends PxChunk> createClassFrameChunk(File, CompilationUnit, List<String>).javadoc"));
        StringBuilder prompt = retriever.buildPromptForFindings("default", List.of(chunks.get(0)));
        System.out.println(prompt);
    }


    //@Test
    public void testJavaCodePromptBy2MethodChunks() {
        PxChunkDao chunkDao = chunkDaoProvider.get("default").get();
        List<PxChunk> chunksA = chunkDao.findById("de.spraener.prjxp.chuno.code.java.JavaCodeChunker.Collection<? extends PxChunk> createClassFrameChunk(File, CompilationUnit, List<String>)");
        List<PxChunk> chunksB = chunkDao.findById("de.spraener.prjxp.chuno.code.java.JavaCodeChunker.void createContainedMethodChunks(File, CompilationUnit, List<PxChunk>, TypeDeclaration<?>, List<String>)");
        StringBuilder prompt = retriever.buildPromptForFindings("default", List.of(chunksA.get(chunksA.size() - 1), chunksB.get(0)));
        System.out.println(prompt);
    }

    // @Test
    public void testNoJavaChunk() {
        PxChunk tsChunk = PxChunk.create(
                c->{
                    c.setId("base-component.ts");
                    c.setContent("export class BaseComponent{}");
                    c.getMetadata().put("type", "x-text/typescript");
                }
        );
        pxChunkDaoInMemory.addChunk(tsChunk);
        String prompt = retriever.buildPromptForFindings("default", List.of(tsChunk)).toString();
        assertTrue("".equals(prompt));
    }


    // @Test
    public void testOrphanedChunk() {
        PxChunk tsChunk = PxChunk.create(
                c->{
                    c.setId("de.test.HelloWorld.sayHello");
                    c.setParent("de.test.HelloWorld");
                    c.setContent("    public void sayHello() {\n        System.out.println(\"Hello World\");\n    }");
                    c.getMetadata().put("type", "text/x-java-code");
                    c.getMetadata().put("java_code_section", "method");
                    c.setPart(0);
                    c.setTotal(2);
                }
        );
        pxChunkDaoInMemory.addChunk(tsChunk);
        PxChunk noneDBChunk = PxChunk.create(
                c->{
                    c.setId("de.other.HelloWorld.sayHello");
                    c.setParent("de.other.HelloWorld");
                    c.setContent("    public void sayHello() {\n        System.out.println(\"Hello World\");\n    }");
                    c.getMetadata().put("type", "text/x-java-code");
                    c.getMetadata().put("java_code_section", "method");
                    c.setPart(0);
                    c.setTotal(2);
                }
        );
        List<PxChunk> chunkList = new ArrayList<>();
        chunkList.add(tsChunk);
        chunkList.add(null);
        chunkList.add(noneDBChunk);
        String prompt = retriever.buildPromptForFindings("default", chunkList).toString();
        assertTrue("".equals(prompt));
    }

}