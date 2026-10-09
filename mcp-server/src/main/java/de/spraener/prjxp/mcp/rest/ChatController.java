package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.mcp.rest.dto.ChatRequest;
import de.spraener.prjxp.mcp.rest.dto.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;

    @PostMapping("/send")
    public ResponseEntity<ChatResponse> sendMessage(@RequestBody ChatRequest request) {
        ChatResponse response = chatService.send(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/send", produces = "text/event-stream", headers = "Accept=text/event-stream")
    public SseEmitter sendMessageStreaming(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L); // 2 min timeout
        chatService.sendStreaming(request, emitter);
        return emitter;
    }

    @PostMapping("/projects")
    public ResponseEntity<List<String>> getAvailableProjects() {
        return ResponseEntity.ok(chatService.getAvailableProjects());
    }

    @PostMapping("/tools")
    public ResponseEntity<List<String>> getAvailableTools() {
        return ResponseEntity.ok(chatService.getAvailableTools());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<String> handleRuntimeError(RuntimeException ex) {
        log.error("Chat error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ex.getMessage());
    }
}
