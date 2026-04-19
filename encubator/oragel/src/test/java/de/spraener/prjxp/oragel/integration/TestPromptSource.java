package de.spraener.prjxp.oragel.integration;

import de.spraener.prjxp.oragel.PromptSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class TestPromptSource implements PromptSource {
    private final List<String> questions = new ArrayList<>();

    public void ask(String question) {
        questions.add(question);
    }

    @Override
    public Stream<String> stream() {
        // Wir fügen am Ende ein "exit" hinzu, damit der Runner stoppt
        return Stream.concat(questions.stream(), Stream.of("exit"));
    }
}