package com.ittai.debugbridge.tools;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;

public record ToolSpec(
    String name,
    String description,
    McpSchema.JsonSchema inputSchema,
    Handler handler
) {
    @FunctionalInterface
    public interface Handler {
        Map<String, Object> handle(Map<String, Object> args) throws Exception;
    }
}
