package com.example.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for {@link FileTools}.
 *
 * <p>The property under test is containment: no path the model supplies may read,
 * write, move or delete anything outside the workspace, whether it escapes with
 * {@code ..}, with an absolute path, or through a symlink. The size and count
 * guards are checked too, since they protect the context window.
 */
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
        assertThat(outside.resolve("escaped.txt")).doesNotExist();
    }

    @Test
    void writeRejectsDeepTraversal() {
        assertThat(tools.writeFile("notes/../../escaped.txt", "nope"))
                .contains("outside the allowed workspace");
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
        if (!createSymlink(workspace.resolve("link"), outside)) {
            return; // filesystem refuses symlinks: nothing to verify here
        }

        String result = tools.readFile("link/secret.txt");

        assertThat(result).contains("outside the allowed workspace");
        assertThat(result).doesNotContain("top secret");
    }

    @Test
    void writeRejectsSymlinkPointingOutsideWorkspace() throws IOException {
        if (!createSymlink(workspace.resolve("link"), outside)) {
            return;
        }

        assertThat(tools.writeFile("link/planted.txt", "malicious"))
                .contains("outside the allowed workspace");
        assertThat(outside.resolve("planted.txt")).doesNotExist();
    }

    // ---------- missing files ----------

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
        assertThat(file).hasContent("new");
    }

    @Test
    void writeCreatesParentDirectories() {
        assertThat(tools.writeFile("deep/nested/file.md", "hello")).contains("Wrote 5 characters");
        assertThat(workspace.resolve("deep/nested/file.md")).hasContent("hello");
    }

    // ---------- read size guard ----------

    @Test
    void readRefusesOversizedFile() throws IOException {
        Path big = workspace.resolve("big.txt");
        Files.write(big, new byte[(int) FileTools.MAX_READ_BYTES + 1]);

        assertThat(tools.readFile("big.txt")).contains("exceeds the").contains("read limit");
    }

    @Test
    void readAllowsFileJustUnderLimit() throws IOException {
        Path edge = workspace.resolve("edge.txt");
        Files.writeString(edge, "x".repeat((int) FileTools.MAX_READ_BYTES - 1));

        assertThat(tools.readFile("edge.txt")).doesNotContain("exceeds the");
    }

    // ---------- listing ----------

    @Test
    void listReportsFilesAndDirectories() {
        tools.writeFile("subdir/file.txt", "x");
        tools.writeFile("plain.txt", "x");

        String result = tools.listFiles();

        assertThat(result).contains("subdir");
        assertThat(result).contains("plain.txt");
    }

    @Test
    void listHidesDotDirectories() throws IOException {
        Files.createDirectories(workspace.resolve(".agent"));
        Files.writeString(workspace.resolve(".agent/activity.log"), "{}");

        assertThat(tools.listFiles()).doesNotContain(".agent");
    }

    @Test
    void listReportsEmptyWorkspace() {
        assertThat(tools.listFiles()).isEqualTo("(workspace is empty)");
    }

    @Test
    void listTruncatesAtMaxEntries() {
        for (int i = 0; i < FileTools.MAX_LIST_ENTRIES + 5; i++) {
            tools.writeFile("f" + i + ".txt", "x");
        }

        assertThat(tools.listFiles()).contains("truncated at " + FileTools.MAX_LIST_ENTRIES);
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

        assertThat(tools.deleteFile("../outside/keep.txt"))
                .contains("outside the allowed workspace");
        assertThat(outside.resolve("keep.txt")).exists();
    }

    @Test
    void moveRenamesFile() {
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
    void moveRejectsTraversal() {
        tools.writeFile("innocent.txt", "x");

        assertThat(tools.moveFile("innocent.txt", "../outside/stolen.txt"))
                .contains("outside the allowed workspace");
        assertThat(outside.resolve("stolen.txt")).doesNotExist();
    }

    // ---------- helpers ----------

    /** Returns false when the platform/filesystem refuses symlinks, so the test can skip. */
    private static boolean createSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException e) {
            return false;
        }
    }
}
