package de.spraener.prjxp.chuno.docs.poi;

import de.spraener.prjxp.chuno.docs.DocConversionAgent;
import de.spraener.prjxp.chuno.docs.model.CostEstimation;
import de.spraener.prjxp.chuno.docs.model.DocArtifakt;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;
import lombok.extern.java.Log;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToHTMLContentHandler;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;

@Component
@Log
public class Doc2HtmlConversionAgent implements DocConversionAgent<File,String> {
    @Override
    public DocArtifaktType getSourceFormat() {
        return DocArtifaktType.WORD_DOC;
    }

    @Override
    public DocArtifaktType getTargetFormat() {
        return DocArtifaktType.HTML;
    }

    @Override
    public double estimateCosts(DocArtifakt<File, ?> artifakt) {
        return CostEstimation.SIMPLE;
    }

    @Override
    public int estimateQuantity(DocArtifakt<File, ?> artifakt) {
        return 1;
    }

    @Override
    public void convert(DocArtifakt<File, ?> artifakt) {
        try {
            AutoDetectParser parser = new AutoDetectParser(); // Tika erkennt OLE2 vs OOXML
            ToHTMLContentHandler handler = new ToHTMLContentHandler();
            Metadata metadata = new Metadata();
            parser.parse(new FileInputStream(artifakt.getData()), handler, metadata, new ParseContext());
            DocArtifakt html = new DocArtifakt(artifakt)
                    .setId(artifakt.getId()+".html")
                    .setData(handler.toString())
                    .setFormat(DocArtifaktType.HTML)
                    ;

            PrintWriter pw = new PrintWriter(artifakt.getData().getAbsolutePath()+".html");
            pw.println(html.getData());
            pw.flush();
            pw.close();

            artifakt.addChild(html);
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }
}
