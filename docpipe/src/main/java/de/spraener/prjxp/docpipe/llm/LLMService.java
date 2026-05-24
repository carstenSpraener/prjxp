package de.spraener.prjxp.docpipe.llm;

import de.spraener.prjxp.docpipe.content.ContentCreationTask;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.model.DPJob;
import de.spraener.prjxp.docpipe.model.DPModelConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.PrintStream;

@Service
@RequiredArgsConstructor
@Log
public class LLMService {
    private final ChatModelFactory chatModelFactory;
    private static int promptCount = 0;

    private static PrintStream promptOut() {
        try {
            return new PrintStream("./dp-prompt-"+(++promptCount)+".txt");
        } catch( IOException e ) {
            throw new RuntimeException(e);
        }
    }

    public String chat(ContentCreationTask ccTask, String prompt) {
        DPContentCreation dpCC =  ccTask.getDpContentCreation();
        DPJob dpJob =  ccTask.getDpJob();

        String stereotype = dpCC.getStereotype();
        DPModelConfig cfg = dpJob.getModelForStereotype(stereotype).orElseThrow(
                () -> new IllegalArgumentException("No Model found for Stereotype " + stereotype)
        );
        ChatModel chatModel = chatModelFactory.create(cfg);
        try (PrintStream out = promptOut() ) {
            out.println(prompt);
            out.flush();
            return chatModel.chat(prompt);
        }
    }
}
