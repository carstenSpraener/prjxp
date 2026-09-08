package de.spraener.prjxp.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VectorSearchController.class)
class VectorSearchControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    VectorSearchService service;

    @Test
    void missingQueryReturns400() throws Exception {
        mvc.perform(get("/prjxp/tools/vectorSearch"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void blankQueryReturns400() throws Exception {
        mvc.perform(get("/prjxp/tools/vectorSearch").param("query", "   "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void validCallReturns200WithHits() throws Exception {
        SearchHit hit = new SearchHit("chunk-1", 0.87, "src/A.java", 10, 20, "snippet text", "vector", Map.of());
        when(service.search(anyString(), anyString(), any(), anyInt())).thenReturn(List.of(hit));

        mvc.perform(get("/prjxp/tools/vectorSearch").param("query", "How does chunking work?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].chunkId").value("chunk-1"))
                .andExpect(jsonPath("$[0].score").value(0.87))
                .andExpect(jsonPath("$[0].file").value("src/A.java"))
                .andExpect(jsonPath("$[0].lineFrom").value(10))
                .andExpect(jsonPath("$[0].lineTo").value(20))
                .andExpect(jsonPath("$[0].snippet").value("snippet text"))
                .andExpect(jsonPath("$[0].source").value("vector"));
    }

    @Test
    void limitAboveMaxIsClampedTo100() throws Exception {
        when(service.search(anyString(), anyString(), any(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/prjxp/tools/vectorSearch").param("query", "x").param("limit", "500"))
                .andExpect(status().isOk());

        verify(service).search(eq("x"), eq("default"), isNull(), eq(100));
    }

    @Test
    void defaultLimitIs10() throws Exception {
        when(service.search(anyString(), anyString(), any(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/prjxp/tools/vectorSearch").param("query", "x"))
                .andExpect(status().isOk());

        verify(service).search(eq("x"), eq("default"), isNull(), eq(10));
    }

    @Test
    void parametersArePassedThrough() throws Exception {
        when(service.search(anyString(), anyString(), anyString(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/prjxp/tools/vectorSearch")
                .param("query", "How does chunking work?")
                .param("project", "myproj")
                .param("language", "java")
                .param("limit", "5"))
                .andExpect(status().isOk());

        verify(service).search("How does chunking work?", "myproj", "java", 5);
    }
}
