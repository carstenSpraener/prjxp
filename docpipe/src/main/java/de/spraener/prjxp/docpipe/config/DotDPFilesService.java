package de.spraener.prjxp.docpipe.config;

import de.spraener.prjxp.docpipe.DocPipeConfig;
import de.spraener.prjxp.docpipe.content.ContentCreationTask;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;

@Component
public class DotDPFilesService {
    private static final String HASH_FILE_PATH = "content-hashes.properties";
    public static final String DP_DIR= ".dp";

    public File dotPipeDir(ContentCreationTask cct) {
        return new File(cct.getDpJob().getRootDir().getAbsolutePath() +"/" + DP_DIR);
    }

    public String globalModelsFileName(DocPipeConfig cfg) {
        return cfg.getProjectDir() + "/" + DP_DIR + "/models.json";
    }

    public boolean hasDocPipeDir(Path dir) {
        return dir.resolve(DP_DIR).toFile().exists();
    }

    public File getDotPipeDir(File directory) {
        return new File(directory.getAbsolutePath() + "/"+DP_DIR);
    }

    public File getModelsJsonFrom(File directory) {
        return new File(directory.getAbsolutePath() + "/"+DP_DIR+"/models.json");
    }

    public File getDocumentsJsonFrom(File directory) {
        return new File(directory.getAbsolutePath() + "/"+DP_DIR+"/documents.json");
    }

    public File getContentHashesFrom(File directory) {
        return new File(directory.getAbsolutePath() + "/"+DP_DIR+"/content-hashes.properties");
    }

    public File getContentHashesFrom(ContentCreationTask cct) {
        return getContentHashesFrom(cct.getDpJob().getRootDir());
    }

    public String getOutputFilePath(ContentCreationTask cct) {
        return cct.getDpJob().getRootDir().getAbsoluteFile() + "/" + cct.getDpContentCreation().getOutputFile();
    }
}
