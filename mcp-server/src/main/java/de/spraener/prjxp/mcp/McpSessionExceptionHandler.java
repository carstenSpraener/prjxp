package de.spraener.prjxp.mcp;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Löst das "Session not found"-Problem beim MCP Streamable HTTP Transport.
 *
 * Problem: Nach einem Server-Neustart sind alle In-Memory-Sessions verloren.
 * Der MCP-Client (z.B. CoPilot in IntelliJ) sendet weiterhin die alte Session-ID,
 * der Server wirft eine Exception.
 *
 * Lösung: Ein Filter merkt sich alle bekannten Session-IDs. Kommt eine unbekannte
 * Session-ID herein, wird sie aus dem Request entfernt. Der MCP-Transport behandelt
 * den Request dann als neuen Request und legt eine neue Session an.
 */
@Configuration
public class McpSessionExceptionHandler {

    // Bekannte Session-IDs, die der Server selbst vergeben hat
    static final Set<String> knownSessions = ConcurrentHashMap.newKeySet();

    @Bean
    public FilterRegistrationBean<McpSessionFilter> mcpSessionFilter() {
        FilterRegistrationBean<McpSessionFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new McpSessionFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Integer.MIN_VALUE + 1);
        return registration;
    }

    public static class McpSessionFilter implements Filter {

        private static final String MCP_SESSION_HEADER = "mcp-session-id";

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {

            if (!(request instanceof HttpServletRequest httpReq)) {
                chain.doFilter(request, response);
                return;
            }

            HttpServletResponse httpResp = (HttpServletResponse) response;
            String sessionId = httpReq.getHeader(MCP_SESSION_HEADER);

            // Unbekannte Session-ID → sofort 404 zurückgeben (MCP-Spec: Client muss re-initialisieren)
            if (sessionId != null && !knownSessions.contains(sessionId)) {
                httpResp.sendError(HttpServletResponse.SC_NOT_FOUND, "Session not found: " + sessionId);
                return;
            }

            try {
                chain.doFilter(httpReq, response);
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && (msg.startsWith("Session not found:") || msg.equals("Session ID missing"))) {
                    httpResp.sendError(HttpServletResponse.SC_NOT_FOUND, msg);
                } else {
                    throw e;
                }
            }

            // Neue Session-ID aus der Response merken
            String newSessionId = httpResp.getHeader(MCP_SESSION_HEADER);
            if (newSessionId != null) {
                knownSessions.add(newSessionId);
            }
        }
    }

}
