package de.spraener.prjxp.chuno.docs.html;

import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import de.spraener.prjxp.chuno.docs.DocConversionAgent;
import de.spraener.prjxp.chuno.docs.model.CostEstimation;
import de.spraener.prjxp.chuno.docs.model.DocArtifakt;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;

@Component
@Log
public class Html2MDConversionAgent implements DocConversionAgent<String, String> {
    private final HtmlStructurePreprocessor preprocessor = new HtmlStructurePreprocessor();

    @Override
    public DocArtifaktType getSourceFormat() {
        return DocArtifaktType.HTML;
    }

    @Override
    public DocArtifaktType getTargetFormat() {
        return DocArtifaktType.MARK_DOWN;
    }

    @Override
    public double estimateCosts(DocArtifakt<String, ?> artifakt) {
        return CostEstimation.SIMPLE;
    }

    @Override
    public int estimateQuantity(DocArtifakt<String, ?> artifakt) {
        return 1;
    }

    @Override
    public void convert(DocArtifakt<String, ?> input) {
        log.fine("Converting HTML to Markdown");
        String cleanHtml = preprocessor.prepare(input.getData());

        MutableDataSet options = createOptions();

        String markdown = FlexmarkHtmlConverter.builder(options)
                .extensions(Collections.singletonList(TablesExtension.create()))
                .build()
                .convert(cleanHtml);

        markdown = markdown.replace("\u00A0", " ");
        DocArtifakt mdArt = new DocArtifakt<>(input).setData(markdown).setFormat(DocArtifaktType.MARK_DOWN).setId(input.getId() + ".md");
        input.addChild(mdArt);
    }

    private MutableDataSet createOptions() {
        MutableDataSet options = new MutableDataSet();

        options.set(TablesExtension.COLUMN_SPANS, false); // Vereinfacht die Struktur
        options.set(TablesExtension.APPEND_MISSING_COLUMNS, true);
        options.set(TablesExtension.DISCARD_EXTRA_COLUMNS, true);
        options.set(TablesExtension.HEADER_SEPARATOR_COLUMN_MATCH, true);
        options.set(TablesExtension.MIN_SEPARATOR_DASHES, 3);

        options.set(FlexmarkHtmlConverter.BR_AS_EXTRA_BLANK_LINES, false);
        options.set(FlexmarkHtmlConverter.BR_AS_PARA_BREAKS, false);

        options.set(TablesExtension.CLASS_NAME, "table");

        return options;
    }
}
