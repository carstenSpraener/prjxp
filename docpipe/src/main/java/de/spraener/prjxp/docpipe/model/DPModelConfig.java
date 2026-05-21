package de.spraener.prjxp.docpipe.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.docpipe.config.EnvResolver;
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
    private String stereotype;
    private String modelName;
    private String modelProviderURL;
    private String kiChatImpl;
    private String serverType = "ollama";
    private double temperature = 0.2;
    private int timeOutSeconds = 60;
    private Map<String,String> args;
}
