package com.example.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for {@link GitTools}, run against a throwaway repository in a JUnit temp dir.
 *
 * <p>No network, no remote, no credentials, and nothing outside the temp dir is touched.
 * The property that matters most is the verification gate: a push is refused until a
 * build has passed against the current tree.
 */
class GitToolsTest {

    @TempDir
    Path tempDir;

    private Path repo;
    private GitTools git;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(gitBinary() != null, "git is not installed on this host");

        repo = Files.createDirectories(tempDir.resolve("repo"));
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        runGit("config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("seed.txt"), "seed");
        runGit("add", "-A");
        runGit("commit", "-q", "-m", "seed");
        runGit("branch", "-M", "main");

        git = new GitTools(repo, false, false, List.of("main"), "origin", "true");
    }

    // ---------- status / diff ----------

    @Test
    void currentBranchIsMain() {
        assertThat(git.currentBranch()).isEqualTo("main");
    }

    @Test
    void statusOnCleanTreeMentionsBranch() {
        assertThat(git.status()).contains("main");
    }

    @Test
    void statusShowsUncommittedChange() throws IOException {
        Files.writeString(repo.resolve("seed.txt"), "changed");

        assertThat(git.status()).contains("seed.txt");
    }

    @Test
    void diffIsEmptyWhenClean() {
        assertThat(git.diff()).isEqualTo("(no unstaged changes)");
    }

    @Test
    void diffShowsChangedFileWhenDirty() throws IOException {
        Files.writeString(repo.resolve("seed.txt"), "changed");

        assertThat(git.diff()).contains("seed.txt");
    }

    // ---------- verification gate ----------

    @Test
    void pushIsRefusedBeforeAnyVerification() {
        assertThat(git.push()).contains("Refusing to push").contains(":check");
    }

    @Test
    void pushIsRefusedWhenBuildFails() throws Exception {
        GitTools failing = new GitTools(repo, false, false, List.of("main"), "origin", "false");

        assertThat(failing.verify()).isFalse();
        assertThat(failing.push()).contains("Refusing to push");
    }

    @Test
    void verifyPassesAndUnlocksPushGate() {
        assertThat(git.verify()).isTrue();
        assertThat(git.isVerified()).isTrue();
    }

    @Test
    void commitInvalidatesVerification() throws IOException {
        git.verify();
        Files.writeString(repo.resolve("new.txt"), "content");

        git.commit("add new.txt");

        // A later edit must re-run the gate; a stale pass cannot authorise a push.
        assertThat(git.isVerified()).isFalse();
    }

    @Test
    void pushVerifiedRefusesWhenBuildIsRed() throws Exception {
        Files.writeString(repo.resolve("seed.txt"), "changed");
        GitTools failing = new GitTools(repo, false, false, List.of("main"), "origin", "false");

        String result = failing.pushVerified("should not happen");

        assertThat(result).contains("verification failed");
        // Nothing was committed: the edit is still uncommitted.
        assertThat(git.status()).contains("seed.txt");
    }

    // ---------- commit ----------

    @Test
    void commitRecordsChange() throws IOException {
        Files.writeString(repo.resolve("new.txt"), "content");

        String result = git.commit("add new.txt");

        assertThat(result).doesNotStartWith("Error:");
        assertThat(git.status()).doesNotContain("new.txt");
    }

    @Test
    void commitWithNothingChangedReportsClean() {
        assertThat(git.commit("no-op")).contains("nothing to commit");
    }

    @Test
    void commitRejectsBlankMessage() {
        assertThat(git.commit("  ")).contains("commit message is required");
    }

    @Test
    void commitMessageIsOneArgvElementNotShellInput() throws Exception {
        Files.writeString(repo.resolve("new.txt"), "content");
        String hostile = "msg; touch pwned.txt && echo $(id)";

        git.commit(hostile);

        assertThat(runGit("log", "-1", "--pretty=%B")).contains("touch pwned.txt");
        assertThat(repo.resolve("pwned.txt")).doesNotExist();
    }

    // ---------- push ----------

    // FIX: added "throws Exception" because the GitTools constructor can throw
    // a checked exception (this was the compile error at line ~163).
    @Test
    void pushRejectsBranchOutsideAllowlist() throws Exception {
        git = new GitTools(repo, false, false, List.of("release"), "origin", "true");
        git.verify();

        assertThat(git.push()).contains("not in agent.git.branch-allowlist");
    }

    @Test
    void pushWithoutRemoteFailsAtGitNotAtTheGate() {
        git.verify();

        // No remote configured: this must fail in git, proving the gate opened and
        // the argv (git push origin main) was well formed rather than blocked early.
        assertThat(git.push()).contains("git push failed");
    }

    @Test
    void pushVerifiedPassesBuildBeforeCommitting() throws IOException {
        Files.writeString(repo.resolve("seed.txt"), "changed");

        String result = git.pushVerified("verified change");

        // Verify passes ("true"), so the commit happens before the push attempt fails.
        assertThat(result).contains("git push failed");
        assertThat(git.status()).doesNotContain("seed.txt");
    }

    // ---------- argv construction ----------

    @Test
    void verifyCommandSplitsIntoArgv() {
        assertThat(GitTools.shellSplit("mvn -q -B test"))
                .containsExactly("mvn", "-q", "-B", "test");
        assertThat(GitTools.shellSplit("   ")).isEmpty();
        assertThat(GitTools.shellSplit(null)).isEmpty();
    }

    // ---------- helpers ----------

    /** Absolute path to git, or null when the host has none. */
    private static String gitBinary() {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            Path candidate = Path.of(dir, "git");
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    /** Runs git in the temp repo, returns combined output, fails on non-zero exit. */
    private String runGit(String... args) throws Exception {
        List<String> argv = new ArrayList<>();
        argv.add(gitBinary());
        argv.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(repo.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("git " + String.join(" ", args) + " timed out");
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args)
                    + " failed (" + p.exitValue() + "): " + output);
        }
        return output;
    }
}