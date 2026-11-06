package com.example.agent;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class FileTools {

    /** Files larger than this are refused by readFile, to protect the context window. */
    static final long MAX_READ_BYTES = 256L * 1024;

    /** How deep listFiles descends below the workspace root. */
    static final int MAX_LIST_DEPTH = 8;

    /** Hard cap on how many paths listFiles reports. */
    static final int MAX_LIST_ENTRIES = 200;

    private final Path root;

    public FileTools(Path root) throws IOException {
        Files.createDirectories(root);
        // Real path: resolves symlinks so containment checks below compare like with like.
        this.root = root.toRealPath();
        System.out.println(">>> Agent workspace: " + this.root);
    }

    @Tool(description = "Write text content to a file inside the workspace folder. "
            + "Creates parent directories if needed and overwrites an existing file.")
    public String writeFile(
            @ToolParam(description = "Relative file path, e.g. 'notes/todo.txt'") String relativePath,
            @ToolParam(description = "The full text content to write") String content) {
        try {
            Path target = resolveInside(relativePath);
            if (target == null) {
                return "Error: path is outside the allowed workspace.";
            }

            Path parent = target.getParent();
            if (parent == null) {
                return "Error: invalid target path: " + relativePath;
            }
            Files.createDirectories(parent);

            // Re-check after creating directories, following any symlinks that now exist.
            Path realParent = parent.toRealPath();
            if (!realParent.startsWith(root)) {
                return "Error: path is outside the allowed workspace.";
            }

            Path destination = realParent.resolve(target.getFileName());
            Files.writeString(destination, content, StandardCharsets.UTF_8);
            return "Wrote " + content.length() + " characters to " + root.relativize(destination);
        } catch (IOException e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    @Tool(description = "List files and directories in the workspace folder, "
            + "up to " + MAX_LIST_DEPTH + " levels deep and at most " + MAX_LIST_ENTRIES + " entries. "
            + "Directories are shown with a trailing '/'. Returns a comma-separated list of quoted relative paths.")
    public String listFiles() {
        List<String> entries = new ArrayList<>();
        boolean truncated = false;

        try (Stream<Path> stream = Files.walk(root, MAX_LIST_DEPTH)) {
            List<Path> sorted = stream
                    .filter(path -> !path.equals(root))
                    .filter(path -> !isHidden(root.relativize(path)))
                    .sorted(Comparator.comparing(root::relativize))
                    .toList();

            for (Path path : sorted) {
                if (entries.size() >= MAX_LIST_ENTRIES) {
                    truncated = true;
                    break;
                }
                String rel = root.relativize(path).toString();
                entries.add("\\\"" + rel + (Files.isDirectory(path) ? "/" : "") + "\\\"");
            }
        } catch (IOException e) {
            return "Error listing files: " + e.getMessage();
        }

        String body = entries.isEmpty() ? "(workspace is empty)" : String.join(", ", entries);
        return truncated
                ? body + " … (truncated at " + MAX_LIST_ENTRIES + " entries; "
                        + "narrow the workspace or raise MAX_LIST_ENTRIES)"
                : body;
    }

    // true if any part of the path starts with a dot (.DS_Store, .git/, etc.)
    private boolean isHidden(Path relative) {
        for (Path part : relative) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    @Tool(description = "Read the text content of an existing file inside the workspace folder. "
            + "Files larger than " + (MAX_READ_BYTES / 1024) + " KB are refused. "
            + "Use this before editing or rewriting a file.")
    public String readFile(
            @ToolParam(description = "Relative file path, e.g. 'notes/todo.txt'") String relativePath) {
        try {
            Path target = resolveInside(relativePath);
            if (target == null) {
                return "Error: path is outside the allowed workspace.";
            }
            if (!Files.isRegularFile(target)) {
                return "Error: file not found: " + relativePath;
            }

            long size = Files.size(target);
            if (size > MAX_READ_BYTES) {
                return "Error: file is " + (size / 1024) + " KB, which exceeds the "
                        + (MAX_READ_BYTES / 1024) + " KB read limit: " + relativePath;
            }

            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    @Tool(description = "Delete a file inside the workspace folder. Directories cannot be deleted.")
    public String deleteFile(
            @ToolParam(description = "Relative file path, e.g. 'notes/old.txt'") String relativePath) {
        try {
            Path target = resolveInside(relativePath);
            if (target == null) {
                return "Error: path is outside the allowed workspace.";
            }
            if (Files.isDirectory(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return "Error: refusing to delete a directory: " + relativePath;
            }
            if (!Files.isRegularFile(target)) {
                return "Error: file not found: " + relativePath;
            }

            Files.delete(target);
            return "Deleted " + relativePath;
        } catch (IOException e) {
            return "Error deleting file: " + e.getMessage();
        }
    }

    @Tool(description = "Move or rename a file inside the workspace folder. "
            + "Fails if the destination already exists.")
    public String moveFile(
            @ToolParam(description = "Relative source path, e.g. 'notes/draft.txt'") String from,
            @ToolParam(description = "Relative destination path, e.g. 'notes/final.txt'") String to) {
        try {
            Path source = resolveInside(from);
            Path destination = resolveInside(to);
            if (source == null || destination == null) {
                return "Error: path is outside the allowed workspace.";
            }
            if (!Files.isRegularFile(source)) {
                return "Error: file not found: " + from;
            }
            if (Files.exists(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return "Error: destination already exists: " + to;
            }

            Path parent = destination.getParent();
            if (parent == null) {
                return "Error: invalid destination path: " + to;
            }
            Files.createDirectories(parent);

            Path realParent = parent.toRealPath();
            if (!realParent.startsWith(root)) {
                return "Error: path is outside the allowed workspace.";
            }

            Path finalDestination = realParent.resolve(destination.getFileName());
            Files.move(source, finalDestination, StandardCopyOption.ATOMIC_MOVE);
            return "Moved " + from + " to " + root.relativize(finalDestination);
        } catch (IOException e) {
            return "Error moving file: " + e.getMessage();
        }
    }

    /**
     * Resolves a relative path against the workspace root and returns the real (symlink-free) path,
     * or {@code null} if it escapes the workspace. Callers must not assume the target exists.
     */
    private Path resolveInside(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path candidate = root.resolve(relativePath).normalize();
        if (!candidate.startsWith(root)) {
            return null;
        }

        // Walk up to the nearest existing ancestor and resolve *that* for real, so a symlinked
        // directory inside the workspace cannot be used to reach outside it.
        Path existing = candidate;
        while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return null;
        }

        Path realExisting = existing.toRealPath();
        if (!realExisting.startsWith(root)) {
            return null;
        }
        return realExisting.resolve(existing.relativize(candidate)).normalize();
    }
}
