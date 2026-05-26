package de.spraener.prjxp.chuno.docs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.errorlog.PxLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Log
public class MetaInfReader {
    private final PxLogService logService;
    private static final ObjectMapper mapper = new ObjectMapper();

    public Map<String, String> readMetaInf(File originalFile) {
        // Pfad zur Sidecar-Datei konstruieren: originalname + ".meta"
        String metaFileName = originalFile.getName() + ".meta";
        File metaFile = new File(originalFile.getParentFile(), metaFileName);

        if (!metaFile.exists()) {
            log.fine("No sidecar-meta data found for: "+originalFile.getName());
            return new HashMap<>();
        }

        try {
            log.info("loading side car meta data from: "+metaFile.getName());
            // Einlesen des JSON-Inhalts in eine Map<String, String>
            return mapper.readValue(metaFile, new TypeReference<Map<String, String>>() {});
        } catch (IOException e) {
            logService.error(e, "Fehler beim Parsen der .meta Datei %s: %s", metaFile.getAbsolutePath(), e.getMessage());
            return new HashMap<>();
        }
    }
}
