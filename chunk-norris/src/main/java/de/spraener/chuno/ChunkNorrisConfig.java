package de.spraener.chuno;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;

@Component
@Data
public class ChunkNorrisConfig {
    private String rootDir;
    private PrintWriter output;
    private String configFile;
    private String whiteList;
}
