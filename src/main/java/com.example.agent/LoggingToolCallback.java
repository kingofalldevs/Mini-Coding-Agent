package com.example.agent;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Wraps a {@link ToolCallback} so that every invocation is recorded in the
 * {@link ActivityLog} before its result is handed back to the model.
 *
 * <p>Wrapping is preferable to editing {@link FileTools} because the tool classes
 * stay free of logging concerns and every current and future tool is covered by
 * the same code path. Wrapping is preferable to {@code doOnNext} on the stream
 * because tool calls produce no content deltas, so the stream never shows them.
 *
 * <p>The {@code conversationId} is captured per request rather than read from
 * {@link ToolContext}: the context is not guaranteed to carry it, and a wrong id
 * in an audit log is worse than no id.
 */
class LoggingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ActivityLog log;
    private final String conversationId;

    LoggingToolCallback(ToolCallback delegate, ActivityLog log, String conversationId) {
        this.delegate = delegate;
        this.log = log;
        this.conversationId = conversationId;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String name = delegate.getToolDefinition().name();
        String result;
        try {
            result = (toolContext == null)
                    ? delegate.call(toolInput)
                    : delegate.call(toolInput, toolContext);
        } catch (RuntimeException e) {
            // Record the failure, then rethrow: surfacing it to the model is the
            // tool layer's job, but losing it from the log would be a blind spot.
            log.toolCall(conversationId, name, toolInput, "Error: " + e.getMessage());
            throw e;
        }
        log.toolCall(conversationId, name, toolInput, result);
        return result;
    }
}
