package de.spraener.prjxp.docpipe.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.logging.Level;

@Service
@RequiredArgsConstructor
@Log
/**
 * Service for loading LLM model configurations from the filesystem.
 * <p>
 * This class reads JSON configuration files and maps them to a list of 
 * {@link PrjXPChatModelReference} objects, which define the provider and model 
 * details for different stereotypes.
 * </p>
 */
public class ModelConfigLoader {
    private final ObjectMapper objectMapper;

    /**
     * Reads a list of chat model references from the specified JSON file.
     *
     * @param fileName the path to the configuration file (e.g., models.json)
     * @return a list of {@link PrjXPChatModelReference} objects, or an empty list if the file does not exist
     * @throws ConfigException if an error occurs while parsing the JSON content
     */
    public List<PrjXPChatModelReference> listFrom(String fileName) throws ConfigException {
        File file = new File(fileName);
        if( !file.exists() ) {
            return List.of();
        }
        // TODO: Refactor to use yaml instead of json
        try {
            return objectMapper.readValue(file, new TypeReference<List<PrjXPChatModelReference>>() {});
        } catch( Exception e ) {
            log.log(Level.WARNING, "Error reading file " + fileName, e);
            throw new ConfigException(e);
        }
    }
}
