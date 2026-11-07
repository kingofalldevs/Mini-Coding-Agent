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
 * <p>Every record goes to three sinks from one call site, so they cannot drift:
 * <ul>
 *   <li><b>the terminal</b> -- a couple of human-readable lines per event, so you
 *       can watch the agent work in the console while it runs;</li>
 *   <li><b>{@code .agent/activity.md}</b> -- a Markdown transcript to read and diff;</li>
 *   <li><b>{@code .agent/activity.log}</b> -- JSONL, one object per line, for
 *       {@code jq}/{@code grep} and for {@link #tail(int)}.</li>
 * </ul>
 *
 * <p>Two things get logged here: conversation turns (what the user asked, how long
 * the reply took) and tool calls (name, arguments, outcome).
 *
 * <p>This is deliberately <em>not</em> the same file as the agent's own notes.
 * Notes are model-authored prose and may be wrong; this log is written by code
 * and is the authoritative audit trail. Backfilling the gap between activity and
 * attribution is the whole point, so no tool targets a path under {@code .agent/}
 * and the model cannot rewrite its own history even in principle.
 *
 * <p>Both files live under {@code .agent/} because {@link FileTools#listFiles()}
 * hides any path segment starting with a dot, so the log never clutters a
 * listing. That directory is git-ignored (see {@code .gitignore}).
 */
public class ActivityLog {

    /** Arguments/results longer than this are truncated, to bound file growth. */
    static final int MAX_FIELD_CHARS = 600;

    private final Path dir;
    private final Path jsonlFile;
    private final Path transcriptFile;

    public ActivityLog(Path workspace) throws IOException {
        // Real path so the log files are written inside the same sandbox the tools use.
        Path root = workspace.toRealPath();
        this.dir = root.resolve(".agent");
        Files.createDirectories(dir);
        this.jsonlFile = dir.resolve("activity.log");
        this.transcriptFile = dir.resolve("activity.md");

        // Header only on creation; an existing transcript is appended to, never rewritten.
        if (!Files.exists(transcriptFile)) {
            appendTo(transcriptFile, """
                    # Agent activity transcript

                    Machine-written by `ActivityLog`, one section per event, newest last.
                    The sibling `activity.log` holds the same events as JSONL for `jq`/`grep`.

                    """);
        }

        System.out.println(">>> Agent activity log:  " + jsonlFile);
        System.out.println(">>> Agent transcript:    " + transcriptFile);
    }

    /** JSONL path. */
    public Path file() {
        return jsonlFile;
    }

    /** Markdown transcript path. */
    public Path transcriptFile() {
        return transcriptFile;
    }

    /** One user turn plus the assistant's latency. */
    public void turn(String conversationId, String userMessage, long elapsedMs, boolean failed) {
        String ts = Instant.now().toString();
        record(new String[]{
                "ts", ts,
                "kind", "turn",
                "conversation", conversationId,
                "message", truncate(oneLine(userMessage)),
                "elapsedMs", String.valueOf(elapsedMs),
                "ok", String.valueOf(!failed)
        }, String.format("%s  TURN   [%s]  %d ms  ok=%s%n    %s%n",
                ts, conversationId, elapsedMs, !failed, truncate(oneLine(userMessage))));
    }

    /** One tool invocation: what was called, with what arguments, and how it went. */
    public void toolCall(String conversationId, String tool, String arguments, String result) {
        String ts = Instant.now().toString();
        boolean ok = result == null || !result.startsWith("Error");
        String summary = truncate(oneLine(result == null ? "" : result));
        String args = truncate(oneLine(arguments));
        record(new String[]{
                "ts", ts,
                "kind", "tool",
                "conversation", conversationId,
                "tool", tool,
                "args", args,
                "summary", summary,
                "ok", String.valueOf(ok)
        }, String.format("%s  TOOL   %s  ok=%s%n    args: %s%n    out:  %s%n",
                ts, tool, ok, args, summary));
    }

    /**
     * Writes one event to all three sinks. Failures are swallowed: logging must
     * never break a conversation, so a bad disk degrades to a stderr warning.
     */
    private synchronized void record(String[] keyValues, String terminalLine) {
        // 1. Terminal, first, so it appears live even if a file write is slow.
        System.out.print(terminalLine);

        // 2. Markdown transcript.
        StringBuilder md = new StringBuilder(256);
        md.append("## ").append(keyValue(keyValues, "ts")).append("  ")
                .append(keyValue(keyValues, "kind")).append("\n\n");
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            String key = keyValues[i];
            if (key.equals("ts") || key.equals("kind")) {
                continue;
            }
            md.append("- **").append(key).append("**: `")
                    .append(oneLine(keyValues[i + 1]).replace("`", "'"))
                    .append("`\n");
        }
        md.append('\n');
        appendTo(transcriptFile, md.toString());

        // 3. JSONL.
        appendTo(jsonlFile, jsonLine(keyValues));
    }

    private static String keyValue(String[] keyValues, String wanted) {
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i].equals(wanted)) {
                return keyValues[i + 1];
            }
        }
        return "";
    }

    private static String jsonLine(String[] keyValues) {
        StringBuilder sb = new StringBuilder(160).append('{');
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            quote(sb, keyValues[i]);
            sb.append(':');
            quote(sb, keyValues[i + 1]);
        }
        return sb.append('}').append(System.lineSeparator()).toString();
    }

    private static void appendTo(Path target, String text) {
        try {
            Files.writeString(target, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("WARN: could not append to " + target + ": " + e.getMessage());
        }
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\r', ' ').replace('\n', ' ');
    }

    private static String truncate(String singleLine) {
        return singleLine.length() > MAX_FIELD_CHARS
                ? singleLine.substring(0, MAX_FIELD_CHARS) + "…"
                : singleLine;
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
     * Reads back the most recent {@code limit} records, oldest first, for the
     * REPL's {@code :log} command. Returns a plain-text rendering of the JSONL.
     */
    public String tail(int limit) {
        if (!Files.isRegularFile(jsonlFile)) {
            return "(no activity recorded yet)";
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(jsonlFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error reading activity log: " + e.getMessage();
        }
        int from = Math.max(0, lines.size() - Math.max(1, limit));
        return String.join("\n", lines.subList(from, lines.size()));
    }
}
