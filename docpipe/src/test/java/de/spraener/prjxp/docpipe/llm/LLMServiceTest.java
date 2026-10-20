package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.chat.KIChatProvider;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.mcp.McpClientManager;
import de.spraener.prjxp.docpipe.content.ContentCreationTask;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.model.DPJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LLMServiceTest {

    @TempDir
    Path tempDir;

    private KIChatProvider chatProvider;
    private KIChat kiChat;
    private McpClientManager mcpClientManager;
    private LLMService service;

    @BeforeEach
    void setUp() {
        chatProvider = mock(KIChatProvider.class);
        kiChat = mock(KIChat.class);
        mcpClientManager = mock(McpClientManager.class);
        // No MCP servers configured in the test: decorate() is a passthrough.
        when(mcpClientManager.decorate(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new LLMService(chatProvider, mcpClientManager);
    }

    private ContentCreationTask task(String stereotype) {
        DPJob job = new DPJob();
        job.setRootDir(tempDir.toFile());
        job.setPxCfg(mock(PrjXPConfig.class));
        job.setContentCreationList(List.of());

        DPContentCreation creation = new DPContentCreation();
        creation.setStereotype(stereotype);

        return new ContentCreationTask(job, creation);
    }

    @Test
    void chat_modelFoundForStereotype_delegatesToKiChat() {
        when(chatProvider.getByStereotype("explainer")).thenReturn(Optional.of(kiChat));
        when(kiChat.chat("the prompt")).thenReturn("LLM ANSWER");

        String result = service.chat(task("explainer"), "the prompt");

        assertThat(result).isEqualTo("LLM ANSWER");
        verify(kiChat).chat("the prompt");
    }

    @Test
    void chat_noModelForStereotype_throwsIllegalArgumentException() {
        when(chatProvider.getByStereotype("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.chat(task("missing"), "the prompt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No Model found for Stereotype missing");
    }
}
