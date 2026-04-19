package de.spraener.prjxp.chuno.code.java;

import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.io.FileWriter;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public class JavaCodeChunkerTests {

    private File toTmpFile(String name, String code) {
        String fileName = name + ".java";
        File tmp = new File(fileName);
        try (FileWriter writer = new FileWriter(tmp)) {
            writer.write(code);
            writer.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
        tmp = new File(fileName);
        tmp.deleteOnExit();
        return tmp;
    }

    @Autowired
    JavaCodeChunker uut;

    @Test
    public void testSimpleClass() throws Exception {
        File code = toTmpFile("TestClass",
                """
                        class TestClass {
                            public void testMethod(String s) { 
                                System.out.println("Hello, World!"); 
                            } 
                        }
                        """
        );
        List<PxChunk> chunkList = uut.chunk(code).toList();
        assertThat(chunkList)
                .isNotEmpty()
                .anyMatch(c -> c.getId().equals("TestClass") && c.getContent().contains(
                        """
                                
                                class TestClass {
                                
                                    void testMethod(String)
                                }
                                """
                ))
                .anyMatch(c -> c.getId().equals("TestClass.imports") && c.getContent().equals("\n"))
                .anyMatch(c -> c.getId().equals("TestClass.void testMethod(String)") &&
                        c.getContent().equals(
                                "//Methode void testMethod(String) in class TestClass:\n" +
                                        "    public void testMethod(String s) {\n" +
                                        "        System.out.println(\"Hello, World!\");\n" +
                                        "    }\n")
                )
        ;
        // Chunk TestClass.dependencies siehe JavaDependenciesChunkerTests
    }

    @Test
    public void testSimpleClassWithPackageAndImports() throws Exception {
        File code = toTmpFile("TestClass",
                """
                        package de.spraener.test;
                        
                        import de.spraener.util.*;
                        
                        class TestClass {
                            public void testMethod(SprString s) { 
                                System.out.println("Hello, World!"); 
                            } 
                        }
                        """
        );
        Stream<PxChunk> chunks = uut.chunk(code);
        List<PxChunk> chunkList = chunks.toList();
        assertThat(chunkList)
                .isNotEmpty()
                .anyMatch(c -> {
                            return c.getId().equals("de.spraener.test.TestClass") &&
                                    c.getContent().contains("package de.spraener.test;") &&
                                    c.getContent().contains("import de.spraener.util.*;") &&
                                    c.getContent().contains("class TestClass {") &&
                                    c.getContent().contains("void testMethod(SprString)");
                        }
                )
                .anyMatch(c -> c.getId().equals("de.spraener.test.TestClass.imports") &&
                        c.getContent().contains("import de.spraener.util.*;")
                )
                .anyMatch(c -> c.getId().equals("de.spraener.test.TestClass.void testMethod(SprString)") &&
                        c.getContent().equals(
                        "//Methode void testMethod(SprString) in class de.spraener.test.TestClass:\n" +
                                "    public void testMethod(SprString s) {\n" +
                                "        System.out.println(\"Hello, World!\");\n" +
                                "    }\n")
                )
        ;
        // Chunk TestClass.dependencies siehe JavaDependenciesChunkerTests
    }

    //Ignored @Test()
    public void testInnerClass() throws Exception {
        File code = toTmpFile("TestClass",
                """
                        package de.spraener.test;
                        
                        import de.spraener.util.*;
                        
                        public class TestClass {
                            public static class InnerClass {
                               private int anAttr;
                        
                               private String aMethod(AType param) {
                               }
                            }
                        
                            public void testMethod(String s) { 
                                System.out.println("Hello, World!"); 
                            } 
                        }
                        """
        );
        List<PxChunk> chunkList = uut.chunk(code).toList();
        assertThat(chunkList)
                .isNotEmpty()
                .anyMatch(c -> c.getId().equals("de.spraener.test.TestClass.InnerClass"))
        ;
    }
}
