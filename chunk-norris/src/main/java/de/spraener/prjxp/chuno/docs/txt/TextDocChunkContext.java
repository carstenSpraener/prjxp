package de.spraener.prjxp.chuno.docs.txt;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@Data
@RequiredArgsConstructor
public class TextDocChunkContext {
    private final File file;
    private String titel = "";
    private String chapter = "";
    private String section = "";
    private String subSection = "";
    private int pageNumber = 0;
    private int lineNr;
    private Map<String,Object> scriptSate = new HashMap<>();

    public String toId() {
        return file.getAbsolutePath()+"."+chapter+"."+section+"."+subSection;
    }

}
