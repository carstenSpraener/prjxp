package de.spraener.prjxp.docpipe.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.java.Log;

import java.io.File;
import java.util.List;
import java.util.logging.Level;

@Data
@Log
public class DPModelConfig {
    private String stereotype;
    private String modelName;
    private String modelProviderURL;
    private String serverType = "ollama";

    public static List<DPModelConfig> listFrom(ObjectMapper mapper, String fileName) {
        File file = new File(fileName);
        if( !file.exists() ) {
            return List.of();
        }
        try {
            return mapper.readValue(file, new TypeReference<List<DPModelConfig>>() {});
        } catch( Exception e ) {
            log.log(Level.WARNING, "Error reading file " + fileName, e);
        }
        return List.of();
    }
}
