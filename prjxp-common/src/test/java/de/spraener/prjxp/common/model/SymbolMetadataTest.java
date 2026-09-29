package de.spraener.prjxp.common.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SymbolMetadataTest {

    @Test
    void applyClassSetsFqnNameAndType() {
        Map<String, String> metadata = new HashMap<>();

        SymbolMetadata.applyClass(metadata, "classFrame", "de.spraener.test.TestClass", "TestClass");

        assertThat(metadata)
                .containsEntry(SymbolMetadata.SYMBOL_FQN, "de.spraener.test.TestClass")
                .containsEntry(SymbolMetadata.SYMBOL_NAME, "TestClass")
                .containsEntry(SymbolMetadata.SYMBOL_TYPE, "classFrame");
        assertThat(metadata).doesNotContainKey(SymbolMetadata.SYMBOL_CONTAINER_FQN);
        assertThat(metadata).doesNotContainKey(SymbolMetadata.SYMBOL_SIGNATURE_HASH);
    }

    @Test
    void applyMethodSetsAllKeysWithHashedSignature() {
        Map<String, String> metadata = new HashMap<>();

        SymbolMetadata.applyMethod(metadata, "method",
                "de.spraener.test.TestClass", "testMethod", "void testMethod(String s)");

        assertThat(metadata)
                .containsEntry(SymbolMetadata.SYMBOL_FQN, "de.spraener.test.TestClass#testMethod")
                .containsEntry(SymbolMetadata.SYMBOL_NAME, "testMethod")
                .containsEntry(SymbolMetadata.SYMBOL_TYPE, "method")
                .containsEntry(SymbolMetadata.SYMBOL_CONTAINER_FQN, "de.spraener.test.TestClass")
                .containsEntry(SymbolMetadata.SYMBOL_SIGNATURE_HASH, SymbolMetadata.signatureHash("void testMethod(String s)"));
    }

    @Test
    void signatureHashIsDeterministic() {
        assertThat(SymbolMetadata.signatureHash("void foo(int)"))
                .isEqualTo(SymbolMetadata.signatureHash("void foo(int)"));
    }

    @Test
    void signatureHashDiffersForDifferentSignatures() {
        assertThat(SymbolMetadata.signatureHash("void foo(int)"))
                .isNotEqualTo(SymbolMetadata.signatureHash("void foo(long)"));
    }

    @Test
    void signatureHashIsHexEncoded() {
        assertThat(SymbolMetadata.signatureHash("void foo(int)"))
                .hasSize(64)
                .matches("[0-9a-f]+");
    }
}
