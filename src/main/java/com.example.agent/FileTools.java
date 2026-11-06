package com.example.agent;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileTools {

    private final Path root;

    public FileTools(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    @Tool(description = "Write text content to a file inside the workspace folder. "
            + "Creates parent directories if needed and overwrites an existing file.")
    public String writeFile(
            @ToolParam(description = "Relative file path, e.g. 'notes/todo.txt'") String relativePath,
            @ToolParam(description = "The full text content to write") String content) {
        try {
            Path target = root.resolve(relativePath).normalize();

            // block path traversal like ../../etc/passwd
            if (!target.startsWith(root)) {
                return "Error: path is outside the allowed workspace.";
            }

            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return "Wrote " + content.length() + " characters to " + root.relativize(target);
        } catch (IOException e) {
            return "Error writing file: " + e.getMessage();
        }
    }

    @Tool(description = "List files in the workspace folder. Returns a comma-separated list of quoted relative paths.")
    public String listFiles() {
        try (var stream = Files.walk(root)) {
            var files = stream.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .filter(rel -> !isHidden(rel))
                    .map(rel -> "\"" + rel + "\"")
                    .toList();
            return files.isEmpty() ? "(workspace is empty)" : String.join(", ", files);
        } catch (IOException e) {
            return "Error listing files: " + e.getMessage();
        }
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
            + "Use this before editing or rewriting a file.")
    public String readFile(
            @ToolParam(description = "Relative file path, e.g. 'notes/todo.txt'") String relativePath) {
        try {
            Path target = root.resolve(relativePath).normalize();

            if (!target.startsWith(root)) {
                return "Error: path is outside the allowed workspace.";
            }
            if (!Files.isRegularFile(target)) {
                return "Error: file not found: " + relativePath;
            }

            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }
}