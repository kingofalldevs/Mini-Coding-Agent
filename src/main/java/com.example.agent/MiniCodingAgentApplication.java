package com.example.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Scanner;

@SpringBootApplication
public class MiniCodingAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniCodingAgentApplication.class, args);
    }

    @Bean
    ChatMemory chatMemory() {
        // Keeps conversation history for the life of the process.
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .build();
    }

    /** Append-only record of every turn and tool call, readable by the agent and by you. */
    @Bean
    ActivityLog activityLog(@Value("${agent.workspace}") String workspace) throws Exception {
        return new ActivityLog(Path.of(workspace));
    }

    @Bean
    FileTools fileTools(@Value("${agent.workspace}") String workspace) throws Exception {
        return new FileTools(Path.of(workspace));
    }

    /**
     * The raw tool callbacks, exposed as a bean so both the REPL and the HTTP
     * controller can wrap them with a per-request {@link LoggingToolCallback}.
     */
    @Bean
    ToolCallbackProvider toolCallbackProvider(FileTools fileTools) {
        ToolCallback[] callbacks = ToolCallbacks.from(fileTools);
        return () -> callbacks;
    }

    /**
     * Base client: system prompt and memory only, deliberately without tools.
     * Tools are attached per request so each call is logged with the right
     * conversation id.
     */
    @Bean
    ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory) {
        return builder
                .defaultSystem("""
                            You are a helpful assistant that can create, read, and save files.
                            - Use listFiles to see what exists before assuming a file is missing.
                            - Use readFile before editing or rewriting an existing file.
                            - Use writeFile to create or overwrite a file. Overwriting an existing path replaces it,
                              so reuse the same path when the user asks to modify a file.
                            - Give files a sensible extension (e.g. whoiam.md, not a bare name).
                            - Always confirm which file you wrote.
                            - Only create or modify files the user explicitly asks for.
                            - Every turn and tool call is recorded in .agent/activity.log. If the user asks what
                              you did earlier, say what you remember and point them at that log.
                            - Never create test, probe, or placeholder files.
                            """)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /** Builds a client whose tools all report to the activity log under {@code conversationId}. */
    static ChatClient loggedClient(ChatClient base, ToolCallbackProvider tools,
                                   ActivityLog log, String conversationId) {
        ToolCallback[] wrapped = Arrays.stream(tools.getToolCallbacks())
                .map(cb -> (ToolCallback) new LoggingToolCallback(cb, log, conversationId))
                .toArray(ToolCallback[]::new);
        return base.mutate().defaultToolCallbacks(wrapped).build();
    }

    @Bean
    CommandLineRunner repl(ChatClient chatClient,
                           ToolCallbackProvider tools,
                           ActivityLog log) {
        return args -> {
            String conversationId = "repl";
            ChatClient client = loggedClient(chatClient, tools, log, conversationId);

            Scanner in = new Scanner(System.in);
            System.out.println("Mini coding agent ready. Type 'exit' to quit, ':log' to see recent activity.");
            while (true) {
                System.out.print("\nyou> ");
                if (!in.hasNextLine()) break;
                String line = in.nextLine();
                if (line.isBlank()) continue;
                if (line.equalsIgnoreCase("exit")) break;
                if (line.equalsIgnoreCase(":log")) {
                    System.out.println(log.tail(20));
                    continue;
                }

                System.out.print("agent> ");
                long started = System.currentTimeMillis();
                boolean failed = false;
                try {
                    client.prompt()
                            .user(line)
                            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                            .stream()
                            .content()
                            .doOnNext(System.out::print)
                            .blockLast();
                } catch (RuntimeException e) {
                    failed = true;
                    System.out.println("\n[error] " + e.getMessage());
                } finally {
                    log.turn(conversationId, line, System.currentTimeMillis() - started, failed);
                }
                System.out.println();
            }
        };
    }
}
