package com.example.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

/**
 * Append-only, machine-written record of everything the agent does.
 *
 * <p>Two things get logged here, both as one JSON object per line (JSONL):
 * <ul>
 *   <li>conversation turns -- what the user asked and how long the reply took;</li>
 *   <li>tool calls -- name, arguments, outcome, duration.</li>
 * </ul>
 *
 * <p>This is deliberately <em>not</em> the same file as the agent's own notes.
 * Notes are model-authored prose and may be wrong; this log is written by code
 * and is the authoritative audit trail. Keeping them apart means a confused
 * model cannot overwrite its own history.
 *
 * <p>The log lives under {@code .agent/} on purpose: {@link FileTools#listFiles()}
 * hides any path segment starting with a dot, so the log never clutters a
 * listing, and the model has no tool that targets it by accident.
 */
public class ActivityLog {

    /** Lines longer than this have their arguments truncated, to bound file growth. */
    static final int MAX_FIELD_CHARS = 600;

    private final Path file;

    public ActivityLog(Path workspace) throws IOException {
        // Real path so the log file is written inside the same sandbox the tools use.
        Path root = workspace.toRealPath();
        Path dir = root.resolve(".agent");
        Files.createDirectories(dir);
        this.file = dir.resolve("activity.log");
        System.out.println(">>> Agent activity log: " + this.file);
    }

    public Path file() {
        return file;
    }

    /** One user turn plus the assistant's latency. */
    public void turn(String conversationId, String userMessage, long elapsedMs, boolean failed) {
        write("turn",
                "conversation", conversationId,
                "message", userMessage,
                "elapsedMs", String.valueOf(elapsedMs),
                "ok", String.valueOf(!failed));
    }

    /** One tool invocation: what was called, with what arguments, and how it went. */
    public void toolCall(String conversationId, String tool, String arguments, String result) {
        boolean ok = result == null || !result.startsWith("Error");
        write("tool",
                "conversation", conversationId,
                "tool", tool,
                "args", arguments,
                "summary", summarise(result),
                "ok", String.valueOf(ok));
    }

    /**
     * Appends one record. Failures are swallowed: logging must never break a
     * conversation, so a bad disk degrades to a stderr warning, not an exception.
     */
    private synchronized void write(String kind, String... keyValues) {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        field(sb, "ts", Instant.now().toString());
        sb.append(',');
        field(sb, "kind", kind);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            sb.append(',');
            field(sb, keyValues[i], keyValues[i + 1]);
        }
        sb.append('}').append(System.lineSeparator());

        try {
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("WARN: could not append to activity log: " + e.getMessage());
        }
    }

    /** Result bodies can be entire files; the log only needs enough to identify the call. */
    private static String summarise(String result) {
        if (result == null) {
            return "";
        }
        String oneLine = result.replace('\r', ' ').replace('\n', ' ');
        return oneLine.length() > MAX_FIELD_CHARS
                ? oneLine.substring(0, MAX_FIELD_CHARS) + "…"
                : oneLine;
    }

    private static void field(StringBuilder sb, String key, String value) {
        quote(sb, key);
        sb.append(':');
        quote(sb, value);
    }

    /** Minimal JSON string escaping, matching ChatController's approach. */
    private static void quote(StringBuilder sb, String s) {
        if (s == null) {
            sb.append("null");
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
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
    }

    /**
     * Reads back the most recent {@code limit} records, oldest first, for display
     * or for the agent to reason about. Returns a plain-text rendering.
     */
    public String tail(int limit) {
        if (!Files.isRegularFile(file)) {
            return "(no activity recorded yet)";
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error reading activity log: " + e.getMessage();
        }
        int from = Math.max(0, lines.size() - Math.max(1, limit));
        return String.join("\n", lines.subList(from, lines.size()));
    }
}
