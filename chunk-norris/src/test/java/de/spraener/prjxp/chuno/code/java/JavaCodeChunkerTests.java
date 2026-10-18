package de.spraener.prjxp.chuno.code.java;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.SymbolMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    @Test
    public void testEnumConstants() throws Exception {
        // Note: a constant with BOTH a constructor arg AND a body (e.g. "GREEN(int v) { ... }")
        // is not parseable by JavaParser, so we use separate constants: one with args, one with body.
        File code = toTmpFile("Color",
                """
                        package de.spraener.test;

                        public enum Color {
                            RED,
                            GREEN(42),
                            BLUE {
                                public String toString() { return "Blue"; }
                            };

                            private int value;

                            Color() { this.value = 0; }
                            Color(int v) { this.value = v; }

                            public int getValue() { return value; }
                        }
                        """
        );
        List<PxChunk> chunkList = uut.chunk(code).toList();

        // Enum constants unit — all constants + the ';' separator, but NOT the following field
        assertThat(chunkList)
                .anyMatch(c -> c.getId().equals("de.spraener.test.Color.constants") &&
                        "enumConstants".equals(c.getMetadata().get("java_code_section")) &&
                        "de.spraener.test.Color".equals(c.getParent()) &&
                        c.getContent().contains("RED,") &&
                        c.getContent().contains("GREEN(42)") &&
                        c.getContent().contains("BLUE {") &&
                        c.getContent().contains(";") &&
                        !c.getContent().contains("private int value")
                );

        // Other chunks still present: method, frame, etc.
        assertThat(chunkList)
                .anyMatch(c -> c.getId().equals("de.spraener.test.Color") &&
                        "classFrame".equals(c.getMetadata().get("java_code_section"))
                )
                .anyMatch(c -> c.getId().equals("de.spraener.test.Color.int getValue()") &&
                        "method".equals(c.getMetadata().get("java_code_section"))
                );
    }

    @Test
    public void testNestedTypes() throws Exception {
        File code = toTmpFile("OuterClass",
                """
                        package de.spraener.test;

                        public class OuterClass {

                            private String outerField;

                            public void outerMethod() {
                                System.out.println(outerField);
                            }

                            class InnerClass {
                                private int innerValue;

                                public void doInnerWork() {
                                    System.out.println(innerValue);
                                }
                            }

                            static enum NestedEnum {
                                ALPHA, BETA;

                                private NestedEnum() {}
                            }
                        }
                        """
        );
        List<PxChunk> chunkList = uut.chunk(code).toList();

        // Frame units for all three types: outer, inner, nested enum
        assertThat(chunkList)
                .anyMatch(c -> c.getId().equals("de.spraener.test.OuterClass") &&
                        "classFrame".equals(c.getMetadata().get("java_code_section")) &&
                        c.getContent().contains("class OuterClass {") &&
                        c.getContent().contains("String outerField") &&
                        c.getContent().contains("void outerMethod()"))
                .anyMatch(c -> c.getId().equals("de.spraener.test.OuterClass.InnerClass") &&
                        "classFrame".equals(c.getMetadata().get("java_code_section")) &&
                        c.getContent().contains("class InnerClass {") &&
                        c.getContent().contains("int innerValue") &&
                        c.getContent().contains("void doInnerWork()"))
                .anyMatch(c -> c.getId().equals("de.spraener.test.OuterClass.NestedEnum") &&
                        "classFrame".equals(c.getMetadata().get("java_code_section")) &&
                        c.getContent().contains("enum NestedEnum {"));

        // Method unit for inner class method
        assertThat(chunkList)
                .anyMatch(c -> c.getId().equals("de.spraener.test.OuterClass.InnerClass.void doInnerWork()") &&
                        "method".equals(c.getMetadata().get("java_code_section")) &&
                        c.getContent().contains("System.out.println(innerValue)"));

        // Enum constants unit for nested enum
        assertThat(chunkList)
                .anyMatch(c -> c.getId().equals("de.spraener.test.OuterClass.NestedEnum.constants") &&
                        "enumConstants".equals(c.getMetadata().get("java_code_section")) &&
                        c.getContent().contains("ALPHA") &&
                        c.getContent().contains("BETA"));

        // Outer class method still works (regression)
        assertThat(chunkList)
                .anyMatch(c -> c.getId().equals("de.spraener.test.OuterClass.void outerMethod()") &&
                        "method".equals(c.getMetadata().get("java_code_section")));
    }

    @Test
    public void testParseFailureRawFallback() throws Exception {
        // Unparseable file: missing closing brace, 30+ lines
        String code = """
                package de.spraener.test;

                import java.util.List;
                import java.util.ArrayList;

                /**
                 * This class has a syntax error.
                 * The closing brace is missing on purpose.
                 */
                public class BrokenClass {

                    private String name;
                    private int    value;
                    private List<String> items = new ArrayList<>();

                    /** Default constructor */
                    public BrokenClass() {
                        this.name = "default";
                        this.value = 0;
                    }

                    /** Constructor with name */
                    public BrokenClass(String name) {
                        this.name = name;
                        this.value = 42;
                    }

                    public void doWork() {
                        System.out.println(name);
                        for (String item : items) {
                            System.out.println(item);
                        }
                    }

                    public String getName() { return name; }
                    public void  setName(String n) { this.name = n; }
                    public int   getValue() { return value; }
                    public void  setValue(int v) { this.value = v; }

                // MISSING CLOSING BRACE — triggers ParseProblemException
                """;
        File codeFile = toTmpFile("BrokenClass", code);
        List<String> codeLines = Files.readAllLines(codeFile.toPath(), StandardCharsets.UTF_8);
        String expectedContent = String.join("\n", codeLines);

        List<PxChunk> chunkList = uut.chunk(codeFile).toList();

        // At least one rawFile unit
        assertThat(chunkList)
                .isNotEmpty()
                .anyMatch(c -> "rawFile".equals(c.getMetadata().get("java_code_section")))
                .anyMatch(c -> "text/x-java-code".equals(c.getMimeType()))
                .allSatisfy(c -> {
                    if ("rawFile".equals(c.getMetadata().get("java_code_section"))) {
                        assertThat(c.getId()).isEqualTo(codeFile.getAbsolutePath());
                    }
                });

        // Concatenate all rawFile part contents (with '\n' between parts, mirroring the
        // provider's frames.isEmpty() fallback) and verify they reconstruct the file text exactly
        String reconstructed = chunkList.stream()
                .filter(c -> "rawFile".equals(c.getMetadata().get("java_code_section")))
                .sorted((a, b) -> Integer.compare(a.getPart(), b.getPart()))
                .map(PxChunk::getContent)
                .collect(java.util.stream.Collectors.joining("\n"));

        assertThat(reconstructed).isEqualTo(expectedContent);
    }

    @Test
    public void testParseableFileNoRawFallback() throws Exception {
        // Parseable file — rawFile fallback must NOT trigger
        File code = toTmpFile("GoodClass",
                """
                        package de.spraener.test;

                        public class GoodClass {
                            private int x = 1;
                            private int y = 2;

                            public GoodClass() {}

                            public void doSomething() {
                                System.out.println(x + y);
                            }

                            public int getX() { return x; }
                            public void setX(int x) { this.x = x; }

                            public int getY() { return y; }
                            public void setY(int y) { this.y = y; }

                            @Override
                            public String toString() {
                                return "GoodClass{" + x + ", " + y + "}";
                            }
                        }
                        """
        );

        List<PxChunk> chunkList = uut.chunk(code).toList();

        // No rawFile units should exist
        assertThat(chunkList)
                .noneMatch(c -> "rawFile".equals(c.getMetadata().get("java_code_section")));
    }

    @Test
    public void testParseFailureRawFallbackMultiPart() throws Exception {
        // >10k chars with a syntax error (missing closing brace) → multiple line-aligned parts
        StringBuilder sb = new StringBuilder("package de.spraener.test;\n\npublic class BigBroken {\n");
        for (int i = 0; i < 400; i++) {
            sb.append("    private int field").append(i).append(" = ").append(i).append(";\n");
        }
        File codeFile = toTmpFile("BigBroken", sb.toString());
        List<String> codeLines = Files.readAllLines(codeFile.toPath(), StandardCharsets.UTF_8);
        String expectedContent = String.join("\n", codeLines);

        List<PxChunk> chunkList = uut.chunk(codeFile).toList();
        List<PxChunk> parts = chunkList.stream()
                .filter(c -> "rawFile".equals(c.getMetadata().get("java_code_section")))
                .sorted((a, b) -> Integer.compare(a.getPart(), b.getPart()))
                .toList();

        assertThat(parts).isNotEmpty();
        assertThat(parts.get(0).getTotal()).isGreaterThan(1);

        // Line-aligned tiling: parts cover the file lines contiguously, no gaps, no overlaps
        assertThat(parts.get(0).getFromLine()).isEqualTo("1");
        for (int i = 1; i < parts.size(); i++) {
            assertThat(parts.get(i).getFromLine()).isEqualTo(parts.get(i - 1).getToLine());
        }
        assertThat(parts.get(parts.size() - 1).getToLine()).isEqualTo("" + (codeLines.size() + 1));

        // The provider's '\n'-separated concatenation reconstructs the file text exactly
        String reconstructed = String.join("\n", parts.stream().map(PxChunk::getContent).toList());
        assertThat(reconstructed).isEqualTo(expectedContent);
    }

    @Test
    public void testNonFileInputReturnsEmpty() throws Exception {
        // A directory named like a Java file: readAllLines fails, the raw-fallback re-read
        // fails as well → old behavior (empty stream), no exception escapes chunk()
        File dir = new File("NotAFile.java");
        assertThat(dir.mkdirs()).isTrue();
        try {
            List<PxChunk> chunkList = uut.chunk(dir).toList();
            assertThat(chunkList).isEmpty();
        } finally {
            assertThat(dir.delete()).isTrue();
        }
    }
}
