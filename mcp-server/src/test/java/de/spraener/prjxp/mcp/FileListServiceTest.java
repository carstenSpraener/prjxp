package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.FileListResult;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileListServiceTest {

    @Mock
    private PxChunkDaoProvider chunkDaoProvider;
    @Mock
    private ProjectRegistry projectRegistry;
    @Mock
    private PxChunkDao dao;

    private FileListService service;

    @BeforeEach
    void setUp() {
        service = new FileListService(chunkDaoProvider, projectRegistry);
    }

    @Test
    void listResolvesProjectAndDelegates() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(chunkDaoProvider.get("myproj")).thenReturn(Optional.of(dao));
        when(dao.listFiles("src", 50)).thenReturn(new FileListResult(List.of("src/a.java"), false));

        FileListResult result = service.list("src", "myproj", 50);

        assertThat(result.files()).containsExactly("src/a.java");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void listUnknownProjectThrows() {
        doThrow(new UnknownProjectException("nope", List.of("alpha"))).when(projectRegistry).ensureSearchable("nope");

        assertThatThrownBy(() -> service.list(null, "nope", 50))
                .isInstanceOf(UnknownProjectException.class);
    }

    @Test
    void listWithoutDaoReturnsEmpty() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(chunkDaoProvider.get("myproj")).thenReturn(Optional.empty());

        FileListResult result = service.list(null, "myproj", 50);

        assertThat(result.files()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }
}
