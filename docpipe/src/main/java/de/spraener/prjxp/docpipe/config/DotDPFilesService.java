package de.spraener.prjxp.docpipe.config;

import de.spraener.prjxp.docpipe.DocPipeConfig;
import de.spraener.prjxp.docpipe.content.ContentCreationTask;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;

@Component
/**
 * Service for managing paths and files related to the {@code .dp} configuration directories.
 * <p>
 * This class provides utility methods to locate the documentation pipeline directory,
 * configuration files (like {@code documents.json} and {@code models.json}), and content hash properties.
 * </p>
 */
public class DotDPFilesService {
    private static final String HASH_FILE_PATH = "content-hashes.properties";
    public static final String DP_DIR= ".dp";

    /**
     * Resolves the {@code .dp} configuration directory for a given content creation task.
     *
     * @param cct the content creation task
     * @return the {@link File} object representing the configuration directory
     */
    public File dotPipeDir(ContentCreationTask cct) {
        return new File(cct.getDpJob().getRootDir().getAbsolutePath() +"/" + DP_DIR);
    }

    /**
     * Returns the path to the global models configuration file.
     *
     * @param cfg the documentation pipeline configuration
     * @return the absolute path to the {@code models.json} file
     */
    public String globalModelsFileName(DocPipeConfig cfg) {
        return cfg.getProjectDir() + "/" + DP_DIR + "/models.json";
    }

    /**
     * Checks if a given directory contains a {@code .dp} configuration folder.
     *
     * @param dir the directory to check
     * @return true if the {@code .dp} directory exists, false otherwise
     */
    public boolean hasDocPipeDir(Path dir) {
        return dir.resolve(DP_DIR).toFile().exists();
    }

    /**
     * Resolves the {@code .dp} configuration directory for a given file.
     *
     * @param directory the parent directory
     * @return the {@link File} object representing the configuration directory
     */
    public File getDotPipeDir(File directory) {
        return new File(directory.getAbsolutePath() + "/"+DP_DIR);
    }

    /**
     * Resolves the {@code models.json} file within a given directory's {@code .dp} folder.
     *
     * @param directory the parent directory
     * @return the {@link File} object representing the models configuration file
     */
    public File getModelsJsonFrom(File directory) {
        return new File(directory.getAbsolutePath() + "/"+DP_DIR+"/models.json");
    }

    /**
     * Resolves the {@code documents.json} file within a given directory's {@code .dp} folder.
     *
     * @param directory the parent directory
     * @return the {@link File} object representing the documents configuration file
     */
    public File getDocumentsJsonFrom(File directory) {
        return new File(directory.getAbsolutePath() + "/"+DP_DIR+"/documents.json");
    }

    /**
     * Resolves the content hashes properties file within a given directory's {@code .dp} folder.
     *
     * @param directory the parent directory
     * @return the {@link File} object representing the content hashes file
     */
    public File getContentHashesFrom(File directory) {
        return new File(directory.getAbsolutePath() + "/"+DP_DIR+"/content-hashes.properties");
    }

    /**
     * Resolves the content hashes properties file for a given content creation task.
     *
     * @param cct the content creation task
     * @return the {@link File} object representing the content hashes file
     */
    public File getContentHashesFrom(ContentCreationTask cct) {
        return getContentHashesFrom(cct.getDpJob().getRootDir());
    }

    /**
     * Resolves the absolute output file path for a given content creation task.
     *
     * @param cct the content creation task containing the output file name
     * @return the absolute path to the output file as a string
     */
    public String getOutputFilePath(ContentCreationTask cct) {
        return cct.getDpJob().getRootDir().getAbsoluteFile() + "/" + cct.getDpContentCreation().getOutputFile();
    }
}
