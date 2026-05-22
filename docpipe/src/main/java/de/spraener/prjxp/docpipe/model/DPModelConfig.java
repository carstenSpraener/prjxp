package de.spraener.prjxp.docpipe.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.docpipe.config.EnvResolver;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.ToString;
import lombok.extern.java.Log;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

@Data
@Log
public class DPModelConfig {
    @NotBlank(message="You have to define a stereotype for model referencing")
    private String stereotype;
    @NotBlank(message="You hav to set a model name (LLM name to use)")
    private String modelName;
    private String modelProviderURL;
    private String kiChatImpl;
    @NotBlank(message="There is no serverType. Please set one of ollama, gemini, openai or custom")
    private String serverType;
    private double temperature = 0.2;
    private int timeOutSeconds = 60;
    private Map<String,String> args;
}
