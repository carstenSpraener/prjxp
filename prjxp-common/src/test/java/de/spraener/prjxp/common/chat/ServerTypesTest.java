package de.spraener.prjxp.common.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServerTypesTest {

    @Test
    void values_returnsAllConstants() {
        ServerTypes[] values = ServerTypes.values();

        assertThat(values).hasSize(6);
        assertThat(values).contains(
                ServerTypes.UNKNOWN,
                ServerTypes.OLLAMA,
                ServerTypes.GEMINI,
                ServerTypes.LM_STUDIO,
                ServerTypes.OPEN_API,
                ServerTypes.CUSTOM
        );
    }

    @Test
    void ollama_serverType_returnsOllama() {
        assertThat(ServerTypes.OLLAMA.serverType()).isEqualTo("ollama");
    }

    @Test
    void gemini_serverType_returnsGemini() {
        assertThat(ServerTypes.GEMINI.serverType()).isEqualTo("gemini");
    }

    @Test
    void lmStudio_serverType_returnsLmStudio() {
        assertThat(ServerTypes.LM_STUDIO.serverType()).isEqualTo("lm-studio");
    }

    @Test
    void openApi_serverType_returnsOpenapi() {
        assertThat(ServerTypes.OPEN_API.serverType()).isEqualTo("openapi");
    }

    @Test
    void custom_serverType_returnsCustom() {
        assertThat(ServerTypes.CUSTOM.serverType()).isEqualTo("custom");
    }

    @Test
    void unknown_serverType_returnsUnknown() {
        assertThat(ServerTypes.UNKNOWN.serverType()).isEqualTo("unknown");
    }

    @Test
    void from_withOllama_returnsOllama() {
        assertThat(ServerTypes.from("ollama")).isEqualTo(ServerTypes.OLLAMA);
    }

    @Test
    void from_withGemini_returnsGemini() {
        assertThat(ServerTypes.from("gemini")).isEqualTo(ServerTypes.GEMINI);
    }

    @Test
    void from_withLmStudio_returnsLmStudio() {
        assertThat(ServerTypes.from("lm-studio")).isEqualTo(ServerTypes.LM_STUDIO);
    }

    @Test
    void from_withOpenapi_returnsOpenApi() {
        assertThat(ServerTypes.from("openapi")).isEqualTo(ServerTypes.OPEN_API);
    }

    @Test
    void from_withCustom_returnsCustom() {
        assertThat(ServerTypes.from("custom")).isEqualTo(ServerTypes.CUSTOM);
    }

    @Test
    void from_withUnknown_returnsUnknown() {
        assertThat(ServerTypes.from("unknown")).isEqualTo(ServerTypes.UNKNOWN);
    }

    @Test
    void from_withUnknownString_returnsUnknown() {
        assertThat(ServerTypes.from("foobar")).isEqualTo(ServerTypes.UNKNOWN);
    }

    @Test
    void from_withNull_returnsUnknown() {
        assertThat(ServerTypes.from(null)).isEqualTo(ServerTypes.UNKNOWN);
    }

    @Test
    void from_withEmptyString_returnsUnknown() {
        assertThat(ServerTypes.from("")).isEqualTo(ServerTypes.UNKNOWN);
    }

    @Test
    void from_caseSensitive() {
        assertThat(ServerTypes.from("OLLAMA")).isEqualTo(ServerTypes.UNKNOWN);
        assertThat(ServerTypes.from("Ollama")).isEqualTo(ServerTypes.UNKNOWN);
    }

    @Test
    void from_withWhitespace_returnsUnknown() {
        assertThat(ServerTypes.from(" ollama")).isEqualTo(ServerTypes.UNKNOWN);
        assertThat(ServerTypes.from("ollama ")).isEqualTo(ServerTypes.UNKNOWN);
    }
}
