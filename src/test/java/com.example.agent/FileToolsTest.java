package com.example.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FileToolsTest {

    @TempDir
    Path tempDir;

    private Path workspace;
    private Path outside;
    private FileTools tools;

    @BeforeEach
    void setUp() throws IOException {
        workspace = Files.createDirectories(tempDir.resolve("workspace"));
        outside = Files.createDirectories(tempDir.resolve("outside"));
        tools = new FileTools(workspace);
    }

    // ---------- path traversal ----------

    @Test
    void writeRejectsParentTraversal() {
        String result = tools.writeFile("../escaped.txt", "nope");

        assertThat(result).contains("outside the allowed workspace");
        assertThat(outside.getParent().resolve("escaped.txt")).doesNotExist();
    }

    @Test
    void writeRejectsDeepTraversal() {
        String result = tools.writeFile("notes/../../escaped.txt", "nope");

        assertThat(result).contains("outside the allowed workspace");
    }

    @Test
    void readRejectsParentTraversal() throws IOException {
        Files.writeString(outside.resolve("secret.txt"), "top secret");

        String result = tools.readFile("../outside/secret.txt");

        assertThat(result).contains("outside the allowed workspace");
        assertThat(result).doesNotContain("top secret");
    }

    @Test
    void readRejectsAbsolutePathOutsideWorkspace() throws IOException {
        Files.writeString(outside.resolve("secret.txt"), "top secret");

        String result = tools.readFile(outside.resolve("secret.txt").toString());

        assertThat(result).contains("outside the allowed workspace");
        assertThat(result).doesNotContain("top secret");
    }

    // ---------- symlink escape ----------

    @Test
    void readRejectsSymlinkPointingOutsideWorkspace() throws IOException {
        Files.writeString(outside.resolve("secret.txt"), "top secret");
        try {
            Files.createSymbolicLink(workspace.resolve("link"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // filesystem does not support symlinks; nothing to verify
        }

        String result = tools.readFile("link/secret.txt");

        assertThat(result).contains("outside the allowed workspace");
        assertThat(result).doesNotContain("top secret");
    }

    @Test
    void writeRejectsSymlinkPointingOutsideWorkspace() throws IOException {
        try {
            Files.createSymbolicLink(workspace.resolve("link"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }

        String result = tools.writeFile("link/planted.txt", "malicious");

        assertThat(result).contains("outside the allowed workspace");
        assertThat(outside.resolve("planted.txt")).doesNotExist();
    }

    // ---------- missing file ----------

    @Test
    void readMissingFileReportsNotFound() {
        assertThat(tools.readFile("nope.txt")).contains("file not found");
    }

    @Test
    void readMissingFileInsideMissingDirectoryReportsNotFound() {
        assertThat(tools.readFile("no/such/dir/file.txt")).contains("file not found");
    }

    @Test
    void deleteMissingFileReportsNotFound() {
        assertThat(tools.deleteFile("nope.txt")).contains("file not found");
    }

    // ---------- overwrite semantics ----------

    @Test
    void writeOverwritesExistingFile() throws IOException {
        Path file = workspace.resolve("notes.txt");
        Files.writeString(file, "original content that is longer");

        String result = tools.writeFile("notes.txt", "new");

        assertThat(result).contains("Wrote 3 characters");
        assertThat(Files.readString(file)).isEqualTo("new");
    }

    @Test
    void writeCreatesParentDirectories() {
        String result = tools.writeFile("deep/nested/file.md", "hello");

        assertThat(result).contains("Wrote 5 characters");
        assertThat(workspace.resolve("deep/nested/file.md")).hasContent("hello");
    }

    // ---------- read size guard ----------

    @Test
    void readRefusesOversizedFile() throws IOException {
        Path big = workspace.resolve("big.txt");
        Files.write(big, new byte[(int) FileTools.MAX_READ_BYTES + 1]);

        String result = tools.readFile("big.txt");

        assertThat(result).contains("exceeds the");
        assertThat(result).contains("read limit");
    }

    @Test
    void readAllowsFileExactlyAtLimit() throws IOException {
        Path edge = workspace.resolve("edge.txt");
        Files.writeString(edge, "x".repeat((int) FileTools.MAX_READ_BYTES - 1));

        assertThat(tools.readFile("edge.txt")).doesNotContain("exceeds the");
    }

    // ---------- list caps ----------

    @Test
    void listReportsDirectoriesWithTrailingSlash() throws IOException {
        Files.createDirectories(workspace.resolve("emptydir"));
        tools.writeFile("file.txt", "x");

        String result = tools.listFiles();

        assertThat(result).contains("\"emptydir/\"");
        assertThat(result).contains("\"file.txt\"");
    }

    @Test
    void listReportsEmptyWorkspaceWhenOnlyHiddenFilesExist() throws IOException {
        Files.createDirectories(workspace.resolve(".git"));
        Files.writeString(workspace.resolve(".gitignore"), "target/");

        assertThat(tools.listFiles()).isEqualTo("(workspace is empty)");
    }

    @Test
    void listTruncatesAtMaxEntries() {
        for (int i = 0; i < FileTools.MAX_LIST_ENTRIES + 25; i++) {
            tools.writeFile("f" + i + ".txt", "x");
        }

        String result = tools.listFiles();

        assertThat(result).contains("truncated at " + FileTools.MAX_LIST_ENTRIES);
        assertThat(result.split("\"").length / 2).isEqualTo(FileTools.MAX_LIST_ENTRIES);
    }

    @Test
    void listRespectsMaxDepth() throws IOException {
        Path deep = workspace;
        for (int i = 0; i < FileTools.MAX_LIST_DEPTH + 3; i++) {
            deep = deep.resolve("d" + i);
        }
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("buried.txt"), "x");

        assertThat(tools.listFiles()).doesNotContain("buried.txt");
    }

    // ---------- delete + move ----------

    @Test
    void deleteRemovesFile() throws IOException {
        Path file = workspace.resolve("gone.txt");
        Files.writeString(file, "x");

        assertThat(tools.deleteFile("gone.txt")).contains("Deleted");
        assertThat(file).doesNotExist();
    }

    @Test
    void deleteRefusesDirectories() throws IOException {
        Files.createDirectories(workspace.resolve("keepdir"));

        assertThat(tools.deleteFile("keepdir")).contains("refusing to delete a directory");
        assertThat(workspace.resolve("keepdir")).isDirectory();
    }

    @Test
    void deleteRejectsTraversal() throws IOException {
        Files.writeString(outside.resolve("keep.txt"), "x");

        assertThat(tools.deleteFile("../outside/keep.txt")).contains("outside the allowed workspace");
        assertThat(outside.resolve("keep.txt")).exists();
    }

    @Test
    void moveRenamesFile() throws IOException {
        tools.writeFile("draft.txt", "content");

        assertThat(tools.moveFile("draft.txt", "archive/final.txt")).contains("Moved");
        assertThat(workspace.resolve("archive/final.txt")).hasContent("content");
        assertThat(workspace.resolve("draft.txt")).doesNotExist();
    }

    @Test
    void moveRefusesToClobberExistingDestination() {
        tools.writeFile("a.txt", "a");
        tools.writeFile("b.txt", "b");

        assertThat(tools.moveFile("a.txt", "b.txt")).contains("destination already exists");
        assertThat(workspace.resolve("b.txt")).hasContent("b");
    }

    @Test
    void moveRejectsTraversal() throws IOException {
        tools.writeFile("innocent.txt", "x");

        assertThat(tools.moveFile("innocent.txt", "../outside/stolen.txt"))
                .contains("outside the allowed workspace");
        assertThat(outside.resolve("stolen.txt")).doesNotExist();
    }
}
