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
public class ModelConfigLoader {
    private final ObjectMapper objectMapper;

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
