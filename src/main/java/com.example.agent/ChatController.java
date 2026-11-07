package com.example.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatClient chatClient;
    private final ToolCallbackProvider tools;
    private final ActivityLog activityLog;

    public ChatController(ChatClient chatClient, ToolCallbackProvider tools, ActivityLog activityLog) {
        this.chatClient = chatClient;
        this.tools = tools;
        this.activityLog = activityLog;
    }

    /** Request body for /api/chat. conversationId may be omitted on the first turn. */
    public record ChatRequest(String message, String conversationId) {}

    /**
     * Streams the model's reply as Server-Sent Events.
     *
     * <p>Each event's data is a JSON object: {"t":"..."} for content, {"nl":n} for
     * a run of {@code n} line breaks, or {"cid":"..."} for the conversation id.
     *
     * <p>Three encoding hazards are handled here:
     * <ul>
     *   <li>Spring's SSE encoder rewrites newlines inside a value into additional
     *       "data:" lines, so "\n" is sent as JSON {@code \n} instead.</li>
     *   <li>The same encoder trims whitespace-only values, so a chunk that is just
     *       a space -- which models emit constantly between words -- would vanish,
     *       collapsing the reply into unspaced text. JSON string encoding keeps it.</li>
     *   <li>Chunks that are only line breaks are sent as {"nl":n}, which also lets
     *       the client tell wrapper newlines (formatting) apart from hard breaks.</li>
     * </ul>
     *
     * <p>The turn and every tool call it triggers are appended to the activity log.
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestBody ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            return Flux.just(jsonMessage("Error: message must not be blank."));
        }

        String conversationId = (request.conversationId() == null || request.conversationId().isBlank())
                ? UUID.randomUUID().toString()
                : request.conversationId();

        // Per-request client so tool calls are logged against the right conversation.
        ChatClient client = MiniCodingAgentApplication.loggedClient(chatClient, tools, activityLog, conversationId);

        long started = System.currentTimeMillis();

        Flux<String> content = client.prompt()
                .user(request.message())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content()
                .map(ChatController::encodeChunk)
                // Log on completion, error, or cancellation -- whichever happens first.
                .doFinally(signal -> activityLog.turn(
                        conversationId,
                        request.message(),
                        System.currentTimeMillis() - started,
                        signal != reactor.core.publisher.SignalType.ON_COMPLETE));

        // The conversation id goes out first so the client can reuse it later.
        return Flux.concat(
                Flux.just("{\"cid\":" + quote(conversationId) + "}"),
                content
        );
    }

    /** Wraps a content chunk in a JSON envelope, preserving all whitespace. */
    private static String encodeChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        if (chunk.isBlank() && chunk.chars().allMatch(c -> c == '\n' || c == '\r')) {
            int breaks = 0;
            for (int i = 0; i < chunk.length(); i++) {
                char c = chunk.charAt(i);
                if (c == '\n' || (c == '\r' && (i + 1 >= chunk.length() || chunk.charAt(i + 1) != '\n'))) {
                    breaks++;
                }
            }
            if (breaks > 0) {
                return "{\"nl\":" + breaks + "}";
            }
        }
        return "{\"t\":" + quote(chunk) + "}";
    }

    private static String jsonMessage(String text) {
        return "{\"t\":" + quote(text) + "}";
    }

    /** Minimal JSON string escaping; avoids pulling in a mapper for one field. */
    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
