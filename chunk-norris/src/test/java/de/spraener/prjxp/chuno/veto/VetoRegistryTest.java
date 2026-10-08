package de.spraener.prjxp.chuno.veto;

import de.spraener.prjxp.common.config.ProjectDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VetoRegistryTest {

    private VetoRegistry registry;
    private VetoContext vetoContext;

    @BeforeEach
    void setUp() {
        vetoContext = mock(VetoContext.class);
        registry = new VetoRegistry(mock(ListableBeanFactory.class), vetoContext);
    }

    @Test
    void shouldVetoWithDefinitionSetsAndClearsContext() {
        ProjectDefinition def = mock(ProjectDefinition.class);
        Path path = Path.of("src/Test.vb");

        registry.shouldVeto(path, def);

        verify(vetoContext).set(def);
        verify(vetoContext).clear();
    }

    @Test
    void shouldVetoWithoutDefinitionDoesNotTouchContext() {
        Path path = Path.of("src/Test.vb");

        registry.shouldVeto(path);

        verifyNoInteractions(vetoContext);
    }

    @Test
    void shouldVetoWithDefinitionReturnsFalseWhenNoVetosRegistered() {
        ProjectDefinition def = mock(ProjectDefinition.class);
        Path path = Path.of("src/Test.vb");

        boolean result = registry.shouldVeto(path, def);

        assertThat(result).isFalse();
    }
}
