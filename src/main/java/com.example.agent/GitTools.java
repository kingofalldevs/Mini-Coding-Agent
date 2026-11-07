package com.example.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Git operations for the agent's project, deliberately kept out of the model's
 * tool surface.
 *
 * <p><b>Why these are not {@code @Tool}s.</b> Everything the model can call, a
 * prompt-injected file can also cause it to call. A push is not recoverable: a
 * commit that lands on {@code main} is already shipped by the time anyone reads
 * the transcript. So the mutating operations here are invoked by the <em>user</em>
 * via REPL commands ({@code :status}, {@code :diff}, {@code :check}, {@code :commit},
 * {@code :push}) and never registered as callbacks. The model still sees git state
 * indirectly, through {@code :log} and through the files it can read.
 *
 * <p><b>No shell.</b> Every command runs through {@link ProcessBuilder} with an
 * argv list. {@code bash -c} is never used, so commit messages, branch names and
 * file names cannot smuggle in {@code ;}, {@code |}, {@code $(...)} or redirections.
 *
 * <p><b>Verify before push.</b> {@link #push()} refuses to run unless
 * {@link #verify()} has passed since the last change to the working tree. That is
 * enforced by {@link #pushVerified(String)}, which is the only push path the REPL uses.
 * If the build is red, nothing is committed and nothing leaves the machine.
 *
 * <p><b>Credentials.</b> The token is read from the host environment at call time
 * and passed only to the child {@code git} process. It is never written to disk,
 * never put in a remote URL (URLs persist in {@code .git/config} and the reflog),
 * and never placed under the workspace where {@code readFile} could reach it.
 *
 * <p>Not thread-safe by design: it is driven from the single REPL thread, and a
 * concurrent push from the web UI is not a thing we want to support.
 */
public class GitTools {

    /** Hard cap on any single git invocation, so a hung credential prompt cannot wedge the REPL. */
    static final int COMMAND_TIMEOUT_SECONDS = 120;

    /** Build/test gate gets longer: a cold {@code mvn test} is slow. */
    static final int VERIFY_TIMEOUT_SECONDS = 600;

    /** Captured output is truncated to this, per stream, before it reaches the model or the log. */
    static final int MAX_CAPTURED_CHARS = 8 * 1024;

    private final Path repo;
    private final boolean autoPush;
    private final boolean allowForce;
    private final List<String> branchAllowlist;
    private final String remote;
    private final String verifyCommand;

    /** Timestamp of the last successful verification, or null. */
    private Long verifiedAtMs;

    /** Whether the working tree has changed since {@link #verifiedAtMs}. */
    private boolean dirtySinceVerify = true;

    public GitTools(Path repo,
                    boolean autoPush,
                    boolean allowForce,
                    List<String> branchAllowlist,
                    String remote,
                    String verifyCommand) throws IOException {
        if (!Files.isDirectory(repo)) {
            throw new IOException("agent.git.repo does not exist: " + repo);
        }
        this.repo = repo.toRealPath();
        this.autoPush = autoPush;
        this.allowForce = allowForce;
        this.branchAllowlist = branchAllowlist == null ? List.of() : List.copyOf(branchAllowlist);
        this.remote = remote == null || remote.isBlank() ? "origin" : remote;
        this.verifyCommand = verifyCommand == null || verifyCommand.isBlank()
                ? "mvn -q -B test"
                : verifyCommand;

        if (!Files.isDirectory(this.repo.resolve(".git"))) {
            System.out.println(">>> WARN: " + this.repo + " is not a git repository; "
                    + "commit/push commands will fail until it is.");
        } else {
            System.out.println(">>> Git repo:            " + this.repo);
            System.out.println(">>> Git auto-push:       " + (autoPush ? "ON" : "off"));
            System.out.println(">>> Git verify command:  " + this.verifyCommand);
        }
    }

    // ---------------------------------------------------------------- queries

    /** Short status plus current branch. Safe: read-only. */
    public String status() {
        Result branch = exec(List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), 15);
        if (branch.exitCode != 0) {
            return "Error: not a git repository, or no commits yet.\n" + branch.output();
        }
        Result st = exec(List.of("git", "status", "--short", "--branch"), 15);
        return st.output().isBlank() ? "(working tree clean)" : st.output();
    }

    /** Diff of unstaged changes, capped. */
    public String diff() {
        Result r = exec(List.of("git", "diff", "--stat", "--patch"), 30);
        return r.exitCode == 0 ? (r.output().isBlank() ? "(no unstaged changes)" : r.output())
                : "Error: " + r.output();
    }

    /** Name of the current branch, or null if detached/unavailable. */
    public String currentBranch() {
        Result r = exec(List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), 15);
        if (r.exitCode != 0) {
            return null;
        }
        String name = r.output().trim();
        return name.isBlank() || name.equals("HEAD") ? null : name;
    }

    // ----------------------------------------------------------------- verify

    /**
     * Runs the configured build/test command in the repo and records the outcome.
     * This is the gate {@link #pushVerified(String)} depends on.
     *
     * @return {@code true} only if the command exited 0
     */
    public synchronized boolean verify() {
        String[] argv = shellSplit(verifyCommand);
        if (argv.length == 0) {
            System.out.println("[verify] no verify command configured; refusing to treat as passed.");
            verifiedAtMs = null;
            return false;
        }
        System.out.println("[verify] running: " + String.join(" ", argv));
        Result r = exec(List.of(argv), VERIFY_TIMEOUT_SECONDS);
        System.out.println(r.output());
        if (r.exitCode == 0) {
            verifiedAtMs = System.currentTimeMillis();
            dirtySinceVerify = false;
            System.out.println("[verify] PASSED");
            return true;
        }
        verifiedAtMs = null;
        dirtySinceVerify = true;
        System.out.println("[verify] FAILED (exit " + r.exitCode + ") — nothing will be pushed.");
        return false;
    }

    /** True if a verify has passed and no change has been made since. */
    public synchronized boolean isVerified() {
        return verifiedAtMs != null && !dirtySinceVerify;
    }

    /** Marks the tree dirty, so a stale verification can never authorise a push. */
    public synchronized void invalidateVerification() {
        dirtySinceVerify = true;
    }

    // ---------------------------------------------------------------- commit

    /**
     * {@code git add -A} + {@code git commit}. Local only; never contacts a remote.
     * Any commit made through this public entry point invalidates verification.
     *
     * @return a human-readable outcome, prefixed with {@code Error:} on failure
     */
    public synchronized String commit(String message) {
        invalidateVerification();
        return doCommit(message);
    }

    /**
     * Commit without touching the verification flag. Only {@link #pushVerified(String)}
     * uses this directly: it commits exactly the tree that was just verified, so the
     * verification is still valid for the push that follows.
     */
    private String doCommit(String message) {
        if (message == null || message.isBlank()) {
            return "Error: commit message is required.";
        }

        Result added = exec(List.of("git", "add", "-A"), 30);
        if (added.exitCode != 0) {
            return "Error: git add failed.\n" + added.output();
        }

        Result staged = exec(List.of("git", "diff", "--cached", "--quiet"), 30);
        if (staged.exitCode == 0) {
            return "(nothing to commit, working tree clean)";
        }

        // Message is a single argv element: no quoting, no interpolation, no injection.
        Result committed = exec(List.of("git", "commit", "-m", message), 60);
        if (committed.exitCode != 0) {
            return "Error: git commit failed.\n" + committed.output();
        }
        return committed.output();
    }

    // ------------------------------------------------------------------ push

    /**
     * Verifies, then commits, then pushes — the only push path the REPL exposes.
     *
     * <p>Order matters: the build runs <em>before</em> the commit, so a red build
     * leaves the working tree exactly as the user left it rather than half-committed.
     *
     * @param message commit message, used only if there is something to commit
     */
    public synchronized String pushVerified(String message) {
        if (!verify()) {
            return "Refusing to push: verification failed. Fix the build first.";
        }
        String committed = doCommit(message);
        if (committed.startsWith("Error:")) {
            return committed;
        }
        return push();
    }

    /**
     * {@code git push <remote> <exact-ref>}. Never bare {@code git push} (which obeys
     * {@code push.default}), never {@code --force} unless explicitly enabled, and
     * never a branch outside the allowlist.
     */
    public synchronized String push() {
        if (!isVerified()) {
            return "Refusing to push: code has not been verified since the last change. "
                    + "Run :check first.";
        }

        String branch = currentBranch();
        if (branch == null) {
            return "Error: cannot determine current branch (detached HEAD or no commits).";
        }
        if (!branchAllowlist.isEmpty() && !branchAllowlist.contains(branch)) {
            return "Error: branch '" + branch + "' is not in agent.git.branch-allowlist "
                    + branchAllowlist + ". Refusing to push.";
        }

        List<String> argv = new ArrayList<>(List.of("git", "push", remote, branch));
        if (allowForce) {
            argv.add("--force-with-lease");
        }

        Map<String, String> env = gitEnv();
        Result r = exec(argv, COMMAND_TIMEOUT_SECONDS, env);
        if (r.exitCode != 0) {
            return "Error: git push failed.\n" + r.output();
        }
        return r.output().isBlank() ? "Pushed " + branch + " to " + remote : r.output();
    }

    public boolean isAutoPush() {
        return autoPush;
    }

    /**
     * Environment for child git processes. The token, if present, is passed through
     * so a credential helper can use it; it is never echoed or written anywhere.
     * {@code GIT_TERMINAL_PROMPT=0} makes a missing credential fail fast instead of
     * hanging the REPL forever on a username prompt.
     */
    private Map<String, String> gitEnv() {
        String token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            return Map.of("GIT_TERMINAL_PROMPT", "0");
        }
        // askpass reads the env var; no token on the command line or in .git/config.
        return Map.of(
                "GIT_TERMINAL_PROMPT", "0",
                "GIT_ASKPASS", "/bin/echo",
                "GITHUB_TOKEN", token);
    }

    // --------------------------------------------------------------- process

    private Result exec(List<String> argv, int timeoutSeconds) {
        return exec(argv, timeoutSeconds, Map.of("GIT_TERMINAL_PROMPT", "0"));
    }

    private Result exec(List<String> argv, int timeoutSeconds, Map<String, String> extraEnv) {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(repo.toFile());
        pb.redirectErrorStream(true);
        if (!extraEnv.isEmpty()) {
            pb.environment().putAll(extraEnv);
        }

        try {
            Process p = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() < MAX_CAPTURED_CHARS) {
                        sb.append(line).append('\n');
                    }
                }
                output = sb.toString();
            }

            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(-1, "Error: timed out after " + timeoutSeconds + "s: "
                        + String.join(" ", argv) + "\n" + truncate(output));
            }
            return new Result(p.exitValue(), truncate(output));
        } catch (IOException e) {
            return new Result(-1, "Error: could not run " + argv.get(0) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "Error: interrupted while running " + String.join(" ", argv));
        }
    }

    private static String truncate(String s) {
        return s.length() > MAX_CAPTURED_CHARS
                ? s.substring(0, MAX_CAPTURED_CHARS) + "\n… (truncated)"
                : s;
    }

    /**
     * Splits the verify command on whitespace. Intentionally simple: the value comes
     * from {@code application.properties} (trusted, developer-controlled), not from
     * the model or the network, and keeping it argv-only means no shell is involved.
     *
     * <p>Blank or null input yields an empty array. Without the explicit check,
     * {@code "".split("\\s+")} returns {@code [""]}, i.e. one empty program name.
     */
    static String[] shellSplit(String command) {
        if (command == null || command.isBlank()) {
            return new String[0];
        }
        return command.trim().split("\\s+");
    }

    private record Result(int exitCode, String output) {
    }
}