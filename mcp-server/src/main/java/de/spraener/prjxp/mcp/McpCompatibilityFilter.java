package de.spraener.prjxp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.lang.annotation.Annotation;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Component
public class McpCompatibilityFilter extends OncePerRequestFilter {

    private static final String MCP_PATH = "/mcp";
    private static final String LEGACY_PROTOCOL_VERSION = "2025-06-18";

    private final ObjectMapper objectMapper;
    private final ListableBeanFactory beanFactory;

    public McpCompatibilityFilter(ObjectMapper objectMapper, ListableBeanFactory beanFactory) {
        this.objectMapper = objectMapper;
        this.beanFactory = beanFactory;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !request.getRequestURI().endsWith(MCP_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        byte[] requestBody = StreamUtils.copyToByteArray(request.getInputStream());
        JsonNode message = parseJson(requestBody);

        if (message == null || !message.isObject()) {
            filterChain.doFilter(new BufferedRequestWrapper(request, requestBody), response);
            return;
        }

        String method = message.path("method").asText();
        if ("server/discover".equals(method)) {
            writeDiscoverResult(response, message.path("id"));
            return;
        }

        if ("notifications/initialized".equals(method)) {
            response.setStatus(HttpServletResponse.SC_ACCEPTED);
            return;
        }

        if ("initialize".equals(method)) {
            JsonNode capabilities = message.path("params").path("capabilities");
            if (capabilities.isObject()) {
                ((ObjectNode) capabilities).remove("elicitation");
                requestBody = objectMapper.writeValueAsBytes(message);
            }
        }

        filterChain.doFilter(new BufferedRequestWrapper(request, requestBody), response);
    }

    private JsonNode parseJson(byte[] body) throws IOException {
        if (body.length == 0) {
            return null;
        }
        return objectMapper.readTree(body);
    }

    private void writeDiscoverResult(HttpServletResponse response, JsonNode idNode) throws IOException {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("jsonrpc", "2.0");
        if (idNode == null || idNode.isMissingNode() || idNode.isNull()) {
            result.putNull("id");
        } else {
            result.set("id", idNode);
        }

        ObjectNode discoverResult = result.putObject("result");
        discoverResult.putArray("supportedVersions").add(LEGACY_PROTOCOL_VERSION);
        ObjectNode capabilities = discoverResult.putObject("capabilities");

        if (hasAnnotatedMethod("McpTool")) {
            capabilities.putObject("tools").put("listChanged", false);
        }
        if (hasAnnotatedMethod("McpPrompt")) {
            capabilities.putObject("prompts").put("listChanged", false);
        }
        if (hasAnnotatedMethod("McpResource")) {
            capabilities.putObject("resources")
                    .put("listChanged", false)
                    .put("subscribe", false);
        }
        if (hasAnnotatedMethod("McpComplete")) {
            capabilities.putObject("completions");
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }

    private boolean hasAnnotatedMethod(String annotationSimpleName) {
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> beanType = beanFactory.getType(beanName, false);
            if (beanType == null) {
                continue;
            }
            for (var method : beanType.getMethods()) {
                for (Annotation annotation : method.getDeclaredAnnotations()) {
                    if (annotation.annotationType().getSimpleName().equals(annotationSimpleName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static class BufferedRequestWrapper extends HttpServletRequestWrapper {
        private final byte[] body;

        BufferedRequestWrapper(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return inputStream.read();
                }

                @Override
                public boolean isFinished() {
                    return inputStream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException("Async read is not supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
