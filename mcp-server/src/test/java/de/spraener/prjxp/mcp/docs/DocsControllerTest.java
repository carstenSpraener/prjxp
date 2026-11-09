package de.spraener.prjxp.mcp.docs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocsControllerTest {

    @Mock
    private DocsService docsService;

    @InjectMocks
    private DocsController controller;

    @Test
    void treeDelegatesToService() {
        DocsNode tree = DocsNode.root("doc", List.of());
        when(docsService.buildTree()).thenReturn(tree);

        assertThat(controller.tree()).isSameAs(tree);
    }

    @Test
    void contentPresent() {
        when(docsService.readContent("a.md")).thenReturn(Optional.of("# Hello"));

        ResponseEntity<String> response = controller.content("a.md");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("text/markdown;charset=UTF-8");
        assertThat(response.getBody()).isEqualTo("# Hello");
    }

    @Test
    void contentMissing() {
        when(docsService.readContent("a.md")).thenReturn(Optional.empty());

        assertThat(controller.content("a.md").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void filePresent() {
        when(docsService.readAsset("a.png"))
                .thenReturn(Optional.of(new DocsService.DocsAsset(new byte[]{1, 2, 3}, "image/png")));

        ResponseEntity<byte[]> response = controller.file("a.png");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(response.getBody()).containsExactly(1, 2, 3);
    }

    @Test
    void fileMissing() {
        when(docsService.readAsset("a.png")).thenReturn(Optional.empty());

        assertThat(controller.file("a.png").getStatusCode().value()).isEqualTo(404);
    }
}
