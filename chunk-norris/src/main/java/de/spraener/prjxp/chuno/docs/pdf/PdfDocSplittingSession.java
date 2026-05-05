package de.spraener.prjxp.chuno.docs.pdf;

import lombok.Data;

@Data
public class PdfDocSplittingSession {
    String documentPath;
    String chapter;
    int page;
    int line;
    int chunkCount=0;

    public int incrementChunkCount() {
        int r = this.chunkCount;
        this.chunkCount++;
        return r;
    }
}
