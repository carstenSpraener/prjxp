package de.spraener.prjxp.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.*;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import static org.assertj.core.api.Assertions.*;

class McpServerScanTest {
    @Test
    void mcpScanCannotStartCliPipelinesWhenHubIsDisabled() {
        var scan = McpServer.class.getAnnotation(ComponentScan.class);
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        for (var filter : scan.excludeFilters()) {
            if (filter.type() == FilterType.ANNOTATION)
                for (Class<?> type : filter.classes())
                    if (type == SpringBootApplication.class) scanner.addExcludeFilter(new AnnotationTypeFilter(SpringBootApplication.class));
        }
        assertThat(scanner.findCandidateComponents("de.spraener.prjxp").stream().map(bean -> bean.getBeanClassName()))
                .doesNotContain("de.spraener.prjxp.chuno.ChunkNorris", "de.spraener.prjxp.tibed.TiBedCliApp",
                        "de.spraener.prjxp.docpipe.DocPipeCliApp")
                .contains("de.spraener.prjxp.mcp.setup.SetupController");
    }
}
