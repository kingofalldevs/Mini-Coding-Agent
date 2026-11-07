package com.example.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ActivityLog}.
 *
 * <p>The log is the audit trail, so the properties worth pinning down are: every
 * event reaches both sinks, the JSONL stays one parseable object per line, and
 * anything the user or model writes is escaped rather than being able to forge,
 * merge or break a record.
 */
class ActivityLogTest {

    @TempDir
    Path workspace;

    private ActivityLog log;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        log = new ActivityLog(workspace);
    }

    @Test
    void createsBothSinksUnderDotDirectory() {
        assertThat(log.file()).isRegularFile();
        assertThat(log.transcriptFile()).isRegularFile();
        assertThat(log.file().getParent().getFileName().toString()).isEqualTo(".agent");
    }

    @Test
    void turnAppendsOneParseableJsonLine() throws IOException {
        log.turn("repl", "hello", 12L, false);

        List<String> lines = Files.readAllLines(log.file());
        assertThat(lines).hasSize(1);

        JsonNode node = mapper.readTree(lines.get(0));
        assertThat(node.get("kind").asText()).isEqualTo("turn");
        assertThat(node.get("conversation").asText()).isEqualTo("repl");
        assertThat(node.get("ok").asText()).isEqualTo("true");
    }

    @Test
    void toolCallRecordsNameArgsAndOutcome() throws IOException {
        log.toolCall("repl", "writeFile", "{\"path\":\"a.txt\"}", "Wrote 3 characters");

        JsonNode node = mapper.readTree(Files.readString(log.file()).strip());
        assertThat(node.get("kind").asText()).isEqualTo("tool");
        assertThat(node.get("tool").asText()).isEqualTo("writeFile");
        assertThat(node.get("ok").asText()).isEqualTo("true");
        assertThat(node.get("summary").asText()).contains("Wrote 3 characters");
    }

    @Test
    void resultStartingWithErrorIsMarkedNotOk() throws IOException {
        log.toolCall("repl", "readFile", "{}", "Error: file not found: nope.txt");

        assertThat(mapper.readTree(Files.readString(log.file()).strip()).get("ok").asText())
                .isEqualTo("false");
    }

    @Test
    void newlinesAndQuotesCannotBreakTheRecord() throws IOException {
        log.turn("repl", "line one\nline \"two\"\ttabbed", 5L, false);

        List<String> lines = Files.readAllLines(log.file());
        assertThat(lines).hasSize(1);
        assertThat(mapper.readTree(lines.get(0)).get("message").asText())
                .isEqualTo("line one line \"two\" tabbed");
    }

    @Test
    void transcriptIsMarkdownWithOneSectionPerEvent() throws IOException {
        log.turn("repl", "hi", 1L, false);
        log.toolCall("repl", "listFiles", "{}", "a.txt");

        String md = Files.readString(log.transcriptFile());
        assertThat(md).contains("# Agent activity transcript");
        assertThat(md.split("## ").length - 1).isEqualTo(2);
    }

    @Test
    void longFieldIsTruncated() throws IOException {
        log.turn("repl", "x".repeat(ActivityLog.MAX_FIELD_CHARS * 2), 1L, false);

        String content = Files.readString(log.file());
        assertThat(content).contains("…");
        assertThat(content.length()).isLessThan(ActivityLog.MAX_FIELD_CHARS * 3);
    }

    @Test
    void tailReturnsMostRecentRecords() {
        for (int i = 0; i < 5; i++) {
            log.turn("repl", "message " + i, 1L, false);
        }

        String tail = log.tail(2);

        assertThat(tail.lines().count()).isEqualTo(2);
        assertThat(tail).contains("message 4").doesNotContain("message 0");
    }

    @Test
    void tailOnEmptyLogIsGentle() {
        assertThat(log.tail(5)).isEqualTo("(no activity recorded yet)");
    }

    @Test
    void existingTranscriptIsAppendedNotRewritten() throws IOException {
        log.turn("repl", "first", 1L, false);
        long firstSize = Files.size(log.transcriptFile());

        log.turn("repl", "second", 1L, false);

        assertThat(Files.size(log.transcriptFile())).isGreaterThan(firstSize);
        assertThat(Files.readString(log.transcriptFile())).contains("first").contains("second");
    }
}
