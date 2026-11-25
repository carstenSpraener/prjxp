package de.spraener.prjxp.lpgdscript;

import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GdscriptFileViewProviderTest {

    private final GdscriptFileViewProvider provider = new GdscriptFileViewProvider();

    @SuppressWarnings("unchecked") // PxChunk.create(Consumer<PxChunk>...) varargs; a single consumer is always passed
    private static PxChunk unit(String id, String fromLine, String toLine, String content) {
        return PxChunk.create(c -> {
            c.setId(id);
            c.setFromLine(fromLine);
            c.setToLine(toLine);
            c.setContent(content);
        });
    }

    @Test
    void mimeTypeAndLanguage() {
        assertThat(provider.mimeType()).isEqualTo("text/x-gdscript-code");
        assertThat(provider.language()).isEqualTo("gdscript");
    }

    @Test
    void rendersInLineOrder() {
        // §5.2: combined units for one file, passed in scrambled order
        List<PxChunk> scrambled = List.of(
                unit("Player._ready()", "7", "9", "func _ready():\n\tprint(\"ready\")\n"),
                unit("Player.move(dir)", "5", "7", "func move(dir):\n\tposition += dir * speed\n"),
                unit("Player.fields", "3", "5", "@export var speed = 300\nvar health = 100\n"),
                unit("Player", "0", "3", "class_name Player\nextends CharacterBody2D\n## A player.\n"));

        String expected = "class_name Player\nextends CharacterBody2D\n## A player.\n"
                + "\n"
                + "@export var speed = 300\nvar health = 100\n"
                + "\n"
                + "func move(dir):\n\tposition += dir * speed\n"
                + "\n"
                + "func _ready():\n\tprint(\"ready\")\n";
        assertThat(provider.render(scrambled)).isEqualTo(expected);
    }

    @Test
    void dropsContainedRanges() {
        List<PxChunk> units = List.of(
                unit("inner", "2", "4", "inner content\n"),
                unit("outer", "0", "10", "outer content\n"));
        assertThat(provider.render(units)).isEqualTo("outer content\n");
    }

    @Test
    void dropsBlankUnits() {
        List<PxChunk> units = List.of(
                unit("blank", "0", "2", "   \n"),
                unit("real", "2", "4", "real content\n"));
        assertThat(provider.render(units)).isEqualTo("real content\n");
    }

    @Test
    void emptyInput_emptyString() {
        assertThat(provider.render(List.of())).isEmpty();
        assertThat(provider.render(null)).isEmpty();
    }
}
