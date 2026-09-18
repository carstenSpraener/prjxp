package de.spraener.prjxp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class McpCompatibilityFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ListableBeanFactory beanFactory = mock(ListableBeanFactory.class);
    private final McpCompatibilityFilter filter = new McpCompatibilityFilter(objectMapper, beanFactory);

    @Test
    void initializeRequestRemovesElicitationCapabilities() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setContentType("application/json");
        request.setContent("""
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "method":"initialize",
                  "params":{
                    "protocolVersion":"2025-06-18",
                    "capabilities":{
                      "elicitation":{"url":"x","form":{}},
                      "sampling":{}
                    },
                    "clientInfo":{"name":"kiro","version":"1.0"}
                  }
                }
                """.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<ServletRequest> requestCaptor = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(requestCaptor.capture(), org.mockito.ArgumentMatchers.any(ServletResponse.class));

        byte[] forwardedBody = requestCaptor.getValue().getInputStream().readAllBytes();
        JsonNode forwarded = objectMapper.readTree(forwardedBody);
        assertThat(forwarded.path("params").path("capabilities").has("elicitation")).isFalse();
        assertThat(forwarded.path("method").asText()).isEqualTo("initialize");
    }

    @Test
    void serverDiscoverReturnsMethodNotFoundWithoutCallingChain() throws Exception {
        ToolBean toolBean = new ToolBean();
        org.mockito.Mockito.when(beanFactory.getBeanDefinitionNames()).thenReturn(new String[]{"toolBean"});
        Class<?> toolBeanType = toolBean.getClass();
        org.mockito.Mockito.doReturn(toolBeanType).when(beanFactory).getType("toolBean", false);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setContentType("application/json");
        request.setContent("""
                {"jsonrpc":"2.0","id":9,"method":"server/discover","params":{}}
                """.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("result").path("supportedVersions").isArray()).isTrue();
        assertThat(body.path("result").path("supportedVersions").get(0).asText()).isEqualTo("2025-06-18");
        assertThat(body.path("result").path("capabilities").path("tools").path("listChanged").asBoolean()).isFalse();
    }

    @Test
    void initializedNotificationIsForwarded() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setContentType("application/json");
        request.setContent("""
                {"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
                """.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void invalidJsonIsForwardedWithoutFilterCrash() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setContentType("application/json");
        request.setContent("{jsonrpc:2.0}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    static class ToolBean {
        @McpTool(name = "x", description = "x")
        public String sampleTool(String input) {
            return input;
        }
    }
}
