package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackRegistry;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.reader.FileViewProvider;
import de.spraener.prjxp.common.reader.GenericFileViewProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    private static LanguagePackRegistry packRegistryWithProvider(FileViewProvider pluginProvider, String mime) {
        LanguagePack pluginPack = new LanguagePack() {
            @Override
            public String language() {
                return "csharp";
            }

            @Override
            public String mimeType() {
                return mime;
            }

            @Override
            public Optional<FileViewProvider> fileViewProvider() {
                return Optional.of(pluginProvider);
            }
        };
        return new LanguagePackRegistry(List.of(), List.of(pluginPack));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<LanguagePackRegistry> packRegistryProvider(LanguagePackRegistry registry) {
        ObjectProvider<LanguagePackRegistry> mock = mock(ObjectProvider.class);
        when(mock.getIfAvailable()).thenReturn(registry);
        return mock;
    }

    @Test
    void pluginPackProviderIsRegistered() {
        FileViewProvider pluginProvider = provider("text/x-csharp-code", "csharp");
        FileViewRegistry registry = new FileViewRegistry(List.of(),
                packRegistryProvider(packRegistryWithProvider(pluginProvider, "text/x-csharp-code")));

        assertThat(registry.forMime("text/x-csharp-code")).isSameAs(pluginProvider);
    }

    @Test
    void pluginProviderWinsOverBuiltInWithSameMime() {
        FileViewProvider builtIn = provider("text/x-csharp-code", "csharp-builtin");
        FileViewProvider pluginProvider = provider("text/x-csharp-code", "csharp");
        FileViewRegistry registry = new FileViewRegistry(List.of(builtIn),
                packRegistryProvider(packRegistryWithProvider(pluginProvider, "text/x-csharp-code")));

        assertThat(registry.forMime("text/x-csharp-code")).isSameAs(pluginProvider);
    }

    @Test
    void missingPackRegistryKeepsBuiltInProvidersOnly() {
        FileViewProvider builtIn = provider("text/x-java-code", "java");
        FileViewRegistry registry = new FileViewRegistry(List.of(builtIn), null);

        assertThat(registry.forMime("text/x-java-code")).isSameAs(builtIn);
        assertThat(registry.forMime("text/x-csharp-code")).isInstanceOf(GenericFileViewProvider.class);
    }
}
