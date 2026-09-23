package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.reader.FileViewProvider;
import de.spraener.prjxp.common.reader.GenericFileViewProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class FileViewRegistryTest {

    private static FileViewProvider provider(String mimeType, String language) {
        return new FileViewProvider() {
            @Override
            public String mimeType() {
                return mimeType;
            }

            @Override
            public String language() {
                return language;
            }

            @Override
            public String render(List<PxChunk> units) {
                return units.stream().map(PxChunk::getContent).collect(Collectors.joining("\n"));
            }
        };
    }

    @Test
    void knownMimeResolvesProvider() {
        FileViewProvider stub = provider("text/x-java-code", "java");
        FileViewRegistry registry = new FileViewRegistry(List.of(stub));

        assertThat(registry.forMime("text/x-java-code")).isSameAs(stub);
    }

    @Test
    void unknownMimeFallsBackToGeneric() {
        FileViewRegistry registry = new FileViewRegistry(List.of(provider("text/x-java-code", "java")));

        FileViewProvider resolved = registry.forMime("text/x-csharp");

        assertThat(resolved).isInstanceOf(GenericFileViewProvider.class);
        assertThat(resolved.language()).isEqualTo("generic");
    }

    @Test
    void wildcardProvidersAreIgnored() {
        FileViewProvider wildcard = provider("*/*", "generic");
        FileViewRegistry registry = new FileViewRegistry(List.of(wildcard));

        // the wildcard provider must not be registered under its mime; the internal generic fallback answers instead
        assertThat(registry.forMime("*/*")).isNotSameAs(wildcard);
        assertThat(registry.forMime("*/*")).isInstanceOf(GenericFileViewProvider.class);
    }
}
