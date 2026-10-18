package de.spraener.prjxp.chuno.code.java;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.SymbolMetadata;
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

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Test
    public void testSimpleClass() throws Exception {
        File code = toTmpFile("TestClass",
                """
                        class TestClass {
                            /**
                              * A little java doc
                              */
                            public void testMethod(String s) { 
                                System.out.println("Hello, World!"); 
                            } 
                        }
                        """
        );
        List<PxChunk> chunkList = uut.chunk(code).toList();
        assertThat(chunkList)
                .isNotEmpty()
                .anyMatch(c -> c.getId().equals("TestClass") &&
                        c.getContent().contains("class TestClass {") &&
                        c.getContent().contains("void testMethod(String)")
                )
                .noneMatch(c -> c.getId().equals("TestClass.imports"))
                .anyMatch(c -> c.getId().equals("TestClass.void testMethod(String)") &&
                        c.getContent().equals(
                                        "    public void testMethod(String s) {\n" +
                                        "        System.out.println(\"Hello, World!\");\n" +
                                        "    }\n")
                )
                .anyMatch( c -> c.getId().equals("TestClass.void testMethod(String).javadoc") &&
                                c.getContent().contains("A little java doc")
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
                                        "    public void testMethod(SprString s) {\n" +
                                        "        System.out.println(\"Hello, World!\");\n" +
                                        "    }\n")
                )
        ;
        // Chunk TestClass.dependencies siehe JavaDependenciesChunkerTests
    }

    @Test
    public void testSymbolMetadata() throws Exception {
        File code = toTmpFile("TestClass",
                """
                        package de.spraener.test;

                        import de.spraener.util.SprString;

                        class TestClass {
                            /**
                              * A little java doc
                              */
                            public void testMethod(SprString s) {
                                System.out.println("Hello, World!");
                            }
                        }
                        """
        );
        List<PxChunk> chunkList = uut.chunk(code).toList();

        assertThat(chunkList)
                .anyMatch(c -> c.getId().equals("de.spraener.test.TestClass") &&
                        "classFrame".equals(c.getMetadata().get(SymbolMetadata.SYMBOL_TYPE)) &&
                        "de.spraener.test.TestClass".equals(c.getMetadata().get(SymbolMetadata.SYMBOL_FQN)) &&
                        "TestClass".equals(c.getMetadata().get(SymbolMetadata.SYMBOL_NAME)))
                .anyMatch(c -> c.getId().equals("de.spraener.test.TestClass.void testMethod(SprString)") &&
                        "method".equals(c.getMetadata().get(SymbolMetadata.SYMBOL_TYPE)) &&
                        "de.spraener.test.TestClass#testMethod".equals(c.getMetadata().get(SymbolMetadata.SYMBOL_FQN)) &&
                        "testMethod".equals(c.getMetadata().get(SymbolMetadata.SYMBOL_NAME)) &&
                        "de.spraener.test.TestClass".equals(c.getMetadata().get(SymbolMetadata.SYMBOL_CONTAINER_FQN)) &&
                        SymbolMetadata.signatureHash("void testMethod(SprString)")
                                .equals(c.getMetadata().get(SymbolMetadata.SYMBOL_SIGNATURE_HASH)))
                .anyMatch(c -> c.getId().equals("de.spraener.test.TestClass.void testMethod(SprString).javadoc") &&
                        "methodDoc".equals(c.getMetadata().get(SymbolMetadata.SYMBOL_TYPE)) &&
                        "de.spraener.test.TestClass#testMethod".equals(c.getMetadata().get(SymbolMetadata.SYMBOL_FQN)))
                .anyMatch(c -> c.getId().equals("de.spraener.test.TestClass.imports") &&
                        "imports".equals(c.getMetadata().get(SymbolMetadata.SYMBOL_TYPE)) &&
                        "de.spraener.test.TestClass".equals(c.getMetadata().get(SymbolMetadata.SYMBOL_FQN)))
        ;
    }

    @Test
    public void testConstructors() throws Exception {
        File code = toTmpFile("ConstructorClass",
                """
                        package de.spraener.test;

                        class ConstructorClass {
                            /**
                             * Primary constructor with Javadoc
                             */
                            public ConstructorClass(int x) {
                                this.x = x;
                            }

                            @Deprecated
                            public ConstructorClass(String s) {
                                this.s = s;
                            }

                            private int x;
                            private String s;

                            public void doSomething() {
                                System.out.println(x);
                            }
                        }
                        """
        );
        List<PxChunk> chunkList = uut.chunk(code).toList();

        // Constructor with Javadoc. Note: getDeclarationAsString(false,false,false) yields
        // "ConstructorClass(int)" — no modifiers, no parameter names (same convention as methods).
        assertThat(chunkList)
                .anyMatch(c -> c.getId().equals("de.spraener.test.ConstructorClass.ConstructorClass(int)") &&
                        "constructor".equals(c.getMetadata().get("java_code_section")) &&
                        c.getContent().contains("this.x = x;")
                )
                .anyMatch(c -> c.getId().equals("de.spraener.test.ConstructorClass.ConstructorClass(int).javadoc") &&
                        "methodDoc".equals(c.getMetadata().get("java_code_section")) &&
                        c.getContent().contains("Primary constructor with Javadoc")
                );

        // Constructor with annotation — the annotation must appear exactly once (no duplication)
        assertThat(chunkList)
                .anyMatch(c -> c.getId().equals("de.spraener.test.ConstructorClass.ConstructorClass(String)") &&
                        "constructor".equals(c.getMetadata().get("java_code_section")) &&
                        countOccurrences(c.getContent(), "@Deprecated") == 1 &&
                        c.getContent().contains("this.s = s;")
                );

        // Frame unit must contain constructor signature lines
        assertThat(chunkList)
                .anyMatch(c -> c.getId().equals("de.spraener.test.ConstructorClass") &&
                        "classFrame".equals(c.getMetadata().get("java_code_section")) &&
                        c.getContent().contains("ConstructorClass(int)") &&
                        c.getContent().contains("ConstructorClass(String)")
                );

        // Method still works
        assertThat(chunkList)
                .anyMatch(c -> c.getId().equals("de.spraener.test.ConstructorClass.void doSomething()") &&
                        "method".equals(c.getMetadata().get("java_code_section"))
                );
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
